import desktimer.core.time.*;
import desktimer.db.*;
import desktimer.export.Exporter;
import desktimer.platform.*;
import desktimer.ui.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** 新功能隔离验收；只写 target 家，不注册或注入全局快捷键。全屏覆盖仅短暂显示。 */
public final class FeatureSelfTest {
  private static UiController ui;
  private static WorkbenchWindow workspace;
  private static Path home;
  public static void main(String[] args) throws Exception {
    home = Files.createTempDirectory(Path.of("target"), "feature-selftest-");
    var diagnostics = desktimer.diagnostics.RuntimeDiagnostics.open(home.resolve("logs"));
    CountDownLatch started = new CountDownLatch(1);
    Platform.startup(started::countDown); started.await(); Platform.setImplicitExit(false);
    DataSource ds = new DataSource(home.resolve("timer.db"));
    try {
      Migrations.migrate(ds.conn());
      Clock clock = new Clock();
      EntryRepository entries = new EntryRepository(ds, clock);
      SettingRepository settings = new SettingRepository(ds, clock);
      TodoRepository todos = new TodoRepository(ds, clock);
      String fixtureDay = DayAttribution.checkinDate(clock.wall());
      entries.importBackfill("feature-origin", "fixture", "时间线补录来源", null, java.util.List.of(),
          fixtureDay + "T09:00:00+08:00", fixtureDay + "T09:25:00+08:00");
      settings.put("guard.enabled", "false"); settings.put("guard.used_ms", "5400000");
      settings.put("sound.choice", "off");
      todos.add("阅读开源计时器状态机", null, java.util.List.of("学习"));
      fx(() -> {
        ui = new UiController(entries, clock, () -> {}, settings, new NoteRepository(ds, clock), todos,
            new FullScreenDetector(), UiTheme.DARK, () -> 300_000L);
        ui.showWorkbench(); workspace = (WorkbenchWindow) field(ui, "workbench");
        snapshot(workspace.root(), "tasks.png");
        @SuppressWarnings("unchecked") ListView<Todo> list = (ListView<Todo>) workspace.root().lookup("#tasks-list");
        list.getSelectionModel().select(0);
        fire(workspace.root(), "开始 25 分番茄");
      });
      String work = ui.liveSessionIds().get(0);
      require(entries.find(work).orElseThrow().targetMs() == 25 * 60_000L, "task starts a real pomodoro");
      fx(() -> {
        ui.panel().setCompact(true); ui.panel().show();
        ((javafx.animation.Timeline) field(ui, "tick")).stop();
        ui.panel().setAnimations(true); ui.panel().render("24:59", "进度平滑验证", 0.1);
      });
      Thread.sleep(250);
      fx(() -> {
        snapshot((Parent) ui.panel().root(), "compact.png");
        require(ui.panel().root().getBoundsInLocal().getWidth() <= 302, "compact panel is actually narrower");
        require(Math.abs(((ProgressBar) ui.panel().root().lookup(".progress-bar")).getProgress() - 0.1) < 0.01,
            "animation reaches requested progress");
        ((javafx.animation.Timeline) field(ui, "tick")).play();
      });
      settings.put("guard.enabled", "true");
      fx(() -> invoke(ui, "guardPulse"));
      require(entries.find(work).orElseThrow().status() == EntryStatus.PAUSED, "forced break pauses work");
      fx(() -> {
        BreakWindow overlay = (BreakWindow) field(ui, "breakWindow");
        require(overlay.screenCount() == javafx.stage.Screen.getScreens().size(), "all monitors covered");
        snapshot(overlay.root(), "forced-rest.png");
        String rest = (String) field(ui, "activeRestId");
        invoke(ui, "skipRest"); invoke(ui, "deferRest");
        require(rest.equals(field(ui, "activeRestId")), "forced rest refuses normal skip/defer");
        invoke(ui, "emergencyRest");
        var guard = (RestGuard) field(ui, "guard");
        require(guard.usedMs() >= 90 * 60_000L, "emergency keeps debt");
        require(!overlay.showing(), "emergency closes all overlays");

        ui.showWorkbench();
        ((TabPane) workspace.root().lookup(".tab-pane")).getSelectionModel().select(2);
        workspace.root().applyCss(); workspace.root().layout();
        fire(workspace.root(), "重置连续使用累计");
        require(guard.usedMs() == 0 && "0".equals(settings.get("guard.used_ms", "")), "settings reset applies in memory and on disk");
        require(entries.find(work).orElseThrow().status() == EntryStatus.PAUSED, "reset leaves work paused");
        workspace.hide();

        var begin = UiController.class.getDeclaredMethod("beginRest", EntryKind.class, long.class, boolean.class);
        begin.setAccessible(true);
        guard.sample(clock.elapsedMs() + 1000, 0L, false, Long.MAX_VALUE);
        require(guard.usedMs() > 0, "qualifying exit has a nonzero balance to clear");
        begin.invoke(ui, EntryKind.REST_LONG, 600_000L, true);
        String qualified = (String) field(ui, "activeRestId");
        var forcedSession = ((ActiveSessions) field(ui, "sessions")).byId(qualified);
        var elapsed = forcedSession.getClass().getDeclaredField("accumulatedMs");
        elapsed.setAccessible(true);
        elapsed.setLong(forcedSession, 298_000L);
        invoke(ui, "pulse");
        overlay.root().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
        require(overlay.showing() && guard.forced(), "Escape before five minutes leaves rest active");

        elapsed.setLong(forcedSession, 300_000L);
        invoke(ui, "pulse");
        snapshot(overlay.root(), "forced-rest-ready.png");
        fire(overlay.root(), "结束休息（清零使用累计）");
        var completedRest = entries.find(qualified).orElseThrow();
        require(!overlay.showing() && completedRest.status() == EntryStatus.FINISHED
            && completedRest.durationMs() >= 300_000L && completedRest.durationMs() < 600_000L,
            "qualified exit closes all screens and records only actual rest");
        require(guard.usedMs() == 0 && "0".equals(settings.get("guard.used_ms", "")), "qualified exit clears persisted usage");

        begin.invoke(ui, EntryKind.REST_LONG, 600_000L, true);
        elapsed.setLong(((ActiveSessions) field(ui, "sessions")).byId((String) field(ui, "activeRestId")), 300_000L);
        invoke(ui, "pulse");
        overlay.root().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
        require(!overlay.showing(), "Escape closes qualified rest");

        begin.invoke(ui, EntryKind.REST_LONG, 300_000L, true);
        elapsed.setLong(((ActiveSessions) field(ui, "sessions")).byId((String) field(ui, "activeRestId")), 300_000L);
        invoke(ui, "pulse");
        require(!overlay.showing() && !guard.forced(), "configured deadline still closes automatically");
        ui.panel().press(javafx.scene.input.KeyCode.X);
      });
      settings.put("guard.enabled", "false");
      fx(() -> { ui.capture().type("p 2s 测试下一段 @回归"); ui.capture().submit(); });
      Thread.sleep(2600);
      fx(() -> {
        require(!ui.pendingRest().isEmpty(), "pomodoro completion suggests a break");
        invoke(ui, "startRest");
        require(((BreakWindow) field(ui, "breakWindow")).showing(), "normal fullscreen rest opens");
        ActiveSessions owner = (ActiveSessions) field(ui, "sessions");
        var rest = owner.byId((String) field(ui, "activeRestId"));
        var accumulated = rest.getClass().getDeclaredField("accumulatedMs");
        accumulated.setAccessible(true); accumulated.setLong(rest, 300_000);
        invoke(ui, "deferRest");
        require(field(ui, "activeRestId") == null && !ui.pendingRest().isEmpty(), "defer does not count future break time");
        require(((RestGuard) field(ui, "guard")).usedMs() == 0, "completed five-minute rest is credited without waiting for a polling tick");
        invoke(ui, "skipRest");
        require(ui.hasActiveSession(), "skip starts next pomodoro");
        ui.panel().press(javafx.scene.input.KeyCode.X);
        ui.showWorkbench();
        TabPane tabs = (TabPane) workspace.root().lookup(".tab-pane");
        tabs.getSelectionModel().select(1); snapshot(workspace.root(), "timeline.png");
        tabs.getSelectionModel().select(2); snapshot(workspace.root(), "preferences.png");
        fire(workspace.root(), "刷新诊断");
        require(workspace.root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().contains("512 KB")), "diagnostics state is visible");
        ui.setTheme(UiTheme.LIGHT); snapshot(workspace.root(), "preferences-light.png");
        ((TabPane) workspace.root().lookup(".tab-pane")).getSelectionModel().select(1);
        workspace.root().applyCss(); workspace.root().layout();
        var timeline = (ListView<?>) workspace.root().lookup("#timeline-list");
        int fixtureIndex = -1;
        for (int i = 0; i < timeline.getItems().size(); i++) {
          if (timeline.getItems().get(i) instanceof EntryRepository.TimelineItem item
              && "时间线补录来源".equals(item.task())) fixtureIndex = i;
        }
        require(fixtureIndex >= 0, "backfill fixture exists in current timeline");
        timeline.scrollTo(fixtureIndex);
        timeline.layout();
        require(workspace.root().lookupAll(".list-cell").stream().anyMatch(n -> n instanceof ListCell<?> c && c.getText() != null && c.getText().contains("补录")), "timeline renders backfill provenance");
        snapshot(workspace.root(), "timeline-light.png");
        workspace.hide();
      });
      new Exporter(ds, home.resolve("exports"), clock, desktimer.App.APP_VERSION).exportAll();
      SoundPlayer.play("wind-chime"); awaitSound("wind-chime");
      require(SoundPlayer.lastError().isEmpty(), "wind-chime decodes on real audio device");
      SoundPlayer.play("tic-toc"); awaitSound("tic-toc");
      require(SoundPlayer.lastError().isEmpty(), "tic-toc decodes on real audio device");
      SoundPlayer.stop();
      fx(() -> ui.shutdown());
      System.out.println("FEATURE_SELFTEST_OK tasks=true compact=true animation=true timeline=true fullscreen=true"
          + " forced_pause=true forced_no_skip=true emergency_debt=true forced_qualified_exit=true reset_usage=true five_minute_credit=true defer=true next_pomodoro=true audio=true home=" + home);
      Files.writeString(Path.of("target", "feature-selftest-result.txt"), "FEATURE_SELFTEST_OK " + home.toAbsolutePath());
    } finally {
      fx(() -> { if (ui != null) ui.shutdown(); if (workspace != null) workspace.hide(); });
      ds.close();
      diagnostics.close();
      Platform.exit();
    }
  }
  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
  }
  private static void awaitSound(String choice) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!choice.equals(SoundPlayer.lastPlayed()) && System.nanoTime() < deadline) Thread.sleep(50);
    require(choice.equals(SoundPlayer.lastPlayed()), "audio device started " + choice + ": " + SoundPlayer.lastError());
  }
  private static void invoke(Object target, String name) throws Exception {
    var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
  }
  private static void fire(Parent root, String title) {
    Button button = (Button) root.lookupAll(".button").stream().filter(n -> n instanceof Button b && title.equals(b.getText())).findFirst().orElseThrow();
    button.fire();
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private interface FxWork { void run() throws Exception; }
  private static void fx(FxWork work) throws Exception {
    FutureTask<Void> task = new FutureTask<>(() -> { work.run(); return null; });
    Platform.runLater(task); task.get(20, TimeUnit.SECONDS);
  }
  private static void snapshot(Parent node, String name) throws Exception {
    node.applyCss(); node.layout();
    WritableImage image = node.snapshot(null, null);
    int width = (int) image.getWidth(), height = (int) image.getHeight();
    int[] pixels = new int[width * height];
    image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
    BufferedImage png = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    png.setRGB(0, 0, width, height, pixels, 0, width);
    ImageIO.write(png, "png", home.resolve(name).toFile());
  }
}
