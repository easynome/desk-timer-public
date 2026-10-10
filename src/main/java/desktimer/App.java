package desktimer;

import desktimer.backup.Backuper;
import desktimer.config.TimerHome;
import desktimer.core.parse.CaptureParser;
import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.core.time.Recovery;
import desktimer.db.DataSource;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import desktimer.db.Migrations;
import desktimer.db.Note;
import desktimer.db.NoteRepository;
import desktimer.db.SettingRepository;
import desktimer.db.Todo;
import desktimer.db.TodoRepository;
import desktimer.export.Exporter;
import desktimer.inbox.InboxAbsorber;
import desktimer.platform.Autostart;
import desktimer.platform.HotkeyAdapter;
import desktimer.platform.FullScreenDetector;
import desktimer.platform.KeySpec;
import desktimer.platform.RunKeyAutostart;
import desktimer.platform.SingleInstance;
import desktimer.platform.SystemThemeProbe;
import desktimer.platform.TrayAdapter;
import desktimer.ui.KeyMap;
import desktimer.ui.SettingsWindow;
import desktimer.ui.TimerPanel;
import desktimer.ui.UiTheme;
import desktimer.ui.UiController;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;

/**
 * 常驻主进程：单实例锁 → 数据目录 → 迁移 → 启动对账 → 托盘 → JavaFX 界面 → 快捷键 → 心跳与导出。
 * 对账结果的排队呈现是 S9。
 */
public final class App {

  private static final CountDownLatch STOP = new CountDownLatch(1);
  /** 唤起快捷键在库里的键名与出厂默认（设置窗改的就是这一项）。 */
  private static final String WAKE_KEY = "hotkey.wake";
  private static final String THEME_KEY = "ui.theme";
  private static final String DEFAULT_WAKE = "ctrl+alt+f12";
  /**
   * 应用版本 —— 只有这一处。它会被写进备份 zip 的清单与导出的 manifest，
   * 而 {@code package.ps1} 的 {@code --app-version} 必须与它相同
   * （{@code AppVersionPinnedTest} 逐字读那个脚本比对；今天差点就出现"tag 是 0.9、zip 里写 0.1"）。
   */
  public static final String APP_VERSION = "1.0.0";

  public static void main(String[] args) throws Exception {
    boolean selfTest = hasFlag(args, "--selftest");
    int heartbeatSec = (int) numArg(args, "--heartbeat-sec", selfTest ? 1 : 30);
    long runSecs = numArg(args, "--run-secs", selfTest ? 2 : Long.MAX_VALUE);

    TimerHome home = TimerHome.resolveAndCreate();
    SingleInstance lock = SingleInstance.acquireOrNone(home.lockFile());
    if (lock == null) {
      System.out.println("ALREADY_RUNNING 已有一个 desk-timer 在运行（右下角托盘那个图标就是），本次启动退出");
      // 双击启动时没有控制台可看，只能就地说明一句；从命令行起的只打印，不多弹一窗
      if (System.console() == null && !selfTest) {
        javax.swing.JOptionPane.showMessageDialog(null,
            "desk-timer 已经在运行了。\n看右下角托盘图标，按唤起快捷键（出厂是 Ctrl+Alt+F12，"
                + "托盘右键→设置… 可改）叫出界面。",
            "desk-timer", javax.swing.JOptionPane.INFORMATION_MESSAGE);
      }
      System.exit(0);
      return;
    }
    boolean failed = false;
    try (SingleInstance held = lock;
         DataSource ds = new DataSource(home.dbFile());
         HotkeyAdapter hotkeys = new HotkeyAdapter()) {

      int version = Migrations.migrate(ds.conn());
      Clock clock = new Clock();
      EntryRepository entries = new EntryRepository(ds, clock);
      SettingRepository settings = new SettingRepository(ds, clock);
      NoteRepository notes = new NoteRepository(ds, clock);
      TodoRepository todos = new TodoRepository(ds, clock);
      Exporter exporter = new Exporter(ds, home.exportsDir(), clock, APP_VERSION);
      Backuper backuper = new Backuper(ds, home.backupDir(), clock, APP_VERSION, version);
      // 外部工具写 inbox/，应用读走：与 exports/ 方向相反，所以放启动导出的**前面**，
      // 刚收进来的那一段就能出现在同一份导出里
      InboxAbsorber inbox = new InboxAbsorber(entries, home.inboxDir(), clock);
      FullScreenDetector fullscreen = new FullScreenDetector();
      absorb(inbox);
      int exportSec = (int) numArg(args, "--export-sec", selfTest ? 2 : 60);

      // 启动对账的结果不进弹窗，进对账队列：托盘先亮，等用户唤起时再摊开
      List<Entry> pending = entries.reconcileRunning(Recovery.Cause.CRASH);
      System.out.println("RECONCILE_PENDING n=" + pending.size()
          + pending.stream().map(Entry::task).toList());
      export(exporter);
      // 每天第一次启动自动备份一份；托盘右键"立即备份"走同一个方法
      backup(backuper, settings, clock, "startup");

      UiController[] uiRef = new UiController[1];
      SettingsWindow[] settingsWin = new SettingsWindow[1];
      // 开机自启的状态读自注册表本身（HKCU 的 Run 值）；非打包版认不出 exe 路径，界面会把这一项藏起来
      Autostart autostart = new RunKeyAutostart(Autostart.exePath());
      boolean[] hotkeyOk = {true};
      AtomicInteger hits = new AtomicInteger();
      // 实际挂着的那个组合：设置窗显示的必须是它，而不是库里存的（启动时装失败两者会不一致）
      AtomicReference<KeySpec> liveSpec = new AtomicReference<>();
      // 面板动作键的唯一实例：设置窗改的就是它，改完立刻推给面板并落盘（M1-d2，范围 2）
      AtomicReference<KeyMap> panelKeys = new AtomicReference<>(KeyMap.from(storedPanelKeys(settings)));
      Runnable onWake = () -> {
        hits.incrementAndGet();
        System.out.println("HOTKEY_HIT");
        uiRef[0].wake();
      };
      TrayAdapter tray = new TrayAdapter("desk-timer", () -> uiRef[0].wake(), STOP::countDown,
          () -> backup(backuper, settings, clock, "manual"), () -> {
            if (settingsWin[0] == null) {
              System.out.println("SETTINGS_NOT_READY 界面还没起来，稍后再点一次");
            } else {
              settingsWin[0].show();
            }
          });
      tray.install();
      System.out.println("TRAY_OK state=" + TrayAdapter.State.NORMAL);

      // 换键：装得上才落盘。HotkeyAdapter 是先探测再摘旧监听，所以失败时旧键一个字都没动
      Function<KeySpec, String> rebind = spec -> {
        HotkeyAdapter.Registration r = hotkeys.register(spec, onWake);
        if (!r.ok()) {
          return r.reason();
        }
        liveSpec.set(spec);
        hotkeyOk[0] = true;
        // 托盘色先纠正：键已经真的挂上了，"没生效"的橙灯不能再亮着
        refreshTray(tray, entries, true);
        try {
          settings.put(WAKE_KEY, spec.canonical());
        } catch (SQLException e) {
          return "新键已经生效，但没能存进库：" + e.getMessage() + "，下次启动会退回上一次的键";
        }
        return null;
      };

      // JavaFX 起在快捷键之前：钩子回调要能直接唤起界面
      CountDownLatch fxUp = new CountDownLatch(1);
      Platform.startup(fxUp::countDown);
      Platform.setImplicitExit(false);
      fxUp.await();
      CountDownLatch uiUp = new CountDownLatch(1);
      AtomicReference<String> themePref = new AtomicReference<>(storedTheme(settings));
      Platform.runLater(() -> {
        UiTheme.Palette theme = resolveTheme(themePref.get());
        uiRef[0] = new UiController(entries, clock, () -> {
          export(exporter);
          refreshTray(tray, entries, hotkeyOk[0]);
        }, settings, notes, todos, fullscreen, theme);
        settingsWin[0] = new SettingsWindow(liveSpec::get, rebind, panelKeys::get, map -> {
          panelKeys.set(map);
          try {
            settings.put("key.panel", map.wire());
          } catch (SQLException e) {
            System.out.println("KEY_PANEL_SAVE_FAILED " + e.getMessage());
          }
          if (uiRef[0] != null) {
            uiRef[0].panel().applyKeys(map);
          }
          System.out.println("KEY_PANEL_SAVED " + (map.wire().isEmpty() ? "出厂" : map.wire()));
        }, () -> backup(backuper, settings, clock, "manual"), onWake, STOP::countDown, settings,
            autostart, theme, themePref::get, pref -> {
              // 换主题：先落库（下次开机还是这个），再让五个界面各重贴一遍样式。
              // 系统那一档每次现读注册表，所以"跟随系统"跟的是当下，不是启动那一刻的快照
              themePref.set(pref);
              try {
                settings.put(THEME_KEY, pref);
              } catch (SQLException e) {
                System.out.println("THEME_SAVE_FAILED " + e.getMessage());
              }
              UiTheme.Palette next = resolveTheme(pref);
              if (uiRef[0] != null) {
                uiRef[0].setTheme(next);
              }
              if (settingsWin[0] != null) {
                settingsWin[0].applyTheme(next);
              }
              System.out.println("THEME_PREFERENCE " + pref + " resolved=" + next.name());
            });
        uiUp.countDown();
      });
      uiUp.await();
      UiController ui = uiRef[0];
      // 面板自己也会读一次库（首启路径），这里再统一成 App 手里那一份，避免两个实例并存
      ui.panel().applyKeys(panelKeys.get());
      System.out.println("UI_READY capture=off panel=off");

      // 来源优先级：命令行 > 库里存的 > 出厂默认。命令行最高，因为传键是排障手段
      String fromArg = strArg(args, "--hotkey", null);
      String stored = storedWake(settings);
      String wanted = fromArg != null ? fromArg : (stored.isBlank() ? DEFAULT_WAKE : stored);
      KeySpec hotkey = wakeSpec(wanted);
      HotkeyAdapter.Registration reg = hotkeys.register(hotkey, onWake);
      if (reg.ok()) {
        liveSpec.set(hotkey);
      }
      System.out.println("HOTKEY ok=" + reg.ok() + " spec=" + reg.spec() + " source="
          + (fromArg != null ? "argv" : (stored.isBlank() ? "default" : WAKE_KEY))
          + (reg.reason() == null ? "" : " reason=" + reg.reason()));
      // 注册失败不弹窗：托盘进异常态，右键就能进设置窗改键
      hotkeyOk[0] = reg.ok();
      refreshTray(tray, entries, reg.ok());
      System.out.println("HINT 按 " + hotkey.canonical()
          + " 唤起：优先级 计时面板 > 待确认卡 > 待办卡 > 捕获窗；托盘左键同一条路。"
          + " 一个输入框三种用途：`25m 任务` 开始计时、`! 正文` 记一笔、`+ 标题` 记待办，"
          + "后两种都不开始计时。换键：托盘右键→设置…，按下新组合当场生效。"
          + " 笔记本上 F12 被媒体键占用时按 Fn，或启动时传 --hotkey=ctrl+alt+t。");

      // 同一个单线程池：心跳与导出天然串行，不跟 SQLite 写锁抢人
      ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
      });
      pool.scheduleAtFixedRate(() -> beat(entries), heartbeatSec, heartbeatSec, TimeUnit.SECONDS);
      // 同一个 tick 里先收 inbox 再导出：外部刚写的那一段，最迟一个周期后就出现在导出里
      pool.scheduleAtFixedRate(() -> {
        absorb(inbox);
        export(exporter);
      }, exportSec, exportSec, TimeUnit.SECONDS);

      System.out.println("READY home=" + home.root() + " user_version=" + version
          + " heartbeat_sec=" + heartbeatSec);

      try {
        if (selfTest) {
          selfTest(entries, clock, exporter, hotkey, hits);
          uiSelfTest(ui, entries, exporter, settings, ds);
          queueSelfTest(ui, entries, exporter, ds, clock);
          noteSelfTest(ui, notes, exporter);
          backupSelfTest(backuper, ds);
          todoSelfTest(ui, todos, exporter, settings, ds, clock);
          inboxSelfTest(inbox, home, exporter);
          dndSelfTest(ui, settings, fullscreen, entries, clock);
          pomodoroSelfTest(ui, entries, ds, settings);
          parallelSelfTest(ui, ds);
          overlapSelfTest(ui, entries, exporter, ds, clock);
          themeSelfTest(ui, settings);
          notesCardSelfTest(ui, ds, notes);
          autostartSelfTest();
          placementSelfTest(settings);
          keymapSelfTest(ui, settingsWin[0], settings, ds);
          settingsSelfTest(settingsWin[0], settings, liveSpec, hits, ui);
        }
        boolean byRequest = STOP.await(runSecs, TimeUnit.SECONDS);
        System.out.println("EXIT reason=" + (byRequest ? "tray-or-signal" : "run-secs-timeout"));
      } catch (Exception e) {
        // JNativeHook 的分发线程不是守护线程：main 抛了异常 JVM 也不会自己退，
        // 所以失败必须在这里收口，否则进程会带着已关闭的数据库连接一直转。
        System.out.println("RUN_FAILED " + e);
        failed = true;
      } finally {
        pool.shutdownNow();
        tray.remove();
      }
    }
    System.exit(failed ? 1 : 0);
  }

  private static void beat(EntryRepository entries) {
    try {
      int n = entries.touchHeartbeats();
      if (n > 0) {
        System.out.println("HEARTBEAT rows=" + n);
      }
    } catch (Exception e) {
      System.out.println("HEARTBEAT_FAILED " + e.getMessage());
    }
  }

  /** 托盘色就是打扰阶梯的 L1：待确认 > 计时中 > 正常；快捷键没注册上也算待确认。 */
  private static void refreshTray(TrayAdapter tray, EntryRepository entries, boolean hotkeyOk) {
    try {
      TrayAdapter.State state;
      if (!hotkeyOk || !entries.reviewQueue().isEmpty()) {
        state = TrayAdapter.State.ATTENTION;
      } else if (entries.unfinished().isEmpty()) {
        state = TrayAdapter.State.NORMAL;
      } else {
        state = TrayAdapter.State.RUNNING;
      }
      tray.setState(state);
    } catch (Exception e) {
      System.out.println("TRAY_STATE_FAILED " + e.getMessage());
    }
  }

  /** 吸收失败不打扰用户（与备份同一条策略）：坏行自己有 rejected-* 可查，别弹窗。 */
  private static void absorb(InboxAbsorber inbox) {
    try {
      inbox.absorbOnce();
    } catch (Exception e) {
      System.out.println("INBOX_FAILED " + e.getMessage());
    }
  }

  private static void export(Exporter exporter) {
    try {
      Exporter.Result r = exporter.exportAll();
      System.out.println("EXPORTED rows=" + r.rows() + " sha256_12=" + r.sha256_12()
          + " dir=" + r.dir());
    } catch (Exception e) {
      System.out.println("EXPORT_FAILED " + e.getMessage());
    }
  }

  /** 走正式路径：解析 → 建条目（含项目与标签）→ 心跳 → 单调时钟算时长 → 结束 → 导出可被消费方解析。 */
  private static void selfTest(EntryRepository entries, Clock clock, Exporter exporter,
      KeySpec hotkey, AtomicInteger hits) throws Exception {
    // 快捷键自检：注入一次，等全局钩子回调。收不到就是失败，不做"可能环境不允许"的宽限。
    Thread sender = new Thread(() -> {
      try {
        Thread.sleep(800);
        inject(hotkey);
      } catch (Exception e) {
        System.out.println("HOTKEY_INJECT_FAILED " + e.getMessage());
      }
    }, "hotkey-probe");
    sender.setDaemon(true);
    sender.start();
    long deadline = System.currentTimeMillis() + 4000;
    while (hits.get() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    System.out.println("SELFTEST_HOTKEY spec=" + hotkey.canonical() + " hits=" + hits.get());
    if (hits.get() == 0) {
      throw new IllegalStateException("SELFTEST_FAILED 全局快捷键没有收到按键");
    }

    CaptureParser.Plan plan = new CaptureParser().parse("25m 骨架自检 #自检 @骨架项目");
    System.out.println("PARSE kind=" + plan.kind().wire() + " target_ms=" + plan.targetMs()
        + " task=" + plan.task() + " tags=" + plan.tags() + " project=" + plan.project()
        + " needs_review=" + plan.needsReview());
    if (plan.needsReview() || plan.targetMs() == null || !plan.tags().equals(List.of("自检"))) {
      throw new IllegalStateException("SELFTEST_FAILED 解析计划不符合预期");
    }

    long baseElapsed = clock.elapsedMs();
    String projectId = entries.ensureProject(plan.project());
    Entry started = entries.start(plan.task(), plan.kind(), projectId, false);
    entries.attachTags(started.id(), plan.tags());
    System.out.println("CONFLICTS n=" + entries.conflictsFor(projectId, started.startedAt(), null)
        .size() + " key=" + started.exclusivityKey());

    Thread.sleep(2500);
    Entry seen = entries.find(started.id()).orElseThrow();
    boolean heartbeatOk = seen.lastHeartbeatAt() != null;
    long durationMs = clock.elapsedMs() - baseElapsed;
    entries.finish(started.id(), durationMs);

    Entry done = entries.find(started.id()).orElseThrow();
    List<Entry> unfinished = entries.unfinished();
    System.out.println("SELFTEST entry=" + done.id() + " status=" + done.status().wire()
        + " started_at=" + done.startedAt() + " checkin_date=" + done.checkinDate()
        + " heartbeat_seen=" + heartbeatOk + " duration_ms=" + done.durationMs()
        + " unfinished=" + unfinished.size());
    if (!heartbeatOk || done.durationMs() == null || done.durationMs() <= 0
        || !unfinished.isEmpty()) {
      throw new IllegalStateException("SELFTEST_FAILED");
    }
    Exporter.Result exported = exporter.exportAll();
    System.out.println("SELFTEST_EXPORT rows=" + exported.rows()
        + " sha256_12=" + exported.sha256_12() + " dir=" + exported.dir());
    if (exported.rows() < 1) {
      throw new IllegalStateException("SELFTEST_FAILED 导出里没有条目");
    }
    System.out.println("SELFTEST_OK");
  }

  /**
   * UI 链路自检：唤起捕获窗 → 敲一行 → 回车 → 面板跑表 → 截图取证 → 结束 → 导出。
   * 与 selfTest 分开是因为它们证明的是不同的事：那边是数据语义，这边是"用户那条路走得通"。
   */
  private static void uiSelfTest(UiController ui, EntryRepository entries, Exporter exporter,
      SettingRepository settings, DataSource ds) throws Exception {
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(500);
    boolean captureShown = ui.capture().showing();
    ui.capture().type("25m 界面自检 #自检 @界面项目");
    Thread.sleep(300);
    screenshot("target/selftest-capture.png");
    ui.capture().submit();
    Thread.sleep(800);
    String activeId = ui.activeEntryId();
    boolean panelShown = ui.panel().showing();
    screenshot("target/selftest-ui.png");

    // 回归：跑着计时时再按快捷键，抬起来的必须是面板（能停能结束），
    // 而不是又一个输入框 —— 用户反馈"无法中断计时"的根因。
    ui.capture().setAutoHideOnFocusLost(true);
    ui.wake();
    Thread.sleep(900);
    boolean wakeRaisesPanel = ui.panel().showing() && !ui.capture().showing();
    // 钉住归 P 管（用户反馈：一直压顶层碍事）。默认不钉 ⇒ 不置顶；按一下钉住并置顶且写库；再按取消
    boolean startsUnpinned = !ui.panel().pinned();
    ui.panel().press(KeyCode.P);
    Thread.sleep(250);
    boolean pinnedByP = ui.panel().pinned() && "true".equals(settings.get("ui.panel.pinned", "?"));
    ui.panel().press(KeyCode.P);
    Thread.sleep(250);
    boolean unpinnedByP = !ui.panel().pinned()
        && "false".equals(settings.get("ui.panel.pinned", "?"));
    System.out.println("SELFTEST_UI wake_raises_panel=" + wakeRaisesPanel
        + " pin_default_off=" + startsUnpinned + " pin_on_by_P=" + pinnedByP
        + " pin_off_by_P=" + unpinnedByP + " panel_focused=" + ui.panel().focused());

    // 回归：TRANSPARENT 无边框窗没有标题栏，"拖不动"就是功能缺失，只能靠合成鼠标事件验。
    double px = ui.panel().x();
    double py = ui.panel().y();
    runOnFx(() -> dragBy(ui.panel().root(), 500, 500, 90, -40));
    Thread.sleep(150);
    boolean panelFollowed = near(ui.panel().x(), px + 90) && near(ui.panel().y(), py - 40);
    // 拖回原处再存：否则每跑一次自检窗口就往外挪一格，最后落到钳制分支上误报
    runOnFx(() -> dragBy(ui.panel().root(), 590, 460, -90, 40));
    Thread.sleep(150);
    boolean panelReturned = near(ui.panel().x(), px) && near(ui.panel().y(), py);
    runOnFx(() -> ui.panel().persistPosition());
    String saved = settings.get("ui.panel.bounds", "");
    TimerPanel[] reopened = new TimerPanel[1];
    runOnFx(() -> reopened[0] = blankPanel(settings));
    boolean posRestored = near(reopened[0].x(), px) && near(reopened[0].y(), py);

    // 输入框里的按下是"选字/放光标"，不许顺手把窗拖走
    double cx = ui.capture().x();
    Node input = firstChild(ui.capture().root());
    runOnFx(() -> dragBy(input, 640, 320, 150, 150));
    Thread.sleep(150);
    boolean inputBlocksDrag = near(ui.capture().x(), cx);
    System.out.println("SELFTEST_UI drag_follow=" + panelFollowed + " drag_back=" + panelReturned
        + " pos_saved=" + saved + " pos_restored=" + posRestored
        + " input_blocks_drag=" + inputBlocksDrag);
    if (!panelFollowed || !panelReturned || !posRestored || !inputBlocksDrag || saved.isEmpty()) {
      throw new IllegalStateException("SELFTEST_UI_FAILED 拖动或位置记忆不成立");
    }

    // 用 Enter 结束而不是直接调 finishActive()：这样连"面板的免字母备选键"一起验掉。
    // 并且先等过误触门限：这一段的职责是"回车能结束一段真在跑的计时"，
    // 时长不够时它按设计就该转成放弃（手滑那条路下面单独验）。
    clearMisfireGate();
    ui.panel().press(KeyCode.ENTER);
    Thread.sleep(400);
    Entry done = activeId == null ? null : entries.find(activeId).orElse(null);
    System.out.println("SELFTEST_UI capture_shown=" + captureShown + " panel_shown=" + panelShown
        + " session=" + (activeId != null) + " status="
        + (done == null ? "-" : done.status().wire())
        + " duration_ms=" + (done == null ? "-" : done.durationMs()));
    if (!captureShown || !panelShown || !wakeRaisesPanel || !startsUnpinned || !pinnedByP
        || !unpinnedByP
        || activeId == null || done == null
        || done.status() != EntryStatus.FINISHED || done.durationMs() == null
        || done.durationMs() < UiController.MISFIRE_MS) {
      throw new IllegalStateException("SELFTEST_UI_FAILED 回车没能结束一段（或时长没过误触门限）");
    }

    // 实测事故：倒计时阶段按一次回车，面板直接消失，账上留下一条"0 分钟已完成"。
    // 判据本身有单测，但"按下去确实记成 aborted、理由写着误触"只能在他走的这条路上验。
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(400);
    ui.capture().type("10m 误触自检 #自检 @界面项目");
    Thread.sleep(300);
    ui.capture().submit();
    Thread.sleep(600);
    String misfireId = ui.activeEntryId();
    ui.panel().press(KeyCode.ENTER);
    Thread.sleep(400);
    Entry slipped = misfireId == null ? null : entries.find(misfireId).orElse(null);
    String slippedReason = slipped == null ? "-" : textOfTask(ds, "误触自检", "aborted_reason");
    System.out.println("SELFTEST_UI_MISFIRE status="
        + (slipped == null ? "-" : slipped.status().wire())
        + " duration_ms=" + (slipped == null ? "-" : slipped.durationMs())
        + " reason=\"" + slippedReason + "\"");
    if (slipped == null || slipped.status() != EntryStatus.ABORTED
        || slipped.durationMs() == null || slipped.durationMs() >= UiController.MISFIRE_MS
        || !UiController.MISFIRE_REASON.equals(slippedReason)) {
      throw new IllegalStateException("SELFTEST_UI_FAILED 手滑回车没按误触入账");
    }
    ui.capture().setAutoHideOnFocusLost(true);
    export(exporter);
    if (!entries.unfinished().isEmpty()) {
      throw new IllegalStateException("SELFTEST_UI_FAILED 结束后仍有未完成条目");
    }
    System.out.println("SELFTEST_UI_OK");
  }

  /** 发一次"按下 → 拖 → 松手"，屏幕坐标位移 (dx,dy)。无边框窗的拖动只能这样自动验。 */
  private static void dragBy(Node handle, double screenX, double screenY, double dx, double dy) {
    fire(handle, MouseEvent.MOUSE_PRESSED, screenX, screenY);
    fire(handle, MouseEvent.MOUSE_DRAGGED, screenX + dx, screenY + dy);
    fire(handle, MouseEvent.MOUSE_RELEASED, screenX + dx, screenY + dy);
  }

  private static void fire(Node node, EventType<MouseEvent> type, double screenX, double screenY) {
    Event.fireEvent(node, new MouseEvent(type, 8, 8, screenX, screenY, MouseButton.PRIMARY, 1,
        false, false, false, false, true, false, false, false, true, false, null));
  }

  /** 场景根的第一个子节点：捕获窗那里就是输入框。走公开结构拿，不给产品代码加测试专用 getter。 */
  private static Node firstChild(Node root) {
    return ((Parent) root).getChildrenUnmodifiable().get(0);
  }

  private static boolean near(double actual, double expected) {
    return Math.abs(actual - expected) < 1.0;
  }

  /**
   * 自检要结束一段"真在跑的计时"，必须先跨过误触门限（低于门限按结束键就是设计成放弃的）。
   * 用等待而不是给产品代码开后门：门限本身就是用户能感知的行为，测试绕开它就等于没测。
   */
  private static void clearMisfireGate() throws InterruptedException {
    Thread.sleep(UiController.MISFIRE_MS + 200);
  }

  /** 鼠标事件必须在 FX 线程发；跑完再往下走，否则断言读到的是上一帧。 */
  private static void runOnFx(Runnable body) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    Platform.runLater(() -> {
      try {
        body.run();
      } finally {
        done.countDown();
      }
    });
    if (!done.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("SELFTEST_UI_FAILED FX 线程 5 秒没跑完");
    }
  }

  /**
   * 对账队列自检：造三条"上次崩了"的条目 → 抬起卡片 → 确认 / 改时间 / 丢弃各走一遍。
   * 动作走按键（press → scene 键盘处理器 → 仓储）而不是直接调仓储：
   * 否则证明不了"用户按 Y 真的能确认掉一条"。
   */
  private static void queueSelfTest(UiController ui, EntryRepository entries, Exporter exporter,
      DataSource ds, Clock clock) throws Exception {
    String[][] fixtures = {{"崩前在写", "50", "20"}, {"崩前在读", "40", "15"},
        {"崩前在跑", "30", "10"}};
    int reconciled;
    // 整段夹具 + 对账必须握在同一把锁里：心跳线程每秒把所有 running 行的
    // last_heartbeat_at 刷成当下，夹具刚把心跳挪回 20 分钟前就被它抹平 ⇒ decide() 判成
    // "还在跑"，reconciled 就会少报（00:40 那次实测只剩 1 条）。分开加锁等于让判据去赌时序
    synchronized (ds.lock()) {
      for (String[] f : fixtures) {
        // 每条自己的项目：不同 exclusivity_key 才允许相交，
        // 否则这三条夹具本身就成了"未放行的重叠"，会被契约验收判失败。
        Entry e = entries.start(f[0], EntryKind.COUNT_UP, entries.ensureProject("对账" + f[0]),
            false);
        OffsetDateTime now = OffsetDateTime.parse(clock.wall());
        String started = now.minusMinutes(Long.parseLong(f[1])).format(Clock.WIRE);
        // checkin_date 跟着 started_at 一起改：验收器按 started_at 重算归日，
        // 只在 08:00-08:59 之间跑才会露出来 —— 那就是一次没人能复现的假失败
        String checkin = desktimer.core.time.DayAttribution.checkinDate(started);
        try (PreparedStatement ps = ds.conn().prepareStatement(
            "UPDATE time_entry SET started_at=?, last_heartbeat_at=?, checkin_date=? WHERE id=?")) {
          ps.setString(1, started);
          ps.setString(2, now.minusMinutes(Long.parseLong(f[2])).format(Clock.WIRE));
          ps.setString(3, checkin);
          ps.setString(4, e.id());
          ps.executeUpdate();
        }
      }
      reconciled = entries.reconcileRunning(Recovery.Cause.CRASH).size();
    }
    System.out.println("SELFTEST_QUEUE reconciled=" + reconciled);
    if (reconciled != 3) {
      throw new IllegalStateException("SELFTEST_QUEUE_FAILED 三条都该进对账");
    }

    ui.capture().setAutoHideOnFocusLost(false);
    ui.wake();
    Thread.sleep(600);
    boolean cardUp = ui.card().showing() && !ui.capture().showing() && ui.queueSize() == 3;
    screenshot("target/selftest-card.png");

    // 跑着计时时卡片不许抢面板：提示条里带条数就够，多一个窗就多一次找不回来
    // 按 ← 而不是 N：替代键只用窗口内才解释的方向键（Insert/Delete/F2 在用户机器上不产生事件）
    ui.card().press(KeyCode.LEFT);
    Thread.sleep(400);
    boolean handedToCapture = ui.capture().showing() && !ui.card().showing();
    ui.capture().type("1m 队列自检 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
    // 这段测的是路由优先级，钉住与否会引入焦点抖动，先按死（setPinned 碰 Stage 属性，必须在 FX 线程）
    runOnFx(() -> ui.panel().setPinned(true));
    Thread.sleep(400);   // 提示条由 250ms 的 tick 重画，读早了会拿到上一帧
    String[] banner = new String[1];
    runOnFx(() -> banner[0] = ui.panel().stateLine());
    boolean bannerShowsQueue = banner[0].contains("待确认 3 条")
        && banner[0].contains("已钉住置顶");
    ui.wake();
    Thread.sleep(400);
    boolean panelWins = ui.panel().showing() && !ui.card().showing();
    // 这一段必须以 finished 收尾：后面待办自检拿"未分项目的在跑段"当重叠撞车的活样本，
    // 判成放弃（aborted）会被重叠判据排除，那条拒补录的判据就白测了（2026-10-10 就是这么红的）。
    clearMisfireGate();
    ui.finishActive();
    Thread.sleep(400);

    ui.wake();
    Thread.sleep(500);
    boolean cardAgain = ui.card().showing() && ui.queueSize() == 3;
    ui.card().press(KeyCode.ENTER);
    Thread.sleep(500);
    boolean confirmed = ui.queueSize() == 2
        && statusOf(ds, "崩前在写") == EntryStatus.FINISHED;

    String typed = OffsetDateTime.parse(clock.wall())
        .format(DateTimeFormatter.ofPattern("HH:mm"));
    ui.card().press(KeyCode.T);
    Thread.sleep(300);
    ui.card().typeEdit(typed);
    ui.card().press(KeyCode.ENTER);
    Thread.sleep(500);
    boolean rescheduled = ui.queueSize() == 1 && typed.equals(endedAtOf(ds, "崩前在读"));

    ui.card().press(KeyCode.X);
    Thread.sleep(500);
    boolean discarded = ui.queueSize() == 0 && !ui.card().showing()
        && statusOf(ds, "崩前在跑") == EntryStatus.ABORTED;
    ui.capture().setAutoHideOnFocusLost(true);
    System.out.println("SELFTEST_QUEUE card_up=" + cardUp + " n_to_capture=" + handedToCapture
        + " banner=\"" + banner[0] + "\" panel_wins=" + panelWins + " card_again=" + cardAgain
        + " confirm=" + confirmed + " edit_to=" + typed + " edit=" + rescheduled
        + " discard=" + discarded);
    if (!cardUp || !handedToCapture || !bannerShowsQueue || !panelWins || !cardAgain
        || !confirmed || !rescheduled || !discarded) {
      throw new IllegalStateException("SELFTEST_QUEUE_FAILED 对账队列有一条路走不通");
    }
    export(exporter);
    System.out.println("SELFTEST_QUEUE_OK");
  }

  /**
   * 随手记自检：走捕获窗那条路（输入 `! …` 再回车）。断言三件事 ——
   * 记笔记**不产生计时条目**（最容易做错的地方：回车在计时模式下是"开始"）、
   * 挂接跟着"当前有没有在跑"变、全角 `！＃＠` 与半角同义。
   */
  private static void noteSelfTest(UiController ui, NoteRepository notes, Exporter exporter)
      throws Exception {
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("! 没有计时的时候记一笔 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(500);
    boolean noSessionStarted = ui.activeEntryId() == null;
    List<Note> afterFirst = notes.all();
    boolean unlinked = !afterFirst.isEmpty()
        && afterFirst.get(afterFirst.size() - 1).entryId() == null;

    ui.capture().show();
    Thread.sleep(200);
    ui.capture().type("1m 挂接自检 @笔记项目");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
    String running = ui.activeEntryId();
    ui.panel().press(KeyCode.RIGHT);
    Thread.sleep(400);
    boolean captureOpensWhileRunning = ui.capture().showing();
    ui.capture().type("！跑着的时候记一笔 ＃灵感 ＠笔记项目");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(500);
    List<Note> all = notes.all();
    Note last = all.get(all.size() - 1);
    boolean linked = running != null && running.equals(last.entryId());
    // 项目名走 join，库里只有 id；名字是否真的导出去看下面的 jsonl
    boolean fullWidthWorks = List.of("灵感").equals(last.tags()) && last.projectId() != null;
    screenshot("target/selftest-note.png");
    clearMisfireGate();   // 这段是"挂着一笔的计时正常结束"，不是手滑：让它以 finished 留在账上
    ui.finishActive();
    Thread.sleep(300);

    Exporter.Result r = exporter.exportAll();
    String jsonl = Files.readString(r.dir().resolve("notes.jsonl"), StandardCharsets.UTF_8);
    String md = Files.readString(
        r.dir().resolve("daily-" + java.time.LocalDate.now().toString().substring(0, 7) + ".md"),
        StandardCharsets.UTF_8);
    boolean exported = jsonl.contains("没有计时的时候记一笔") && jsonl.contains("跑着的时候记一笔")
        && jsonl.contains("\"project\":\"笔记项目\"") && md.contains("跑着的时候记一笔");
    System.out.println("SELFTEST_NOTE no_session=" + noSessionStarted + " unlinked=" + unlinked
        + " panel_opens_capture=" + captureOpensWhileRunning + " linked=" + linked
        + " fullwidth=" + fullWidthWorks + " exported=" + exported + " total=" + all.size());
    ui.capture().setAutoHideOnFocusLost(true);
    if (!noSessionStarted || !unlinked || !captureOpensWhileRunning || !linked
        || !fullWidthWorks || !exported) {
      throw new IllegalStateException("SELFTEST_NOTE_FAILED 随手记有一条路走不通");
    }
    System.out.println("SELFTEST_NOTE_OK");
  }

  private static EntryStatus statusOf(DataSource ds, String task) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT status FROM time_entry WHERE task=?")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? EntryStatus.parse(rs.getString(1)) : null;
        }
      }
    }
  }

  private static String endedAtOf(DataSource ds, String task) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT ended_at FROM time_entry WHERE task=?")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          String iso = rs.next() ? rs.getString(1) : null;
          return iso == null ? null : iso.substring(11, 16);
        }
      }
    }
  }

  /**
   * 一天一份：`backup.last_day` 与今天不同才产 zip，否则每次开合盖都要留一份了。
   * 备份失败不打扰用户（只记日志），但托盘菜单里"立即备份"能手动补一次。
   */
  private static void backup(Backuper backuper, SettingRepository settings, Clock clock,
      String reason) {
    try {
      String today = clock.wall().substring(0, 10);
      String last = settings.get("backup.last_day", "");
      if ("manual".equals(reason) || !today.equals(last)) {
        Backuper.Result r = backuper.backup(reason);
        settings.put("backup.last_day", today);
        settings.put("backup.last_zip", r.zip().getFileName().toString());
      } else {
        System.out.println("BACKUP_SKIPPED 今日已有备份 " + today);
      }
    } catch (Exception e) {
      System.out.println("BACKUP_FAILED " + e.getMessage());
    }
  }

  /**
   * 备份自检：把 zip 里的 timer.db 解出来、当库打开数行数，和主库当场比对。
   * 只断言"文件生成了"没有意义 —— 能读出行数才证明可恢复。
   */
  private static void backupSelfTest(Backuper backuper, DataSource ds) throws Exception {
    int live = countEntries(ds);
    Backuper.Result r = backuper.backup("selftest");
    java.nio.file.Path out = r.zip().resolveSibling("selftest-extracted.db");
    try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(
        java.nio.file.Files.newInputStream(r.zip()))) {
      java.util.zip.ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        if ("timer.db".equals(e.getName())) {
          java.nio.file.Files.copy(z, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    int inZip = countFrom(out);
    java.nio.file.Files.deleteIfExists(out);
    System.out.println("SELFTEST_BACKUP zip=" + r.zip().getFileName() + " live=" + live
        + " in_zip=" + inZip + " reported=" + r.rows() + " sha256_12=" + r.sha256_12()
        + " kept=" + r.kept() + " deleted=" + r.deleted());
    if (live <= 0 || inZip != live || r.rows() != live
        || !java.nio.file.Files.exists(r.zip()) || r.kept() < 1) {
      throw new IllegalStateException("SELFTEST_BACKUP_FAILED 备份不可恢复或行数对不上");
    }
  }

  /**
   * 设置窗自检：开窗 → 裸键与只按修饰键都被拒（旧键不动）→ 按一个新组合 → 真的用新组合
   * 全局唤起一次 → 换回原键再唤起一次 → Esc 收窗。
   *
   * 中间两步才是这门功能的关键：证明"换键"当场换了那个全局监听，而不只是往库里写了个字符串。
   * 只验前面几步会漏掉最坏的一种坏状态 —— 界面显示改好了，按下去没反应。
   *
   * 换键走 pressSpec（合成事件进 scene 处理器），唤起走 fireGlobal（Robot 真的敲键盘进钩子）：
   * 两条路分开是故意的，前者证明处理器，后者证明投递。
   */
  private static void settingsSelfTest(SettingsWindow win, SettingRepository settings,
      AtomicReference<KeySpec> live, AtomicInteger hits, UiController ui) throws Exception {
    KeySpec original = live.get();
    win.show();
    Thread.sleep(400);
    boolean shown = win.showing();

    // 裸键在这个窗里归"绑动作键"（M1-d2），所以判据是"唤起键一点没动"而不是"被拒"
    win.select(0);
    win.press(KeyCode.G, false, false, false, false);
    Thread.sleep(250);
    boolean bareKeyBindsAction = java.util.Objects.equals(original, live.get())
        && win.messageLine().contains("已绑到");
    win.press(KeyCode.CONTROL, true, false, false, false);
    Thread.sleep(250);
    boolean modifierRefused = java.util.Objects.equals(original, live.get())
        && win.messageLine().contains("按住 Ctrl 或 Alt");
    win.press(KeyCode.R, true, false, false, false);   // 刚绑的 G 撤掉，别影响后面的判据
    Thread.sleep(250);
    // 托盘那三项在 JavaFX 里各有一个对应物（原生菜单的中文在他机器上画不出来，实测豆腐块）：
    // Ctrl+B 必须真的产出一个新 zip，不然"菜单看不见"就等于"功能没了"
    java.nio.file.Path backupDir = TimerHome.resolveAndCreate().backupDir();
    int zipsBefore = countZips(backupDir);
    // 用 Robot 真注入（走系统的输入投递），不用 win.press(...) 的合成事件：
    // 合成事件只证明处理器认这个键，证明不了按键真的到达这扇窗 —— 用户反馈"按了没反应"
    // 恰恰是这两者的差别，focused 一起打出来才能分清是没送到还是送到了没动作
    boolean winFocused = win.focused();
    inject(KeySpec.parse("ctrl+b"));
    Thread.sleep(1800);
    boolean backupFromJavaFx = countZips(backupDir) == zipsBefore + 1
        && win.messageLine().contains("已备份");
    screenshotRegion("target/selftest-settings.png", win.screenRect());

    KeySpec next = KeySpec.parse("ctrl+alt+shift+t");
    pressSpec(win, next);
    Thread.sleep(400);
    boolean rebound = next.equals(live.get())
        && next.canonical().equals(settings.get(WAKE_KEY, ""));
    boolean wokeByNew = fireGlobal(next, hits);

    // 启动时一个键都没装上（被别的程序占了）就没有"旧键"可还原。这时这两项写成 skipped，
    // 绝不借 true 顶替 —— 包验收只认日志里的标签，把跳过打成通过正好骗过唯一那道闸
    boolean restored;
    boolean wokeByOld;
    String restoredTag;
    String wokeByOldTag;
    if (original != null) {
      pressSpec(win, original);
      Thread.sleep(400);
      restored = original.equals(live.get())
          && original.canonical().equals(settings.get(WAKE_KEY, ""));
      wokeByOld = fireGlobal(original, hits);
      restoredTag = String.valueOf(restored);
      wokeByOldTag = String.valueOf(wokeByOld);
    } else {
      restored = true;
      wokeByOld = true;
      restoredTag = "skipped_no_prior_key";
      wokeByOldTag = "skipped_no_prior_key";
    }

    win.press(KeyCode.ESCAPE, false, false, false, false);
    Thread.sleep(300);
    ui.capture().hide();
    boolean escCloses = !win.showing();
    System.out.println("SELFTEST_SETTINGS shown=" + shown + " bare_key_binds_action="
        + bareKeyBindsAction + " win_focused=" + winFocused + " backup_from_javafx=" + backupFromJavaFx
        + " modifier_refused=" + modifierRefused + " startup_spec="
        + (original == null ? "none" : original.canonical()) + " rebound=" + rebound
        + " woke_by_new=" + wokeByNew + " restored=" + restoredTag + " woke_by_old="
        + wokeByOldTag + " esc_closes=" + escCloses + " saved=" + settings.get(WAKE_KEY, ""));
    if (!shown || !bareKeyBindsAction || !backupFromJavaFx || !modifierRefused || !rebound || !wokeByNew || !restored
        || !wokeByOld || !escCloses) {
      throw new IllegalStateException("SELFTEST_SETTINGS_FAILED 改键有一条路走不通");
    }
    System.out.println("SELFTEST_SETTINGS_OK");
  }

  /** 备份目录里有几个 zip：Ctrl+B 那条路只能靠"多出一个文件"来证明它真跑了。 */
  private static int countZips(java.nio.file.Path dir) {
    if (!java.nio.file.Files.isDirectory(dir)) {
      return 0;
    }
    try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
      return (int) s.filter(p -> p.getFileName().toString().startsWith("timer-backup-")
          && p.getFileName().toString().endsWith(".zip")).count();
    } catch (java.io.IOException e) {
      System.out.println("COUNT_ZIPS_FAILED " + e.getMessage());
      return -1;
    }
  }

  /** 自检用：按 KeySpec 原样在设置窗里重放一次按键（进 scene 处理器，不进系统钩子）。 */
  private static void pressSpec(SettingsWindow win, KeySpec spec) {
    win.press(fxOf(spec.keyName()), spec.ctrl(), spec.alt(), spec.shift(), spec.win());
  }

  /** 自检用：把 KeySpec 的键名换回 JavaFX 的 KeyCode。键名一定是可绑的那批，所以映射是满的。 */
  private static KeyCode fxOf(String keyName) {
    char ch = keyName.charAt(0);
    if (keyName.length() == 1 && ch >= 'a' && ch <= 'z') {
      return KeyCode.valueOf(keyName.toUpperCase(java.util.Locale.ROOT));
    }
    if (keyName.length() == 1 && ch >= '0' && ch <= '9') {
      return KeyCode.valueOf("DIGIT" + keyName);
    }
    return switch (keyName) {
      case "space" -> KeyCode.SPACE;
      case "enter" -> KeyCode.ENTER;
      case "tab" -> KeyCode.TAB;
      case "escape" -> KeyCode.ESCAPE;
      default -> KeyCode.valueOf(keyName.toUpperCase(java.util.Locale.ROOT));   // f1..f12
    };
  }

  /**
   * 自检用：真的敲一次键盘（Robot 注入），等全局钩子把这一记送回来。
   * 返回"收到了没有" —— 这是唯一能证明"换键之后新键真的通"的手段。
   */
  private static boolean fireGlobal(KeySpec spec, AtomicInteger hits) throws Exception {
    int baseline = hits.get();
    Thread sender = new Thread(() -> {
      try {
        inject(spec);
      } catch (Exception e) {
        System.out.println("HOTKEY_INJECT_FAILED " + e.getMessage());
      }
    }, "hotkey-fire");
    sender.setDaemon(true);
    sender.start();
    long deadline = System.currentTimeMillis() + 4000;
    while (hits.get() == baseline && System.currentTimeMillis() < deadline) {
      Thread.sleep(80);
    }
    return hits.get() > baseline;
  }

  /**
   * 待办自检：走捕获窗那条路（输入 `+ …` 再回车），再把卡片的四个动作各按一遍。
   * 断言的四件事都是"容易被做错"的地方 ——
   * 记待办**不产生计时条目**；补录落的是 origin=manual_backfill 的 finished 行且真的挂上；
   * 只标完成不造条目；丢弃删待办但不删计时条目；以及按归属日抑制后不再自动抬卡。
   * 动作一律走按键（press → scene 处理器 → 仓储），否则证明不了"用户按 Enter 真的能完成"。
   */
  private static void todoSelfTest(UiController ui, TodoRepository todos, Exporter exporter,
      SettingRepository settings, DataSource ds, Clock clock) throws Exception {
    int entriesBefore = countEntries(ds);
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("+ 补录自检的待办 ＃自检 ＠待办项目");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(500);
    boolean noSession = ui.activeEntryId() == null;
    List<Todo> open = todos.pending();
    Todo a = open.isEmpty() ? null : open.get(open.size() - 1);
    boolean added = a != null && List.of("自检").equals(a.tags()) && a.projectId() != null;
    if (a == null) {
      throw new IllegalStateException("SELFTEST_TODO_FAILED 捕获窗的 `+` 行没记上待办");
    }

    // 再造两条：只标完成的、要丢弃的。清单排序是 (created_at, id)，同一秒内由 id 兜底 ——
    // id 是随机 UUID，所以这里必须显式打时间戳，否则"索引 0 是哪一条"根本不可预测
    String day = clock.wall().substring(0, 10);
    stampTodo(ds, a.id(), day + "T09:00:00+08:00");
    Todo b = todos.add("只标完成的待办", null, List.of());
    stampTodo(ds, b.id(), day + "T09:00:01+08:00");
    Todo c = todos.add("要丢弃的待办", null, List.of());
    stampTodo(ds, c.id(), day + "T09:00:02+08:00");
    ui.wake();
    Thread.sleep(600);
    boolean cardUp = ui.todoCard().showing() && !ui.capture().showing() && ui.todoCount() == 3;
    screenshot("target/selftest-todo.png");

    // 空格补录 25 分钟：先落条目，再把待办标完成（此时索引 0 必须是 a）
    ui.todoCard().press(KeyCode.SPACE);
    Thread.sleep(300);
    ui.todoCard().typeEdit("25m");
    ui.todoCard().press(KeyCode.ENTER);
    Thread.sleep(700);
    String backfillId = lastBackfillId(ds);
    Todo aAfter = todos.find(a.id()).orElse(null);
    boolean backfilled = backfillId != null && aAfter != null && aAfter.done()
        && aAfter.entryIds().contains(backfillId)
        && durationOf(ds, backfillId) == 25 * 60 * 1000L;

    // 索引顺势落到 b：Enter 只改状态，一条计时都不许多出来
    ui.todoCard().press(KeyCode.ENTER);
    Thread.sleep(500);
    boolean doneWithoutEntry = countEntries(ds) == entriesBefore + 1 && countBackfill(ds) == 1
        && todos.find(b.id()).map(Todo::done).orElse(false)
        && todos.find(c.id()).map(t -> !t.done()).orElse(false);

    // 再按 X 才是 c：丢弃只删待办自己，补录出来的那条计时必须还在
    ui.todoCard().press(KeyCode.X);
    Thread.sleep(500);
    boolean discarded = ui.todoCount() == 0 && !ui.todoCard().showing()
        && todos.find(c.id()).isEmpty() && lastBackfillId(ds) != null
        && countEntries(ds) == entriesBefore + 1;

    // 被拒的补录必须写在卡片上，而且待办要留在清单里：无项目的待办 key=unassigned，
    // 补 25 分钟会撞到自检前几段自己留下的"未分项目"计时 —— 正好是那条判据的活样本
    String first = todos.add("抑制测试用的待办", null, List.of()).id();
    ui.wake();
    Thread.sleep(500);
    boolean raisedAgain = ui.todoCard().showing();
    ui.todoCard().press(KeyCode.SPACE);
    Thread.sleep(300);
    ui.todoCard().typeEdit("25m");
    ui.todoCard().press(KeyCode.ENTER);
    Thread.sleep(600);
    String refuseLine = ui.todoCard().hintLine();
    boolean refusedVisibly = refuseLine.contains("重叠") && todos.find(first)
        .map(t -> !t.done()).orElse(false) && countBackfill(ds) == 1;
    // Esc"稍后"：按归属日抑制，今天不再自动抬这张卡
    ui.todoCard().press(KeyCode.ESCAPE);
    Thread.sleep(300);
    ui.wake();
    Thread.sleep(500);
    boolean suppressed = !ui.todoCard().showing() && ui.capture().showing();
    String savedSuppress = settings.get("todo.card.suppress_until", "");
    settings.put("todo.card.suppress_until", "");
    todos.remove(first);
    ui.capture().setAutoHideOnFocusLost(true);
    ui.capture().hide();

    Exporter.Result r = exporter.exportAll();
    String jsonl = Files.readString(r.dir().resolve("todos.jsonl"), StandardCharsets.UTF_8);
    String md = Files.readString(
        r.dir().resolve("daily-" + clock.wall().substring(0, 7) + ".md"),
        StandardCharsets.UTF_8);
    boolean exported = jsonl.contains("补录自检的待办") && jsonl.contains("\"done\":1")
        && jsonl.contains("\"linked_entry_ids\":[\"" + backfillId + "\"]")
        && md.contains("＋ 补录自检的待办");
    System.out.println("SELFTEST_TODO no_session=" + noSession + " added=" + added
        + " card_up=" + cardUp + " backfilled=" + backfilled
        + " done_without_entry=" + doneWithoutEntry + " discarded=" + discarded
        + " raised_again=" + raisedAgain + " refused_visibly=" + refusedVisibly
        + " refuse=\"" + refuseLine + "\" suppressed=" + suppressed
        + " suppress_saved=" + savedSuppress + " exported=" + exported);
    if (!noSession || !added || !cardUp || !backfilled || !doneWithoutEntry || !discarded
        || !raisedAgain || !refusedVisibly || !suppressed || savedSuppress.isEmpty() || !exported) {
      throw new IllegalStateException("SELFTEST_TODO_FAILED 待办有一条路走不通");
    }
    System.out.println("SELFTEST_TODO_OK");
  }

  /** 补录条目的 id：断言"真的落了 manual_backfill 那一行"，而不是只看行数加一。 */
  private static String lastBackfillId(DataSource ds) throws SQLException {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement();
           java.sql.ResultSet rs = s.executeQuery(
               "SELECT id FROM time_entry WHERE origin='manual_backfill'"
                   + " ORDER BY created_at DESC, id LIMIT 1")) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static long durationOf(DataSource ds, String id) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT duration_ms FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getLong(1) : -1L;
        }
      }
    }
  }

  /**
   * 自检用：显式打 created_at，让清单顺序可预测（同一秒内靠随机 id 兜底是不成立的假设）。
   * 连 checkin_date 一起改：那一列是插入那一刻算的，只改 created_at 会留下
   * "created_at 在 09:00 而归日是前一天"的自相矛盾夹具 —— 验收器正是按 created_at 重算的。
   */
  private static void stampTodo(DataSource ds, String id, String createdAt) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "UPDATE todo SET created_at=?, checkin_date=? WHERE id=?")) {
        ps.setString(1, createdAt);
        ps.setString(2, desktimer.core.time.DayAttribution.checkinDate(createdAt));
        ps.setString(3, id);
        ps.executeUpdate();
      }
    }
  }

  private static int countBackfill(DataSource ds) throws SQLException {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement();
           java.sql.ResultSet rs = s.executeQuery(
               "SELECT COUNT(*) FROM time_entry WHERE origin='manual_backfill'")) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  /**
   * 改键自检（M1-d2，范围 2）：绑上去要真的生效、旧键要真的失效、提示条要跟着改、
   * 撞键要拒且不动原绑定、恢复出厂要干净，最后新面板实例读回同一份库 —— 六件事各证一条。
   *
   * 全部动作都走按键（press → scene 处理器 → KeyMap → 仓储），不直接调仓储：
   * 直接调只能证明"表里写了 F7"，证明不了"按 F7 真的结束了计时"。
   */
  private static void keymapSelfTest(UiController ui, SettingsWindow win,
      SettingRepository settings, DataSource ds) throws Exception {
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("1m 键位自检一 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(700);
    win.show();
    Thread.sleep(400);

    win.select(1);                       // actions 顺序：pause, finish, … ⇒ 1 是"结束"
    win.press(KeyCode.F7, false, false, false, false);
    Thread.sleep(400);
    boolean bound = ui.panel().keys().primary("finish") == KeyCode.F7
        && "finish=F7".equals(settings.get("key.panel", ""));
    String hint = ui.panel().hintLine();
    // 改键后主键位换成 F7，备选键仍然带着（"F7/Enter 结束"）。
    // "E 不再出现在主键位"才是"改键不是多一个键"的判据，所以负向断言查的是 "E/Enter" 这串。
    boolean hintFollows = hint.contains("F7/Enter 结束") && !hint.contains("E/Enter 结束");
    // 另起一个面板实例：证明"存进库"和"下次启动读回来"是同一条路
    TimerPanel[] reload = new TimerPanel[1];
    runOnFx(() -> reload[0] = blankPanel(settings));
    boolean reloadsFromStore = reload[0].keys().primary("finish") == KeyCode.F7;

    clearMisfireGate();
    ui.panel().press(KeyCode.F7);
    Thread.sleep(600);
    boolean f7Ended = statusOf(ds, "键位自检一") == EntryStatus.FINISHED;

    // 旧字母键必须失效：这是"改键"而不是"多一个键"的判据
    ui.capture().show();
    Thread.sleep(200);
    ui.capture().type("1m 键位自检二 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(700);
    ui.panel().press(KeyCode.E);
    Thread.sleep(400);
    boolean oldKeyDead = statusOf(ds, "键位自检二") == EntryStatus.RUNNING;
    clearMisfireGate();
    ui.panel().press(KeyCode.F7);
    Thread.sleep(500);
    boolean f7EndedAgain = statusOf(ds, "键位自检二") == EntryStatus.FINISHED;

    // 撞键要拒并说清和谁撞，且不动原绑定（note 仍是 N）
    win.select(5);
    win.press(KeyCode.F7, false, false, false, false);
    Thread.sleep(400);
    String clash = win.messageLine();
    boolean clashRefused = clash.contains("结束") && ui.panel().keys().primary("note") == KeyCode.N;
    screenshotRegion("target/selftest-keymap.png", win.screenRect());

    // Ctrl+R 全部恢复出厂：表空、库空、E 又能结束
    win.press(KeyCode.R, true, false, false, false);
    Thread.sleep(400);
    ui.capture().show();
    Thread.sleep(200);
    ui.capture().type("1m 键位自检三 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(700);
    clearMisfireGate();
    ui.panel().press(KeyCode.E);
    Thread.sleep(500);
    boolean resetWorks = "".equals(settings.get("key.panel", ""))
        && ui.panel().keys().primary("finish") == KeyCode.E
        && ui.panel().hintLine().contains("E/Enter 结束")
        && statusOf(ds, "键位自检三") == EntryStatus.FINISHED;
    ui.capture().setAutoHideOnFocusLost(true);
    System.out.println("SELFTEST_KEYMAP bound=" + bound + " hint_follows=" + hintFollows
        + " reload_from_store=" + reloadsFromStore + " f7_ended=" + f7Ended
        + " old_key_dead=" + oldKeyDead + " f7_ended_again=" + f7EndedAgain
        + " clash_refused=" + clashRefused + " clash=\"" + clash + "\" reset_works=" + resetWorks);
    if (!bound || !hintFollows || !reloadsFromStore || !f7Ended || !oldKeyDead
        || !f7EndedAgain || !clashRefused || !resetWorks) {
      throw new IllegalStateException("SELFTEST_KEYMAP_FAILED 改键有一条路没走通");
    }
    System.out.println("SELFTEST_KEYMAP_OK");
  }

  /**
   * inbox 自检：外部写一行 → 收进库 → 导出里读得到；同一条内容再写一次必须被台账挡掉；
   * 认不了的行落到 rejected-*。三条各挡一种坏情况：静默吞数据、重放多算一段、猜错语义。
   */
  private static void inboxSelfTest(InboxAbsorber inbox, TimerHome home, Exporter exporter)
      throws Exception {
    String line = "12m 命令行记的一段 #命令行 @命令行项目";
    Files.writeString(home.inboxFile(), line + System.lineSeparator());
    InboxAbsorber.Result first = inbox.absorbOnce();
    boolean absorbed = first.absorbed() == 1 && first.rejected() == 0;

    Files.writeString(home.inboxFile(), line + System.lineSeparator());
    InboxAbsorber.Result replay = inbox.absorbOnce();
    boolean deduped = replay.absorbed() == 0 && replay.skipped() == 1;

    Files.writeString(home.inboxFile(), "这行没有时长" + System.lineSeparator());
    InboxAbsorber.Result bad = inbox.absorbOnce();
    boolean rejectedFile;
    try (java.util.stream.Stream<java.nio.file.Path> s = Files.list(home.inboxDir())) {
      rejectedFile = s.anyMatch(p -> p.getFileName().toString().startsWith("rejected-"));
    }
    boolean rejected = bad.rejected() == 1 && rejectedFile;

    Exporter.Result ex = exporter.exportAll();
    String jsonl = Files.readString(ex.dir().resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    boolean exported = jsonl.contains("命令行记的一段")
        && jsonl.contains("\"origin\":\"manual_backfill\"")
        && jsonl.contains("\"project\":\"命令行项目\"")
        && jsonl.contains("\"tags\":[\"命令行\"]");
    System.out.println("SELFTEST_INBOX absorbed=" + absorbed + " deduped=" + deduped
        + " rejected=" + rejected + " exported=" + exported);
    if (!absorbed || !deduped || !rejected || !exported) {
      throw new IllegalStateException("SELFTEST_INBOX_FAILED inbox 吸收有一条路不对");
    }
    System.out.println("SELFTEST_INBOX_OK");
  }

  /**
   * 位置可信性自检（T-04b 的代码化判据）：库里存着"屏外坐标"时，窗口**不许**回到屏外。
   *
   * 为什么要在真机上跑这一段：钳制判据用的是 JavaFX 的 Screen 可视区，
   * 而拔屏/切"仅电脑屏幕"之后的屏幕集合只有真实环境才知道 —— 单测里那是假的矩形。
   * 三种情形分别钉住：
   *  ① 完全在屏外（副屏没了的旧坐标）⇒ 回默认落点；
   *  ② 只露一角（被推出去大半）⇒ 也不算可信，回默认落点；
   *  ③ 只出去 20px ⇒ 那是用户自己推的，原样保留（判据不能反过头来管用户）。
   */
  private static void placementSelfTest(SettingRepository settings) throws Exception {
    String saved = settings.get("ui.panel.bounds", "");
    javafx.geometry.Rectangle2D primary = javafx.stage.Screen.getPrimary().getVisualBounds();
    double w = 360;
    double h = 168;
    double[] got = new double[2];

    settings.put("ui.panel.bounds",
        (primary.getMaxX() + 4000) + "," + (primary.getMaxY() + 4000));
    runOnFx(() -> place(got, settings));
    boolean offscreenReturns = near(got[0], 120) && near(got[1], 240);
    boolean defaultLandsInside =
        new desktimer.ui.WinPos(got[0], got[1]).visibleOnAny(screens(), w, h, 60);

    settings.put("ui.panel.bounds", (primary.getMaxX() - 150) + ",200");
    runOnFx(() -> place(got, settings));
    boolean cornerOnlyPulledBack = near(got[0], 120) && near(got[1], 240);

    settings.put("ui.panel.bounds", (primary.getMaxX() - 320) + ",200");
    runOnFx(() -> place(got, settings));
    boolean nudgedOutKept = near(got[0], primary.getMaxX() - 320) && near(got[1], 200);

    settings.put("ui.panel.bounds", saved);
    System.out.println("SELFTEST_PLACE screens=" + screens().size()
        + " primary_max_x=" + Math.round(primary.getMaxX())
        + " offscreen_returns=" + offscreenReturns + " default_inside=" + defaultLandsInside
        + " corner_only_pulled_back=" + cornerOnlyPulledBack + " nudged_out_kept=" + nudgedOutKept
        + " landed=" + Math.round(got[0]) + "," + Math.round(got[1]));
    if (!offscreenReturns || !defaultLandsInside || !cornerOnlyPulledBack || !nudgedOutKept) {
      throw new IllegalStateException("SELFTEST_PLACE_FAILED 屏外位置没有被拉回可信落点");
    }
    System.out.println("SELFTEST_PLACE_OK");
  }

  /**
   * 只为"从库里读回键位/位置"而造的面板：动作回调全空，自检不许靠它执行任何事。
   * 三处自检（UI 位置、键位读回、PLACE 钳制）都要一个空白面板，构造参数别再抄三遍 ——
   * M2-b 给面板加了 prev/next/rest 三个动作，抄三遍就意味着漏一个才知道错了。
   */
  private static TimerPanel blankPanel(SettingRepository settings) {
    Runnable noop = () -> {
    };
    return new TimerPanel(noop, noop, noop, noop, noop, noop, noop, noop, noop, settings,
        UiTheme.DARK);
  }

  /** 造一个面板只为走 WindowPlacement.load 那条读回路径；面板本体不显示。 */
  private static void place(double[] out, SettingRepository settings) {
    TimerPanel p = blankPanel(settings);
    out[0] = p.x();
    out[1] = p.y();
  }

  private static List<javafx.geometry.Rectangle2D> screens() {
    List<javafx.geometry.Rectangle2D> out = new ArrayList<>();
    javafx.stage.Screen.getScreens().forEach(s -> out.add(s.getVisualBounds()));
    return out;
  }

  /**
   * 全屏/勿扰自检（M1-f）。两条都要真跑：
   *  ① 探测器造一个盖满主屏的无边框窗，必须报"全屏"；收掉后必须不报 —— 只测"当前不是全屏"
   *     是空跑，那条判据永远为真就等于没测（这台机器上最大化窗口比屏幕还大一圈，
   *     误判成全屏是可发生的错法，见 FullScreenJudgeTest 的实测数字）。
   *  ② 勿扰开着 + 面板收起 + 到点 ⇒ 不响铃、不抢焦点，但必须如实记一条 missed 进对账队列。
   *     "静默"与"吞掉"的差别就是这一条：不提示可以，不记账不行。
   */
  /**
   * 开机自启的自检：读写删全在临时键上跑，正式的 CurrentVersion\\Run 一个字都不写
   * （自启开不开只有他自己能决定），所以这条判据由平台层自己出，App 只负责打印。
   */
  private static void autostartSelfTest() {
    System.out.println("SELFTEST_AUTOSTART " + RunKeyAutostart.selfTest());
    System.out.println("SELFTEST_AUTOSTART_OK");
  }

  private static void dndSelfTest(UiController ui, SettingRepository settings,
      FullScreenDetector detector, EntryRepository entries, Clock clock) throws Exception {
    settings.put("dnd.until", "");
    // ① 全屏下到期：先起一段、把面板收起，再盖一个无边框全屏窗当"前台是视频/演示"
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("1s 全屏到期自检 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(400);
    ui.panel().hide();

    javafx.geometry.Rectangle2D b = javafx.stage.Screen.getPrimary().getBounds();
    long beforeHwnd = detector.foregroundHandle();
    javafx.stage.Stage[] cover = new javafx.stage.Stage[1];
    runOnFx(() -> {
      javafx.stage.Stage s = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT);
      s.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane(), b.getWidth(),
          b.getHeight()));
      s.setX(b.getMinX());
      s.setY(b.getMinY());
      s.show();
      s.toFront();
      s.requestFocus();
      cover[0] = s;
    });
    boolean detected = false;
    long deadline = System.currentTimeMillis() + 3000;
    while (!detected && System.currentTimeMillis() < deadline) {
      detected = detector.probe().fullscreen();
      if (!detected) {
        Thread.sleep(150);
      }
    }
    // 全屏的是**我盖上去的那扇**吗：句柄变了才算。以前只断言"屏幕被盖满"，
    // 用户自己开着全屏视频时，这段既可能假绿（把他那扇当成自检的覆盖窗），也可能假红。
    long mineHwnd = detector.foregroundHandle();
    boolean coverIsOurs = detected && mineHwnd != 0L && mineHwnd != beforeHwnd;
    String detail = detector.probe().detail();
    Thread.sleep(2200);
    boolean missedUnderFullscreen = entries.reviewQueue().stream()
        .anyMatch(r -> "全屏到期自检".equals(r.task()) && r.missed());
    boolean didNotStealFocus = !ui.panel().showing();
    runOnFx(() -> cover[0].close());
    Thread.sleep(400);
    // "释放"问的是我那扇窗还在不在前台，不是"整个桌面有没有全屏窗"
    boolean released = detector.foregroundHandle() != mineHwnd;
    ui.finishActive();
    acknowledgeAll(entries, "全屏到期自检");

    // ② 勿扰开着 + 面板收起：到点必须静默，但要留下 missed 记账
    settings.put("dnd.until", java.time.OffsetDateTime.parse(clock.wall())
        .plusHours(1).format(Clock.WIRE));
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("1s 勿扰到期自检 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(400);
    ui.panel().hide();
    Thread.sleep(2500);
    boolean missedQuietly = entries.reviewQueue().stream()
        .anyMatch(r -> "勿扰到期自检".equals(r.task()) && r.missed());
    String banner = ui.panel().stateLine();
    // 面板上的 M：开 → 关 → 开，验证它是可切换的而不是只有一档
    ui.panel().press(KeyCode.M);
    Thread.sleep(300);
    boolean toggledOff = settings.get("dnd.until", "").isEmpty();
    ui.panel().press(KeyCode.M);
    Thread.sleep(300);
    boolean toggledOn = !settings.get("dnd.until", "").isEmpty();
    ui.finishActive();
    acknowledgeAll(entries, "勿扰到期自检");
    settings.put("dnd.until", "");

    // ③ 没全屏、没勿扰、面板被自己收起：到点必须抬起来，而且不该记 missed
    //    （这就是"倒计时到点只响一声、什么都没弹"那条反馈的回归判据）
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("1s 抬窗自检 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(400);
    ui.panel().hide();
    Thread.sleep(2500);
    boolean panelUp = ui.panel().showing();
    boolean missedRow = entries.reviewQueue().stream()
        .anyMatch(r -> "抬窗自检".equals(r.task()) && r.missed());
    // 到点这一刻前台是不是全屏，取决于他手上在干什么，不是这条判据该管的事。
    // 所以两条分支各给一套期望，而不是赌"现在没人全屏"：
    //   非全屏 ⇒ 必须抬窗且不记 missed（PRD 的 L3，也就是"到点没提示"那条反馈的回归判据）
    //   全屏 ⇒ 必须不抬（不抢焦点）但**要记 missed**，否则"尊重场景"就变成"吞掉"
    boolean fullscreenAtExpiry = detector.probe().fullscreen();
    boolean raisedOnExpire = fullscreenAtExpiry ? (!panelUp && missedRow) : (panelUp && !missedRow);
    // 提示音走 winmm 的具名系统音；退化成 beep 只打印不判死（beep 也是有效到达）
    String soundErr = desktimer.platform.NotifySound.lastError();
    String soundRoute = desktimer.platform.NotifySound.lastRoute();
    ui.finishActive();
    ui.capture().setAutoHideOnFocusLost(true);
    System.out.println("SELFTEST_DND detected=" + detected + " cover_is_ours=" + coverIsOurs
        + " released=" + released
        + " missed_under_fullscreen=" + missedUnderFullscreen
        + " kept_focus=" + didNotStealFocus + " missed_quietly=" + missedQuietly
        + " toggled_off=" + toggledOff + " toggled_on=" + toggledOn
        + " raised_on_expire=" + raisedOnExpire
        + " fullscreen_at_expiry=" + fullscreenAtExpiry + " panel_up=" + panelUp
        + " missed_row=" + missedRow + " sound=\"" + soundRoute + "\""
        + " sound_err=\"" + soundErr + "\""
        + " banner=\"" + banner + "\" detail=" + detail);
    if (!detected || !coverIsOurs || !released || !missedUnderFullscreen
        || !didNotStealFocus || !missedQuietly
        || !toggledOff || !toggledOn || !raisedOnExpire) {
      throw new IllegalStateException("SELFTEST_DND_FAILED 全屏/勿扰有一条路不对");
    }
    System.out.println("SELFTEST_DND_OK");
  }

  /** 自检造的 missed 行要清干净，否则后面的唤起优先级会被它带偏。 */
  private static void acknowledgeAll(EntryRepository entries, String task) throws SQLException {
    for (desktimer.db.ReviewItem r : entries.reviewQueue()) {
      if (task.equals(r.task())) {
        entries.acknowledge(r.id());
      }
    }
  }

  /**
   * 番茄编排自检（M1-g）。逐条对应用户定的生命周期，不留模糊地带：
   *  ① `p 1m` ⇒ kind=pomodoro_work、round 在**开始时**就写、面板临时钉住且没污染库里的偏好；
   *  ② 正常结束 ⇒ 面板不收起、提示条写「空格 开始短休 5分」，且此刻库里**没有**休息条目；
   *  ③ 按空格 ⇒ 才出现 rest_short（target=5 分钟）；
   *  ④ 休息结束 ⇒ 面板收起回空闲、不再提示下一段工作，钉住状态恢复成库里那份；
   *  ⑤ 轮次基准：先完成 1 个（round=1），放弃 1 个，再开始 1 个 ⇒ 断言 round=2 而不是 3。
   */
  /**
   * 多计时并发（M2-b 的核心判据）：面板列全部、动作键只作用在**选中那一段**、
   * 第二个番茄被当面拒且拒因看得见、超过 6 段按折叠判据显示。
   *
   * 每段挂不同项目，避开 overlap 那条判据（它在 M1 就钉住了，这里重测会把失败原因搅浑）。
   */
  private static void parallelSelfTest(UiController ui, DataSource ds) throws Exception {
    ui.capture().setAutoHideOnFocusLost(false);
    captureLine(ui, "10m 并行甲 #自检 @并发甲");
    captureLine(ui, "10m 并行乙 #自检 @并发乙");
    boolean twoRunning = ui.activeCount() == 2;
    // 新起的那一段是选中项（判据与理由见 ActiveSessions.add：不抢才会误伤）
    boolean newestIsSelected = "并行乙".equals(ui.selectedTask());

    // 等过门限再按：甲从起就没停过，跨过门限后这一段测的才是"结束键只作用在选中行"，
    // 而不是"两段都被误触判据判成放弃"。
    clearMisfireGate();
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    boolean endsOnlySelected = "finished".equals(statusOfTask(ds, "并行乙"))
        && "running".equals(statusOfTask(ds, "并行甲")) && ui.activeCount() == 1;

    captureLine(ui, "10m 并行丙 #自检 @并发丙");
    String[] ids = ui.sessionIds().split(",");
    boolean selectedMoves = ui.selectSession(ids[0]) && "并行甲".equals(ui.selectedTask());
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    boolean actedOnTheMovedRow = "finished".equals(statusOfTask(ds, "并行甲"))
        && "running".equals(statusOfTask(ds, "并行丙"));

    // 第二个番茄必须起不来，且拒绝要看得见（不写库、不静默降级成普通倒计时）
    captureLine(ui, "p 10m 番茄唯一 #自检 @番茄");
    int beforeSecond = ui.activeCount();
    captureLine(ui, "p 10m 第二个番茄 #自检 @番茄");
    boolean secondRefused = ui.activeCount() == beforeSecond
        && statusOfTask(ds, "第二个番茄") == null
        && ui.panel().stateLine().contains("同时只跑一个");

    // 折叠判据：7 段时可见区仍是 6 格，其中 5 格是会话 + 1 格是"＋N 段"
    for (int i = 4; i <= 8; i++) {
      captureLine(ui, "10m 并行" + i + " #自检 @并发" + i);
    }
    java.util.List<String> rows = ui.panel().rosterLines();
    // 并行时"这段属于哪个项目"必须写在行上，否则两行同名任务分不出来。
    // 断言挑**一定在可见区里的那一行**（刚起的并行8 就是选中行）：拿被折叠走的甲去证，
    // 证的是折叠而不是行格式。
    boolean rowShowsProject = ui.panel().rosterLines().stream()
        .anyMatch(r -> r.contains("并行8") && r.contains("@并发8"));
    boolean folded = rows.size() == 6 && rows.get(5).contains("段未显示")
        && ui.activeCount() == 7;
    // 选中项落进折叠区时，窗口下滑把它包进来，折叠指示顺延
    String lastId = ui.sessionIds().split(",")[6];
    ui.selectSession(lastId);
    java.util.List<String> scrolled = ui.panel().rosterLines();
    boolean scrolledToShowSelection = scrolled.size() == 6
        && scrolled.stream().anyMatch(r -> r.contains("并行8"))
        && scrolled.get(5).contains("段未显示");

    // 收尾：把在跑的都放弃掉（X 不算番茄，也不留待开始休息）
    for (int guard = 0; guard < 20 && ui.hasActiveSession(); guard++) {
      ui.panel().press(KeyCode.X);
      Thread.sleep(250);
    }
    boolean drained = !ui.hasActiveSession() && ui.pendingRest().isEmpty();
    ui.capture().setAutoHideOnFocusLost(true);
    ui.panel().hide();

    System.out.println("SELFTEST_PARALLEL two_running=" + twoRunning + " newest_selected="
        + newestIsSelected + " ends_only_selected=" + endsOnlySelected + " selection_moves="
        + selectedMoves + " acts_on_moved_row=" + actedOnTheMovedRow
        + " row_shows_project=" + rowShowsProject + " second_pomodoro_refused="
        + secondRefused + " folded=" + folded + " scrolled=" + scrolledToShowSelection
        + " drained=" + drained);
    if (!twoRunning || !newestIsSelected || !endsOnlySelected || !selectedMoves
        || !actedOnTheMovedRow || !rowShowsProject || !secondRefused || !folded || !scrolledToShowSelection
        || !drained) {
      throw new IllegalStateException("SELFTEST_PARALLEL_FAILED 并发有一条判据不对");
    }
    System.out.println("SELFTEST_PARALLEL_OK");
  }

  /** 走捕获窗起一段：唤起、输入、回车，然后给 tick 一点时间。 */
  private static void captureLine(UiController ui, String line) throws Exception {
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type(line);
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
  }

  /**
   * M2-g 自检：并发之下，"没人认过的相交"不许静默进导出。
   *
   * 三层各证一段，缺一层都会重演 2026-10-08 那次真实契约违例：
   * ① 起段那一刻的放行（两段都在跑必须算冲突，放行写在**新起的那行**上）；
   * ② 收表那一刻的补查（旧版遗留的一对未放行行：标 overlap_pending、进对账队列、**暂不导出**，
   *    卡上 Enter＝两段都算、X＝这段作废）；
   * ③ 绕过应用直接写库时，BEFORE UPDATE 触发器必须响亮拒绝。
   */
  private static void overlapSelfTest(UiController ui, EntryRepository entries, Exporter exporter,
      DataSource ds, Clock clock) throws Exception {
    ui.capture().setAutoHideOnFocusLost(false);
    // ① 同桶并发：两行都不带 @项目 ⇒ 同一个 unassigned 桶
    captureLine(ui, "10m 重叠甲 #自检");
    captureLine(ui, "10m 重叠乙 #自检");
    boolean newerCarriesWaiver = flagOfTask(ds, "重叠乙", "overlap_waived") == 1
        && flagOfTask(ds, "重叠甲", "overlap_waived") == 0;
    boolean bannerWarns = ui.panel().stateLine().contains("重叠");
    clearMisfireGate();   // 两段都要以 finished 入账，aborted 会被重叠判据直接排除，这一段就白测了
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    java.util.Set<String> pair = exportsIds(exporter);
    boolean waivedPairExports = pair.contains(idOfTask(ds, "重叠甲"))
        && pair.contains(idOfTask(ds, "重叠乙"));

    // ② 遗留的一对：都没放行、都还在跑（正是旧版漏判留下的形状，只能手工摆出来）
    seedRunning(ds, "遗留甲", clock, 20, 0);
    seedRunning(ds, "遗留乙", clock, 10, 0);
    entries.finish(idOfTask(ds, "遗留甲"), 60_000L);
    Thread.sleep(600);
    boolean parkedForConfirmation = flagOfTask(ds, "遗留甲", "overlap_pending") == 1
        && entries.reviewQueue().stream()
            .anyMatch(r -> r.task().equals("遗留甲") && r.overlapPending());
    boolean withheldFromExport = !exportsIds(exporter).contains(idOfTask(ds, "遗留甲"));

    ui.wake();
    Thread.sleep(500);
    boolean cardShowsIt = ui.card().showing() && moveToCardRow(ui, "遗留甲");
    ui.card().press(KeyCode.ENTER);
    Thread.sleep(700);
    boolean waivedFromCard = flagOfTask(ds, "遗留甲", "overlap_waived") == 1
        && flagOfTask(ds, "遗留甲", "overlap_pending") == 0
        && exportsIds(exporter).contains(idOfTask(ds, "遗留甲"));

    // 另一种回答：这段不作数 ⇒ superseded（不导出），那对相交从头就不存在
    seedRunning(ds, "遗留丙", clock, 30, 0);
    seedRunning(ds, "遗留丁", clock, 20, 0);
    entries.finish(idOfTask(ds, "遗留丙"), 60_000L);
    Thread.sleep(600);
    ui.wake();
    Thread.sleep(500);
    boolean reachedThird = moveToCardRow(ui, "遗留丙");
    ui.card().press(KeyCode.X);
    Thread.sleep(700);
    boolean supersededAway = reachedThird && "superseded".equals(statusOfTask(ds, "遗留丙"))
        && flagOfTask(ds, "遗留丙", "overlap_pending") == 0
        && !exportsIds(exporter).contains(idOfTask(ds, "遗留丙"));

    // ③ 直接写库绕过应用：兜底那一层必须响亮失败
    seedRunning(ds, "遗留戊", clock, 40, 0);
    seedRunning(ds, "遗留己", clock, 30, 0);
    String now = clock.wall();
    boolean triggerBlocks = !sqlOk(ds, "UPDATE time_entry SET status='finished', ended_at='"
        + now + "', duration_ms=60000, end_precision='user', overlap_pending=0, updated_at='"
        + now + "' WHERE task='遗留戊'");
    // 这对抗不住后继判据的 leftover 就作废掉（superseded 不导出，也不留未放行相交）
    sqlOk(ds, "UPDATE time_entry SET status='superseded', overlap_pending=0"
        + " WHERE task IN ('遗留戊','遗留己')");
    ui.card().hide();
    ui.capture().setAutoHideOnFocusLost(true);

    System.out.println("SELFTEST_OVERLAP newer_carries_waiver=" + newerCarriesWaiver
        + " banner_warns=" + bannerWarns + " waived_pair_exports=" + waivedPairExports
        + " parked=" + parkedForConfirmation + " withheld=" + withheldFromExport
        + " card_shows_it=" + cardShowsIt + " waived_from_card=" + waivedFromCard
        + " superseded_away=" + supersededAway + " trigger_blocks_bypass=" + triggerBlocks);
    if (!newerCarriesWaiver || !bannerWarns || !waivedPairExports || !parkedForConfirmation
        || !withheldFromExport || !cardShowsIt || !waivedFromCard || !supersededAway
        || !triggerBlocks) {
      throw new IllegalStateException("SELFTEST_OVERLAP_FAILED 重叠的三层判据有一条不对");
    }
    System.out.println("SELFTEST_OVERLAP_OK");
  }

  /**
   * 主题自检：换配色要真的发到五个界面上，而不是只记了一个名字。
   *
   * 判据分三层：**样式串**（窗底与大数字的颜色确实变了、换回来必须与原来逐字节相同）、
   * **逐窗报告**（漏掉哪扇窗会当场露出来）、**偏好解析**（"跟随系统"现读注册表、
   * 认不出的写法回暗色 —— 坏存量不许拦住启动，同唤起键那条规矩）。
   */
  private static void themeSelfTest(UiController ui, SettingRepository settings) throws Exception {
    boolean startDark = "dark".equals(ui.themeName()) && fiveWindowsAre(ui, "dark")
        && ui.panel().windowStyle().contains("rgba(28,29,32");
    String darkStyle = ui.panel().windowStyle();
    String darkReadout = ui.panel().readoutStyle();

    // 面板上没内容就看不出"读得清读不清"，先起一段留证用的计时
    ui.capture().setAutoHideOnFocusLost(false);
    captureLine(ui, "10m 主题留证 #自检 @主题");
    ui.panel().show();
    Thread.sleep(400);

    ui.setTheme(UiTheme.LIGHT);
    Thread.sleep(400);
    // 判据过不等于看着对：亮色最容易坏在"边框与底同色"上，各裁一张窗给人眼看
    screenshotRegion("target/selftest-theme-light.png", ui.panel().screenRect());
    boolean allLight = "light".equals(ui.themeName()) && fiveWindowsAre(ui, "light");
    boolean lightStyle = ui.panel().windowStyle().contains("rgba(245,246,247")
        && ui.panel().readoutStyle().contains(UiTheme.LIGHT.text("title"))
        && !ui.panel().windowStyle().equals(darkStyle);

    ui.setTheme(UiTheme.DARK);
    Thread.sleep(400);
    screenshotRegion("target/selftest-theme-dark.png", ui.panel().screenRect());
    ui.panel().press(KeyCode.X);
    Thread.sleep(500);
    ui.capture().setAutoHideOnFocusLost(true);
    boolean backToDark = "dark".equals(ui.themeName())
        && ui.panel().windowStyle().equals(darkStyle)
        && ui.panel().readoutStyle().equals(darkReadout);

    Boolean systemLight = SystemThemeProbe.fromRegistry().read();
    UiTheme.Palette resolvedSystem = UiTheme.forPreference("system", systemLight);
    boolean systemResolves = resolvedSystem == (Boolean.TRUE.equals(systemLight)
        ? UiTheme.LIGHT : UiTheme.DARK);
    boolean junkFallsBack = UiTheme.forPreference("  随心情 ", systemLight) == UiTheme.DARK
        && UiTheme.forPreference(null, Boolean.TRUE) == UiTheme.DARK;

    String p = "dark";
    StringBuilder cycle = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      cycle.append(p).append(' ');
      p = UiTheme.nextPreference(p);
    }
    boolean cyclesThroughThree = "dark light system ".equals(cycle.toString()) && p.equals("dark");

    settings.put(THEME_KEY, "light");
    boolean persisted = "light".equals(settings.get(THEME_KEY, "dark"))
        && UiTheme.forPreference(storedTheme(settings), null) == UiTheme.LIGHT;
    settings.put(THEME_KEY, "dark");

    System.out.println("SELFTEST_THEME start_dark=" + startDark + " all_light=" + allLight
        + " light_style=" + lightStyle + " back_to_dark=" + backToDark
        + " system_resolves=" + systemResolves + " system_probe=" + systemLight
        + " junk_falls_back=" + junkFallsBack + " cycles=" + cyclesThroughThree
        + " persisted=" + persisted + " [" + ui.themeWindowsReport() + "]");
    if (!startDark || !allLight || !lightStyle || !backToDark || !systemResolves
        || !junkFallsBack || !cyclesThroughThree || !persisted) {
      throw new IllegalStateException("SELFTEST_THEME_FAILED 主题有一条判据不对");
    }
    System.out.println("SELFTEST_THEME_OK");
  }

  /** 五个界面必须一起换：漏掉哪扇窗，这个字符串就对不上。 */
  private static boolean fiveWindowsAre(UiController ui, String name) {
    return ui.themeWindowsReport().equals("panel=" + name + " capture=" + name
        + " card=" + name + " todo=" + name + " note=" + name);
  }

  /** 按任务名读一个整数列（overlap_waived / overlap_pending）；没有这行返回 -1。 */
  private static int flagOfTask(DataSource ds, String task, String column) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn()
          .prepareStatement("SELECT " + column + " FROM time_entry WHERE task=?")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getInt(1) : -1;
        }
      }
    }
  }

  /** 按任务名读一个文本列（aborted_reason）；没有这行返回 null。列名写死，task 走参数。 */
  private static String textOfTask(DataSource ds, String task, String column) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn()
          .prepareStatement("SELECT " + column + " FROM time_entry WHERE task=?")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  private static String idOfTask(DataSource ds, String task) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement("SELECT id FROM time_entry"
          + " WHERE task=? ORDER BY started_at DESC LIMIT 1")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  /** 造一条"还在跑"的历史行（ended_at 为空）。列名是写死的字面量，task 走参数。 */
  private static void seedRunning(DataSource ds, String task, Clock clock, int minutesAgo,
      int waived) throws SQLException {
    String start = OffsetDateTime.parse(clock.wall()).minusMinutes(minutesAgo).format(Clock.WIRE);
    String id = java.util.UUID.randomUUID().toString();
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
              + "end_precision,checkin_date,overlap_waived,last_heartbeat_at,created_at,updated_at)"
              + " VALUES (?, 'count_up', 'running', 'timer', ?, 'unassigned', ?, 'user', ?, ?,"
              + " ?, ?, ?)")) {
        ps.setString(1, id);
        ps.setString(2, task);
        ps.setString(3, start);
        ps.setString(4, desktimer.core.time.DayAttribution.checkinDate(start));
        ps.setInt(5, waived);
        ps.setString(6, start);
        ps.setString(7, start);
        ps.setString(8, start);
        ps.executeUpdate();
      }
    }
  }

  private static boolean sqlOk(DataSource ds, String statement) {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement()) {
        s.executeUpdate(statement);
        return true;
      } catch (SQLException e) {
        System.out.println("SQL_REFUSED " + e.getMessage());
        return false;
      }
    }
  }

  /** 跑一次真导出，返回文件里出现的 entry_id 集合 —— 判"进没进导出"只认落盘字节。 */
  private static java.util.Set<String> exportsIds(Exporter exporter) throws Exception {
    Exporter.Result r = exporter.exportAll();
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    String body = Files.readString(r.dir().resolve("time-entries.jsonl"));
    for (String line : body.lines().filter(l -> !l.isBlank()).toList()) {
      ids.add(line.replaceAll(".*\"entry_id\":\"", "").replaceAll("\".*", ""));
    }
    return ids;
  }

  /** 键盘走到指定那一条（最多绕一圈）：证的是"用户按得到"，不是内部索引。 */
  private static boolean moveToCardRow(UiController ui, String task) throws Exception {
    for (int i = 0; i < 12; i++) {
      if (task.equals(ui.card().selectedTask())) {
        return true;
      }
      ui.card().press(KeyCode.DOWN);
      Thread.sleep(200);
    }
    return false;
  }

  /** 按任务名读那条计时现在的状态（并发下"哪一段被 E 收了"只能这么证）。 */
  private static String statusOfTask(DataSource ds, String task) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn()
          .prepareStatement("SELECT status FROM time_entry WHERE task=?")) {
        ps.setString(1, task);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  /**
   * 笔记卡（M2-c）：默认视图今天 ∪ 置顶、搜了就搜全库、P 置顶且跨归属日常驻、Esc 两级退出。
   *
   * 判据一律用"唯一标记词命中几行"，不对"卡上总共几行"下断言 —— 前面几段自检已经往库里
   * 写过笔记了，拿绝对行数做判据会把顺序耦合进自检的先后。
   */
  private static void notesCardSelfTest(UiController ui, DataSource ds, NoteRepository notes)
      throws Exception {
    String mark = "卡自检" + (System.nanoTime() % 100000);
    Note a = notes.add(mark + " 甲文字", null, null, List.of("甲标签词"));
    notes.add(mark + " 乙文字", null, null, List.of("乙标签词"));

    ui.panel().press(KeyCode.L);
    Thread.sleep(400);
    boolean opened = ui.noteCard().showing();
    List<String> lines = ui.noteCard().lineTexts();
    boolean newestFirst = lines.size() >= 2
        && lines.get(0).contains(mark + " 乙") && lines.get(1).contains(mark + " 甲");

    // 标签名命中：正文里没有这个词，所以命中只可能来自 note_tag
    ui.noteCard().typeSearch("乙标签词");
    Thread.sleep(300);
    boolean tagHit = ui.noteCard().lineTexts().size() == 1
        && ui.noteCard().lineTexts().get(0).contains(mark + " 乙");
    ui.noteCard().typeSearch(mark);
    Thread.sleep(300);
    boolean bodyHit = ui.noteCard().lineTexts().size() == 2;
    // 带 # 的写法：搜索框上就是这么承诺的，实现必须真的按标签名找（整串 LIKE 正文会搜不到）
    ui.noteCard().typeSearch("#乙标签词");
    Thread.sleep(300);
    boolean scopedTag = ui.noteCard().lineTexts().size() == 1
        && ui.noteCard().lineTexts().get(0).contains(mark + " 乙")
        && ui.noteCard().titleLine().contains("搜「#乙标签词」");

    // 置顶的那条即使归属日在昨天，也要常驻今天的默认视图 —— 这是置顶的全部意义
    ui.noteCard().typeSearch("");
    Thread.sleep(300);
    // 默认视图"新的在上"，乙就在第一行：F 顶中的必须是选中的那一行
    ui.noteCard().press(KeyCode.F);
    Thread.sleep(300);
    boolean pinsTheSelectedRow = ui.noteCard().lineTexts().get(0).contains("★")
        && ui.noteCard().lineTexts().get(0).contains(mark + " 乙");
    ui.noteCard().press(KeyCode.F);
    Thread.sleep(200);
    // 换到甲再置顶它，然后把它的归属日挪到昨天：它仍该在今天默认视图的最前
    ui.noteCard().press(KeyCode.DOWN);
    Thread.sleep(200);
    ui.noteCard().press(KeyCode.F);
    Thread.sleep(300);
    stampNoteDay(ds, a.id(), "2026-09-05");
    ui.noteCard().typeSearch("");
    Thread.sleep(300);
    List<String> pinnedFirst = ui.noteCard().lineTexts();
    boolean pinnedSurvivesDay = pinnedFirst.get(0).contains("★")
        && pinnedFirst.get(0).contains(mark + " 甲");

    ui.noteCard().press(KeyCode.F);
    Thread.sleep(300);
    ui.noteCard().typeSearch("");
    Thread.sleep(300);
    // 取消置顶后甲就该离开今天的默认视图（它的归属日在昨天）；
    // 判据只看"我那两条在不在"，不看总行数 —— 前面的自检段也往今天写过笔记
    List<String> afterUnpin = ui.noteCard().lineTexts();
    boolean unpinDropsIt = afterUnpin.stream().noneMatch(r -> r.contains(mark + " 甲"))
        && afterUnpin.stream().anyMatch(r -> r.contains(mark + " 乙"));

    // Esc 两级：有搜索词时先清词（卡还开着、范围回到今天），没词时才收卡
    ui.noteCard().typeSearch(mark);
    Thread.sleep(300);
    ui.noteCard().press(KeyCode.ESCAPE);
    Thread.sleep(300);
    boolean escClearsFirst = ui.noteCard().showing()
        && ui.noteCard().titleLine().contains("今天");
    ui.noteCard().press(KeyCode.ESCAPE);
    Thread.sleep(300);
    boolean escCloses = !ui.noteCard().showing();

    System.out.println("SELFTEST_NOTES opened=" + opened + " newest_first=" + newestFirst
        + " tag_hit=" + tagHit + " scoped_tag=" + scopedTag + " body_hit=" + bodyHit
        + " pins_selected=" + pinsTheSelectedRow
        + " pinned_survives_day="
        + pinnedSurvivesDay + " unpin_drops=" + unpinDropsIt + " esc_clears=" + escClearsFirst
        + " esc_closes=" + escCloses);
    if (!opened || !newestFirst || !tagHit || !scopedTag || !bodyHit || !pinsTheSelectedRow
        || !pinnedSurvivesDay || !unpinDropsIt
        || !escClearsFirst || !escCloses) {
      throw new IllegalStateException("SELFTEST_NOTES_FAILED 笔记卡有一条判据不对");
    }
    System.out.println("SELFTEST_NOTES_OK");
  }

  /**
   * 把某条笔记整个挪到过去的某一天（造"置顶跨归属日还常驻"的那条样本）。
   *
   * **必须连 created_at 一起挪**：只改 checkin_date 会让自检库自己的导出违反归日判据
   * （验收器拿 created_at 重算 08:00 边界），scratch home 就永远过不了 verify-contract。
   * 挑 09:00 是为了让重算结果与写进去的归属日一致。
   */
  private static void stampNoteDay(DataSource ds, String id, String day) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "UPDATE note SET checkin_date=?, created_at=? WHERE id=?")) {
        ps.setString(1, day);
        ps.setString(2, day + "T09:00:00+08:00");
        ps.setString(3, id);
        ps.executeUpdate();
      }
    }
  }

  private static void pomodoroSelfTest(UiController ui, EntryRepository entries, DataSource ds,
      SettingRepository settings) throws Exception {
    String pinnedBefore = settings.get("ui.panel.pinned", "false");
    ui.capture().setAutoHideOnFocusLost(false);

    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("p 1m 番茄自检一 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
    String first = ui.activeEntryId();
    boolean started = first != null && "pomodoro_work".equals(kindOf(ds, first))
        && roundOf(ds, first) == 1;
    boolean pinnedTemporarily = ui.panel().pinned() && ui.panel().temporaryPin()
        && pinnedBefore.equals(settings.get("ui.panel.pinned", "false"));

    String restKeyName = TimerPanel.keyName(ui.panel().keys().primary("rest"));
    clearMisfireGate();
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    boolean suggestsRest = ui.panel().showing()
        && ui.panel().hintLine().length() > 0
        && "rest_short:300000".equals(ui.pendingRest())
        && ui.panel().stateLine().contains(restKeyName + " 开始短休 5分")
        && countWhere(ds, "SELECT COUNT(*) FROM time_entry WHERE kind LIKE 'rest_%'") == 0;

    // 空格不再"顺手起一段休息"（M2-b：并发下它必须只有一义 = 暂停/继续选中那一段）。
    // 按下去应当什么都不发生，休息另有 R 键。
    ui.panel().press(KeyCode.SPACE);
    Thread.sleep(400);
    boolean spaceDoesNotStartRest = "rest_short:300000".equals(ui.pendingRest())
        && !ui.hasActiveSession();

    ui.panel().press(KeyCode.R);
    Thread.sleep(700);
    String rest = ui.activeEntryId();
    boolean restStarted = rest != null && "rest_short".equals(kindOf(ds, rest))
        && ui.pendingRest().isEmpty() && ui.panel().showing();

    clearMisfireGate();
    ui.panel().press(KeyCode.E);
    Thread.sleep(600);
    boolean backToIdle = ui.activeEntryId() == null && !ui.panel().showing()
        && ui.pendingRest().isEmpty()
        && ui.panel().pinned() == "true".equals(pinnedBefore);

    // ⑤ 轮次基准：完成 1 → 放弃 1 → 再开始 1，第三条的 round 必须是 2
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("p 1m 番茄自检二 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
    ui.panel().press(KeyCode.X);
    Thread.sleep(600);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("p 1m 番茄自检三 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(600);
    String third = ui.activeEntryId();
    int roundOfThird = third == null ? -1 : roundOf(ds, third);
    int completed = entries.completedPomodoros(desktimer.core.time.DayAttribution
        .checkinDate(clockWall(ds)));
    clearMisfireGate();
    ui.panel().press(KeyCode.E);
    Thread.sleep(500);
    ui.capture().hide();
    ui.panel().hide();
    ui.capture().setAutoHideOnFocusLost(true);
    boolean roundBaseline = roundOfThird == 2 && completed == 1;

    String kinds = listKinds(ds);
    // ⑥ 到点自动结束（M2-b 裁的"番茄/休息不留已触发未确认"）：1 秒的番茄，不按任何键
    ui.capture().setAutoHideOnFocusLost(false);
    ui.capture().show();
    Thread.sleep(300);
    ui.capture().type("p 1s 番茄自动到期 #自检");
    Thread.sleep(200);
    ui.capture().submit();
    Thread.sleep(1800);
    boolean expiredAutoClosed = !ui.hasActiveSession()
        && "rest_short:300000".equals(ui.pendingRest()) && ui.panel().showing();
    ui.panel().press(KeyCode.R);
    Thread.sleep(600);
    ui.panel().press(KeyCode.X);
    Thread.sleep(600);
    boolean restAbortedQuietly = !ui.hasActiveSession() && ui.pendingRest().isEmpty();
    ui.capture().setAutoHideOnFocusLost(true);

    System.out.println("SELFTEST_POMODORO started=" + started + " pinned_temporarily="
        + pinnedTemporarily + " suggests_rest=" + suggestsRest + " space_starts_rest="
        + spaceDoesNotStartRest + " rest_started=" + restStarted + " back_to_idle=" + backToIdle
        + " expired_auto_closed=" + expiredAutoClosed + " rest_aborted=" + restAbortedQuietly
        + " round_of_third=" + roundOfThird
        + " completed_count=" + completed + " kinds=" + kinds);
    if (!started || !pinnedTemporarily || !suggestsRest || !restStarted || !backToIdle
        || !roundBaseline || !spaceDoesNotStartRest || !expiredAutoClosed || !restAbortedQuietly) {
      throw new IllegalStateException("SELFTEST_POMODORO_FAILED 番茄有一条生命周期不对");
    }
    System.out.println("SELFTEST_POMODORO_OK");
  }

  private static String clockWall(DataSource ds) {
    return new desktimer.core.time.Clock().wall();
  }

  private static String kindOf(DataSource ds, String id) throws SQLException {
    return scalar(ds, "SELECT kind FROM time_entry WHERE id=?", id);
  }

  private static int roundOf(DataSource ds, String id) throws SQLException {
    String v = scalar(ds, "SELECT pomodoro_round FROM time_entry WHERE id=?", id);
    return v == null ? 0 : Integer.parseInt(v);
  }

  private static String scalar(DataSource ds, String sql, String id) throws SQLException {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(sql)) {
        ps.setString(1, id);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  private static int countWhere(DataSource ds, String sql) throws SQLException {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement();
           java.sql.ResultSet rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  private static String listKinds(DataSource ds) throws SQLException {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement();
           java.sql.ResultSet rs = s.executeQuery(
               "SELECT kind || '=' || COUNT(*) FROM time_entry GROUP BY kind")) {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
          sb.append(rs.getString(1)).append(' ');
        }
        return sb.toString().trim();
      }
    }
  }

  private static int countEntries(DataSource ds) throws SQLException {
    synchronized (ds.lock()) {
      try (java.sql.Statement s = ds.conn().createStatement();
           java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM time_entry")) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  private static int countFrom(java.nio.file.Path db) throws SQLException {
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(
        "jdbc:sqlite:" + db.toAbsolutePath().toString().replace("\\", "/"));
        java.sql.Statement s = c.createStatement();
        java.sql.ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM time_entry")) {
      return rs.next() ? rs.getInt(1) : -1;
    }
  }

  /** 自检用：只截某扇窗的矩形（全桌面那张看不出主题效果，也没法给人看细节）。 */
  private static void screenshotRegion(String relativePath, java.awt.Rectangle rect) {
    try {
      java.awt.image.BufferedImage img = new Robot().createScreenCapture(rect);
      java.io.File f = new java.io.File(relativePath).getAbsoluteFile();
      f.getParentFile().mkdirs();
      javax.imageio.ImageIO.write(img, "png", f);
      System.out.println("SCREENSHOT path=" + f + " rect=" + rect + " bytes=" + f.length());
    } catch (Exception e) {
      System.out.println("SCREENSHOT_FAILED " + e.getMessage());
    }
  }

  private static void screenshot(String relativePath) {
    try {
      java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
      java.awt.image.BufferedImage img = new Robot().createScreenCapture(
          new java.awt.Rectangle(screen));
      java.io.File f = new java.io.File(relativePath).getAbsoluteFile();
      f.getParentFile().mkdirs();
      javax.imageio.ImageIO.write(img, "png", f);
      System.out.println("SCREENSHOT path=" + f + " bytes=" + f.length());
    } catch (Exception e) {
      System.out.println("SCREENSHOT_FAILED " + e.getMessage());
    }
  }

  /** 自检用：把 KeySpec 转成 AWT 按键注入一次。 */
  private static void inject(KeySpec spec) throws Exception {
    Robot r = new Robot();
    int awt = spec.awtKeyCode();
    if (spec.ctrl()) {
      r.keyPress(KeyEvent.VK_CONTROL);
    }
    if (spec.alt()) {
      r.keyPress(KeyEvent.VK_ALT);
    }
    if (spec.shift()) {
      r.keyPress(KeyEvent.VK_SHIFT);
    }
    if (spec.win()) {
      r.keyPress(KeyEvent.VK_WINDOWS);
    }
    r.keyPress(awt);
    r.keyRelease(awt);
    if (spec.win()) {
      r.keyRelease(KeyEvent.VK_WINDOWS);
    }
    if (spec.shift()) {
      r.keyRelease(KeyEvent.VK_SHIFT);
    }
    if (spec.alt()) {
      r.keyRelease(KeyEvent.VK_ALT);
    }
    if (spec.ctrl()) {
      r.keyRelease(KeyEvent.VK_CONTROL);
    }
  }

  /** 面板动作键的覆盖串；读不到就按出厂走（按键坏了不该拦住启动）。 */
  private static String storedPanelKeys(SettingRepository settings) {
    try {
      String v = settings.get("key.panel", "");
      return v == null ? "" : v.trim();
    } catch (SQLException e) {
      System.out.println("KEY_PANEL_READ_FAILED " + e.getMessage());
      return "";
    }
  }

  /** 库里存的唤起键；读不到就按"没存过"处理，绝不因为一次查询失败拦住启动。 */
  private static String storedWake(SettingRepository settings) {
    try {
      String v = settings.get(WAKE_KEY, "");
      return v == null ? "" : v.trim();
    } catch (SQLException e) {
      System.out.println("HOTKEY_SETTING_READ_FAILED " + e.getMessage());
      return "";
    }
  }

  /** 存着的主题偏好；读不到或没存过就是 dark —— shipped 的那张脸，也是唯一安全的默认。 */
  private static String storedTheme(SettingRepository settings) {
    try {
      return settings.get(THEME_KEY, "dark");
    } catch (SQLException e) {
      System.out.println("THEME_READ_FAILED " + e.getMessage() + " 用默认暗色");
      return "dark";
    }
  }

  /**
   * 偏好 → 配色。"跟随系统"每次都在这里现读注册表（不在库里存系统的答案），
   * 所以他在 Windows 设置里改成亮色后，本应用下一次开窗/换主题就会跟上；
   * 探测失败回 null，{@link UiTheme#forPreference} 会退到暗色。
   */
  private static UiTheme.Palette resolveTheme(String pref) {
    Boolean systemLight = null;
    try {
      systemLight = SystemThemeProbe.fromRegistry().read();
    } catch (RuntimeException e) {
      System.out.println("THEME_PROBE_FAILED " + e.getClass().getSimpleName());
    }
    return UiTheme.forPreference(pref, systemLight);
  }

  /** 库里存了个坏字符串也不能开不了机：退回出厂默认，让用户在设置窗里重新按一次键覆盖它。 */
  private static KeySpec wakeSpec(String wanted) {
    try {
      return KeySpec.parse(wanted);
    } catch (IllegalArgumentException e) {
      System.out.println("HOTKEY_SPEC_INVALID \"" + wanted + "\" 不行（" + e.getMessage()
          + "），退回 " + DEFAULT_WAKE);
      return KeySpec.parse(DEFAULT_WAKE);
    }
  }

  private static String strArg(String[] args, String key, String fallback) {
    for (String a : args) {
      if (a.startsWith(key + "=")) {
        return a.substring(key.length() + 1);
      }
    }
    return fallback;
  }

  private static boolean hasFlag(String[] args, String flag) {
    for (String a : args) {
      if (a.equals(flag)) {
        return true;
      }
    }
    return false;
  }

  private static long numArg(String[] args, String key, long fallback) {
    for (String a : args) {
      if (a.startsWith(key + "=")) {
        return Long.parseLong(a.substring(key.length() + 1));
      }
    }
    return fallback;
  }
}
