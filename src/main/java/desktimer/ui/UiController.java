package desktimer.ui;

import desktimer.core.parse.CaptureParser;
import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import desktimer.db.NoteRepository;
import desktimer.db.ReviewItem;
import desktimer.db.SettingRepository;
import desktimer.db.Todo;
import desktimer.db.TodoRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

/**
 * 把捕获窗、计时面板和仓储缝在一起：捕获 → 建条目 → 面板跑表 → 结束即导出。
 * UI 只做展示与输入，时长语义全部走 EntryRepository 与 Clock。
 */
public final class UiController {

  /** 勿扰到什么时候（ISO 时刻，空=没开）。存库而不是存内存：重启后不该偷偷恢复打扰。 */
  private static final String DND_KEY = "dnd.until";

  /** 短休 5 分钟；长休 20 分钟（PRD 给 15-30，取中间值，做成常量方便以后按人调）。 */
  private static final long REST_SHORT_MS = 5 * 60_000L;
  private static final long REST_LONG_MS = 20 * 60_000L;
  /** 每 4 个**完成**的番茄一次长休（PRD 第 1 条）。 */
  private static final int LONG_REST_EVERY = 4;

  /**
   * 误触门限（实测：倒计时阶段按一次回车，一段"0 分钟的完成"就这么进了账）。
   * 短到这个程度又还没到点，能证明的只有"他按了一下"，证明不了"这一段干成了"。
   */
  public static final long MISFIRE_MS = 5_000L;

  /** 作废理由要能在条目里读出门限，不然他看到的是"用户放弃"，那条账就对不上真相。 */
  public static final String MISFIRE_REASON = "疑似误触（不足 " + MISFIRE_MS / 1000L + " 秒）";

  private final EntryRepository entries;
  private final NoteRepository notes;
  private final TodoRepository todos;
  private final java.util.function.Supplier<desktimer.platform.FullScreenDetector.State> foregroundState;
  private final SettingRepository settings;
  private final Clock clock;
  private final Runnable onEntriesChanged;
  private final CaptureWindow capture;
  private final TimerPanel panel;
  private final ReconcileCard card;
  private final TodoCard todoCard;
  private final NoteCard noteCard;
  private final Timeline tick;
  private final Timeline guardTick;
  private final java.util.function.Supplier<Long> idleSource;
  private final desktimer.core.time.RestGuard guard;
  private final BreakWindow breakWindow;
  private WorkbenchWindow workbench;
  private CaptureParser.Plan nextPomodoroPlan;
  private String activeRestId;
  private boolean deferredOnce;
  private long deferredUntil;
  private long emergencyUntil;
  private long savedGuardAt;
  private String restoringRest;
  private volatile java.util.Map<String, Long> measuredSnapshot = java.util.Map.of();

  public java.util.Map<String, Long> liveMeasurements() { return measuredSnapshot; }

  private List<ReviewItem> queue = List.of();
  private List<Todo> openTodos = List.of();
  /**
   * 在跑的会话列表（M2-b）。以前这里是五个字段（entryId/targetMs/accumulatedMs/paused/…），
   * 也就是"面板只容得下一段"；现在全部判据住在 {@link ActiveSessions} 里，能单测。
   */
  private final ActiveSessions sessions = new ActiveSessions();
  /** 最近一次起段时的相交数（提示横幅用，不是每段一个：放行与否已经写进 overlap_waived）。 */
  private int conflicts;
  /** 被当面拒掉的操作（第二个番茄），下一次成功起段就清空；空串＝没有。 */
  private String refusal = "";
  /** "待开始的休息"：只有用户按一键才会变成条目。 */
  private EntryKind pendingRestKind;
  private long pendingRestMs;
  private boolean restPromptPendingDelivery;
  /** 刚完成的那个番茄是第几轮（提示条与自检都读它）。 */
  private int lastRound;
  /** 面板一次最多显示几行会话（超出折成"+N 段"，↑↓ 仍遍历全部）。 */
  private static final int MAX_ROWS = 6;
  /** 当前配色。由 App 从 setting ui.theme + 系统探测解出来，换主题走 {@link #setTheme}。 */
  private UiTheme.Palette theme;

  public UiController(EntryRepository entries, Clock clock, Runnable onEntriesChanged,
      SettingRepository settings, NoteRepository notes, TodoRepository todos,
      desktimer.platform.FullScreenDetector fullscreen, UiTheme.Palette theme) {
    this(entries, clock, onEntriesChanged, settings, notes, todos, fullscreen, theme,
        new desktimer.platform.IdleProbe()::idleMs);
  }

  public UiController(EntryRepository entries, Clock clock, Runnable onEntriesChanged,
      SettingRepository settings, NoteRepository notes, TodoRepository todos,
      desktimer.platform.FullScreenDetector fullscreen, UiTheme.Palette theme,
      java.util.function.Supplier<Long> idleSource) {
    this(entries, clock, onEntriesChanged, settings, notes, todos, fullscreen, theme, idleSource, fullscreen::probe);
  }

  public UiController(EntryRepository entries, Clock clock, Runnable onEntriesChanged,
      SettingRepository settings, NoteRepository notes, TodoRepository todos,
      desktimer.platform.FullScreenDetector fullscreen, UiTheme.Palette theme,
      java.util.function.Supplier<Long> idleSource,
      java.util.function.Supplier<desktimer.platform.FullScreenDetector.State> foregroundState) {
    this.idleSource = idleSource;
    this.entries = entries;
    this.clock = clock;
    this.onEntriesChanged = onEntriesChanged;
    this.notes = notes;
    this.todos = todos;
    this.settings = settings;
    this.foregroundState = foregroundState;
    this.theme = theme;
    this.guard = new desktimer.core.time.RestGuard(settingLong("guard.used_ms", 0));
    this.restoringRest = setting("guard.rest_until", "");
    this.breakWindow = new BreakWindow(this::deferRest, this::skipRest, this::emergencyRest);
    this.capture = new CaptureWindow(this::submit, this::submitNote, this::submitTodo, () -> {
    }, settings, theme);
    this.panel = new TimerPanel(this::pauseResume, this::finish, this::abort,
        this::selectPrev, this::selectNext, this::startRest,
        this::openCapture, this::toggleDnd, this::toggleNotes, settings, theme);
    this.card = new ReconcileCard(this::confirmItem, this::rescheduleItem, this::discardItem,
        capture::show, settings, theme);
    // 面板被用户自己收起 ⇒ "待开始的休息"一起作废：提示只能对应该还没操作的那件事
    this.panel.onHidden(() -> { if (deferredUntil == 0) dropPendingRest(); });
    this.todoCard = new TodoCard(this::completeTodo, this::backfillTodo, this::discardTodo,
        capture::show, this::checkinToday, settings, theme);
    this.noteCard = new NoteCard(notes, this::checkinToday, settings, theme);
    panel.extraActions(this::showWorkbench, this::deferRest, this::skipRest, this::extendSelected);
    applyPreferences();
    this.tick = new Timeline(new KeyFrame(Duration.millis(250), e -> pulse()));
    this.tick.setCycleCount(Animation.INDEFINITE);
    // 隐藏的面板不做场景图更新；没有计时也没有捕获窗时，连 FX 脉冲都不需要。
    capture.root().getScene().getWindow().showingProperty().addListener(
        (o, was, shown) -> updateTickState());
    panel.root().getScene().getWindow().showingProperty().addListener((o, was, shown) -> {
      if (shown) {
        refresh(); // 唤起时先补上后台走过的时间，不能显示收起前的旧数字。
      } else {
        updateTickState();
      }
    });
    guardTick = new Timeline(new KeyFrame(Duration.seconds(5), e -> guardPulse()));
    guardTick.setCycleCount(Animation.INDEFINITE);
    guardTick.play();
  }

  /**
   * 换主题：五个界面各重贴一遍自己的样式。
   *
   * 不重建窗口是刻意的 —— 重建会丢位置记忆与钉住状态（那是用户自己按出来的东西），
   * 而换配色根本不需要重新布一次 Scene。
   */
  public void setTheme(UiTheme.Palette theme) {
    this.theme = theme;
    capture.applyTheme(theme);
    panel.applyTheme(theme);
    card.applyTheme(theme);
    todoCard.applyTheme(theme);
    noteCard.applyTheme(theme);
    if (workbench != null) workbench.applyTheme(theme);
    System.out.println("THEME_APPLIED " + theme.name());
  }

  public String themeName() {
    return theme.name();
  }

  /** 自检用：五个界面各自报一遍当前配色（换主题有没有漏掉哪扇窗，只能这样逐窗看）。 */
  public String themeWindowsReport() {
    return "panel=" + panel.themeName() + " capture=" + capture.themeName()
        + " card=" + card.themeName() + " todo=" + todoCard.themeName()
        + " note=" + noteCard.themeName();
  }

  public CaptureWindow capture() {
    return capture;
  }

  public TimerPanel panel() {
    return panel;
  }

  public TodoCard todoCard() {
    return todoCard;
  }

  private long captureShownAt;

  /**
   * 唤起"当前该看的那个窗"：跑着的计时 > 待确认队列 > 待办卡 > 捕获窗。
   * 快捷键与托盘左键走同一条路 —— 否则用户跑着计时却只被还给一个输入框，
   * 既找不到也停不下（用户反馈的正是这个）。
   *
   * 对账与待办**都不在启动时弹**（四级打扰的定义），排在"正在跑"之后；
   * 待办卡还多一层按归属日抑制（Esc"稍后"就是今天别再抬），因为未完成清单不会自己变空。
   */
  public void wake() {
    if (breakWindow.showing()) { breakWindow.raise(); return; }
    // 笔记卡不进这条梯子（它是主动查阅），但抬别的窗时先把它收掉：两张常顶的窗叠在一起，
    // 用户看到的就只有最上面那张，剩下那段的剩余时间等于看不见。
    if (noteCard.showing()) {
      noteCard.hide();
    }
    if (hasActiveSession() || pendingRestKind != null) {
      if (pendingRestKind != null && deferredUntil == 0) {
        restPromptPendingDelivery = false;
        panel.setAwaitingChoice(true);
      }
      panel.show();
      logWake(pendingRestKind == null ? "panel" : "panel-rest");
      return;
    }
    refreshQueue();
    if (!queue.isEmpty()) {
      card.render(queue);
      card.show();
      logWake("card");
      return;
    }
    refreshTodos();
    if (!openTodos.isEmpty() && !todoCard.suppressedFor(checkinToday())) {
      todoCard.render(openTodos);
      todoCard.show();
      logWake("todos");
      return;
    }
    captureShownAt = System.currentTimeMillis();
    capture.show();
    logWake("capture");
  }

  /** 日志排在 show 之后跑：否则 showing 还是上一帧的旧值，看着像没弹出来。 */
  private void logWake(String target) {
    Platform.runLater(() -> System.out.println("WAKE target=" + target
        + " panel_showing=" + panel.showing() + " capture_showing=" + capture.showing()
        + " card_showing=" + card.showing() + " todo_showing=" + todoCard.showing()
        + " queue=" + queue.size() + " todos=" + openTodos.size()
        + " pinned=" + panel.pinned()));
  }

  /** 队列只在唤起与处理完一条时重读，不进 250ms 的 tick。 */
  public void refreshQueue() {
    try {
      queue = entries.reviewQueue();
    } catch (SQLException e) {
      System.out.println("QUEUE_READ_FAILED " + e.getMessage());
      return;
    }
    card.render(queue);
    if (queue.isEmpty()) {
      card.hide();
    }
  }

  public ReconcileCard card() {
    return card;
  }

  public int queueSize() {
    return queue.size();
  }

  private void confirmItem(ReviewItem item) {
    try {
      if (item.overlapPending()) {
        // 卡上 Enter 在这一条上问的是"两段都算吗"，先答这个；
        // 行若同时还是中断/漏提示的，答完仍留在队列里问下一件事
        entries.waiveOverlap(item.id());
        System.out.println("OVERLAP_WAIVED " + item.id() + " 用户认了两段都算");
        afterReviewAction("OVERLAP_WAIVED", item);
        return;
      }
      if (item.status() == EntryStatus.INTERRUPTED) {
        long ms = item.durationMs() == null ? 0L : item.durationMs();
        entries.confirmRecovery(item.id(), item.endedAt(), ms);
      } else {
        entries.acknowledge(item.id());
      }
      afterReviewAction("CONFIRMED", item);
    } catch (SQLException | IllegalStateException e) {
      System.out.println("CONFIRM_FAILED " + item.id() + " " + e.getMessage());
    }
  }

  private void rescheduleItem(ReviewItem item, String endedAtIso) {
    if (item.status() != EntryStatus.INTERRUPTED) {
      System.out.println("RESCHEDULE_SKIPPED " + item.id() + " 状态=" + item.status().wire()
          + " 只有中断的条目能改结束时间，跑着的请用 E/X");
      return;
    }
    try {
      // 写全名：这个文件里 Duration 已经指 javafx.util.Duration（计时动画在用）
      long ms = Math.max(0L, java.time.Duration.between(OffsetDateTime.parse(item.startedAt()),
          OffsetDateTime.parse(endedAtIso)).toMillis());
      entries.confirmRecovery(item.id(), endedAtIso, ms);
      System.out.println("RESCHEDULED " + item.id() + " ended_at=" + endedAtIso
          + " duration_ms=" + ms);
      afterReviewAction("RESCHEDULED", item);
    } catch (SQLException | IllegalStateException e) {
      System.out.println("RESCHEDULE_FAILED " + item.id() + " " + e.getMessage());
    }
  }

  private void discardItem(ReviewItem item) {
    if (item.overlapPending()) {
      // 重叠的另一半回答："这段不作数"。走 superseded 而不是 aborted ——
      // aborted 的行仍然导出，那对未放行的相交就会把契约弄红；superseded 不进导出，重叠从头不存在
      try {
        entries.supersedeOverlap(item.id());
        afterReviewAction("OVERLAP_SUPERSEDED", item);
      } catch (SQLException | IllegalStateException e) {
        System.out.println("DISCARD_FAILED " + item.id() + " " + e.getMessage());
      }
      return;
    }
    if (item.status() != EntryStatus.INTERRUPTED) {
      System.out.println("DISCARD_SKIPPED " + item.id() + " 状态=" + item.status().wire()
          + " 跑着的计时要用 X 放弃，不走对账丢弃");
      return;
    }
    try {
      entries.discard(item.id(), "对账时用户丢弃");
      afterReviewAction("DISCARDED", item);
    } catch (SQLException | IllegalStateException e) {
      System.out.println("DISCARD_FAILED " + item.id() + " " + e.getMessage());
    }
  }

  private void afterReviewAction(String label, ReviewItem item) {
    System.out.println(label + " " + item.id());
    refreshQueue();
    onEntriesChanged.run();
    if (queue.isEmpty()) {
      card.hide();
    }
  }

  /** 捕获窗迟迟拿不到焦点（Windows 前台锁）时不留野窗：超时自己收掉。 */
  public void sweepStaleCapture() {
    if (captureShownAt > 0 && capture.showing() && !capture.focused()
        && System.currentTimeMillis() - captureShownAt > 45_000) {
      captureShownAt = 0;
      capture.hide();
      System.out.println("CAPTURE_STALE_HIDDEN");
    }
  }

  private void submit(CaptureParser.Plan plan) {
    if (guard.forced()) { breakWindow.raise(); return; }
    try {
      boolean pomodoro = plan.kind() == EntryKind.POMODORO_WORK;
      if (pomodoro && sessions.hasPomodoro()) {
        // 番茄的轮次与休息提示都以"同时只有一个"为前提（他裁的口径），第二个起不来就不是漏账，
        // 而是这一键被拒 —— 拒必须看得见原因，否则用户以为已经在跑了。
        refusal = "番茄同时只跑一个：先收掉「" + sessions.all().stream()
            .filter(s -> s.kind() == EntryKind.POMODORO_WORK).findFirst()
            .map(ActiveSessions.Session::task).orElse("在跑的那段") + "」";
        System.out.println("POMODORO_SECOND_REFUSED " + refusal);
        panel.show();
        refresh();
        return;
      }
      refusal = "";
      if (pomodoro) nextPomodoroPlan = plan;
      // 并发的核心变化：另起一段**不再**把上一段放弃掉。以前那步"被新计时取代"是
      // 单会话的产物（面板只跟一段，旧那段就停不掉了）；现在列表里每段都有自己的出口键。
      String projectId = entries.ensureProject(plan.project());
      Entry started;
      int round = 0;
      if (pomodoro) {
        // 轮次在**开始这一刻**算并写入：面板要立刻显示"第几个"，
        // 断电留下的 interrupted 行也得带轮次，否则账断在半路。
        round = entries.completedPomodoros(checkinToday()) + 1;
        started = entries.startPomodoro(plan.task(), projectId, plan.targetMs(), round, false);
      } else {
        started = entries.start(plan.task(), plan.kind(), projectId, plan.targetMs(), false);
      }
      entries.attachTags(started.id(), plan.tags());
      List<Entry> clashes = entries.conflictsFor(projectId, started.startedAt(), null);
      conflicts = (int) clashes.stream().filter(c -> !c.id().equals(started.id())).count();
      if (conflicts > 0) {
        // 提示条就是这一刻的确认界面（Q4 之后没有可点的弹窗）；
        // 放行必须写进行，否则外部消费者读到相交区间时无法区分"静默造的"和"用户认过的"。
        entries.waiveOverlap(started.id());
        System.out.println("CONFLICT_WARNED_AND_WAIVED n=" + conflicts + " entry=" + started.id()
            + " 面板顶部提示，放行由用户决定");
      }
      // 新起的那一段成为选中项（判据与理由见 ActiveSessions.add）
      sessions.add(new ActiveSessions.Session(started.id(), plan.kind(), plan.task(),
          plan.targetMs(), round, clock.elapsedMs(), plan.project()));
      // 新的一段（含新番茄）一开始，上一段留下的"待开始休息"就作废：
      // 提示只能对应用户还没操作的那一件事
      dropPendingRest();
      refreshQueue();
      if (pomodoro) {
        // PRD 第 9 条：开始番茄自动进入钉住。这是**临时**状态，不写库 ——
        // 写下去就把用户自己按 P 存的偏好覆盖了；要永久改偏好请在设置里改。
        // 钉住只跟着番茄工作段，休息段不特殊对待（他裁的"工作段结束即恢复"）。
        panel.setTemporaryPin(true);
        panel.setPinned(true);
        System.out.println("POMODORO_START " + started.id() + " round=" + round
            + " target_ms=" + plan.targetMs() + " pinned_temporarily=1");
      } else {
        panel.setTemporaryPin(false);
      }
      // 钉住与否交给用户的 P，不再强制：他反馈"一直压在最顶层"碍事，
      // 而"停不下计时"是靠 wake() 抬起面板解决的，不靠默认钉住。
      panel.show();
      refresh();
      onEntriesChanged.run();
    } catch (SQLException e) {
      System.out.println("START_FAILED " + e.getMessage());
    }
  }

  /**
   * 随手记：正在跑的那段计时是挂接对象，没在跑就不挂（entry_id 为 null）。
   * 记笔记**绝不**顺手开一段计时 —— 那是两个动作，合成一个就会污染时长。
   */
  private void submitNote(CaptureParser.NotePlan plan) {
    try {
      String projectId = entries.ensureProject(plan.project());
      String linkTo = selectedId();
      var saved = notes.add(plan.body(), projectId, linkTo, plan.tags());
      System.out.println("NOTE_SAVED " + saved.id() + " linked=" + linkTo
          + " project=" + plan.project() + " tags=" + plan.tags());
      onEntriesChanged.run();
    } catch (SQLException e) {
      System.out.println("NOTE_FAILED " + e.getMessage());
    }
  }

  /**
   * 待办行：只写 todo 表，**不开始计时、也不挂接到正在跑的那段**。
   * 与随手记同一条底线：记一笔和开始一段是两件事。
   */
  private void submitTodo(CaptureParser.TodoPlan plan) {
    try {
      String projectId = entries.ensureProject(plan.project());
      Todo saved = todos.add(plan.title(), projectId, plan.tags());
      System.out.println("TODO_ADDED " + saved.id() + " title=" + saved.title()
          + " project=" + plan.project() + " tags=" + plan.tags());
      refreshTodos();
      onEntriesChanged.run();
    } catch (SQLException | IllegalArgumentException e) {
      System.out.println("TODO_ADD_FAILED " + e.getMessage());
    }
  }

  /** 未完成清单：只在唤起与处理完一条时重读，不进 250ms 的 tick。 */
  public void refreshTodos() {
    try {
      openTodos = todos.pending();
    } catch (SQLException e) {
      System.out.println("TODO_READ_FAILED " + e.getMessage());
      return;
    }
    todoCard.render(openTodos);
    if (openTodos.isEmpty()) {
      todoCard.hide();
    }
  }

  public int todoCount() {
    return openTodos.size();
  }

  /** 标记完成：只改状态，绝不顺手造一条计时条目 —— 那是他明确划的线。 */
  private void completeTodo(Todo t) {
    try {
      todos.complete(t.id());
      System.out.println("TODO_DONE " + t.id());
      afterTodoAction();
    } catch (SQLException | IllegalStateException e) {
      System.out.println("TODO_DONE_FAILED " + t.id() + " " + e.getMessage());
    }
  }

  private void discardTodo(Todo t) {
    try {
      todos.remove(t.id());
      System.out.println("TODO_DISCARDED " + t.id());
      afterTodoAction();
    } catch (SQLException e) {
      System.out.println("TODO_DISCARD_FAILED " + t.id() + " " + e.getMessage());
    }
  }

  /**
   * 完成并补录时间：先落一条 finished 的补录条目（origin=manual_backfill），成功后才把待办标完成。
   * 顺序是有意的：条目被重叠判据拒了时，待办必须还留在清单里 —— 不然事情就凭空消失了。
   */
  private String backfillTodo(Todo t, long ms) {
    String ended = clock.wall();
    String started = java.time.OffsetDateTime.parse(ended)
        .minusNanos(ms * 1_000_000L).format(Clock.WIRE);
    Entry entry;
    try {
      entry = todos.completeWithBackfill(t.id(), entries, started, ended);
    } catch (SQLException | IllegalStateException | IllegalArgumentException e) {
      System.out.println("BACKFILL_FAILED " + t.id() + " " + e.getMessage());
      return e.getMessage();
    }
    System.out.println("BACKFILL_OK entry=" + entry.id() + " todo=" + t.id() + " ms=" + ms
        + " started=" + started);
    afterTodoAction();
    return null;
  }

  private void afterTodoAction() {
    refreshTodos();
    onEntriesChanged.run();
    if (openTodos.isEmpty()) {
      todoCard.hide();
    }
  }

  /** 今天的归属日（08:00 边界）：待办卡的按日抑制用它，不是自然日。 */
  private String checkinToday() {
    return desktimer.core.time.DayAttribution.checkinDate(clock.wall());
  }

  /**
   * 面板上那把 note 键之外，笔记还有第二个入口：L 开卡（M2-c）。
   * 卡与面板都常顶，所以抬起别的窗时先把卡收掉 —— 一次只该看得清一个。
   */
  private void toggleNotes() {
    if (noteCard.showing()) {
      noteCard.hide();
      System.out.println("NOTES_CLOSE");
      return;
    }
    noteCard.show();
    System.out.println("NOTES_OPEN rows=" + noteCard.lineTexts().size());
  }

  /** 自检用：笔记卡（判据取证要走它的 press/typeSearch/lineTexts）。 */
  public NoteCard noteCard() {
    return noteCard;
  }

  /**
   * 计时进行中打开捕获窗：快捷键只会抬面板（那是"当前能操作的那个界面"），
   * 于是跑着的时候反而记不了笔记 —— 面板上的 → 就是那扇门。
   * 面板不收起来：边跑边记，看得见剩余时间。
   */
  private void openCapture() {
    captureShownAt = System.currentTimeMillis();
    capture.show();
    System.out.println("CAPTURE_OPENED_FROM_PANEL session=" + selectedId());
  }

  /** 空格只有一义了：暂停/继续**选中的那一段**。休息另有 R 键（M2-b 推翻 M1-g 那条二义设计）。 */
  private void pauseResume() {
    ActiveSessions.Session s = sessions.selected();
    if (s == null) {
      return;
    }
    try {
      if (s.paused()) {
        entries.resume(s.id());
        s.resume(clock.elapsedMs());
      } else {
        entries.pauseMeasured(s.id(), s.elapsedMs(clock.elapsedMs()));
        s.pause(clock.elapsedMs());
      }
      refresh();
    } catch (SQLException | IllegalStateException e) {
      System.out.println("PAUSE_RESUME_FAILED " + e.getMessage());
    }
  }

  private void selectNext() {
    sessions.selectNext();
    refresh();
  }

  private void selectPrev() {
    sessions.selectPrev();
    refresh();
  }

  private void finish() {
    closeSelected(false);
  }

  private void abort() {
    closeSelected(true);
  }

  /**
   * "结束"这个动作是不是手滑。判据抽成纯函数，是因为它三个输入都能直接问，不用起 FX。
   *
   * @param explicitAbort 用户按的是放弃键（X）⇒ 永远不是误触，那是他明说的作废
   * @param fired         这一段已经到过点 ⇒ 不是误触（到点后按 E 是确认，不是手滑）
   * @param elapsedMs     量下来的时长
   */
  static boolean isMisfire(boolean explicitAbort, boolean fired, long elapsedMs) {
    return !explicitAbort && !fired && elapsedMs < MISFIRE_MS;
  }

  /**
   * 番茄的状态机（M1-g，M2-b 改动后）。四条生命周期，逐条都是用户能感知的：
   *  1. 工作段**正常结束**（不是放弃）⇒ 面板不收起，提示条写「R 开始短休 5m」，
   *     N 是 4 的倍数就换成 20 分钟长休。这一刻**不写任何条目** —— 休息也得他按一键。
   *  2. "待开始的休息"活在这块面板上：面板被他按 H 收起（失焦自动收起**不算**）
   *     或新的一段开始 ⇒ 立刻作废。
   *  3. 休息段结束 ⇒ 回到空闲，**不提示下一段工作**（不循环造条目）。
   *  4. 放弃的工作段不算一个番茄（不进轮次计数），但那一行保留 round 作为证据。
   *
   * 并发下的两条新判据：
   *  - 动作键（空格/E/X/R）只作用在**选中那一段**上，↑↓ 换选中；
   *  - 倒计时"到点"是每段各自的事：普通倒计时到点继续走（它是提醒，14:20 的会开到 14:35
   *    就得记到 14:35），而番茄工作段与休息段到点即结束 —— 它们的定义就是"一个时段"，
   *    留"已触发未确认"那种中间态会让轮次和休息提示都对不上。
   */
  private void closeSelected(boolean aborted) {
    ActiveSessions.Session s = sessions.selected();
    if (s == null) {
      return;
    }
    close(s, aborted, "用户放弃");
  }

  private void close(ActiveSessions.Session s, boolean aborted, String reason) {
    long elapsed = s.elapsedMs(clock.elapsedMs());
    EntryKind kind = s.kind();
    int round = s.round();
    // 误触走的是"作废"那条路（status=aborted），不是"完成"：下面休息提示与自动钉住
    // 都按 asAborted 分支，一段手滑的番茄不该既不进轮次又催他去休息。
    boolean misfire = isMisfire(aborted, s.fired(), elapsed);
    boolean asAborted = aborted || misfire;
    try {
      if (asAborted) {
        entries.abort(s.id(), misfire ? MISFIRE_REASON : reason, elapsed);
      } else {
        entries.finish(s.id(), elapsed);
      }
    } catch (SQLException | IllegalStateException e) {
      System.out.println("CLOSE_FAILED " + e.getMessage());
      return;
    }
    sessions.remove(s.id());
    if (s.id().equals(activeRestId)) {
      activeRestId = null;
      breakWindow.hide();
      if (guard.forced()) {
        guard.forceEnded(!asAborted);
        putSetting("guard.rest_until", "");
      } else guard.recordRest(elapsed);
      saveGuard();
    }
    if (misfire) {
      System.out.println("MISFIRE_ABORTED kind=" + kind.wire() + " elapsed_ms=" + elapsed
          + " 本意是结束，但短于门限且未到点 ⇒ 按放弃入账（理由写进条目）");
    }
    if (kind == EntryKind.POMODORO_WORK && !asAborted) {
      boolean longRest = round % LONG_REST_EVERY == 0;
      lastRound = round;
      pendingRestKind = longRest ? EntryKind.REST_LONG : EntryKind.REST_SHORT;
      pendingRestMs = longRest ? REST_LONG_MS : REST_SHORT_MS;
      deferredOnce = false;
      restPromptPendingDelivery = true;
      panel.setAwaitingChoice(true);
      System.out.println("POMODORO_DONE round=" + round + " next=" + pendingRestKind.wire()
          + " ms=" + pendingRestMs + " 等用户按一键开始，不自动写条目");
    } else if (kind == EntryKind.POMODORO_WORK) {
      dropPendingRest();
      System.out.println("POMODORO_ABORTED round=" + round + " 不算一个番茄（计数不含它）");
    } else if (isRest(kind)) {
      dropPendingRest();
    }
    if (kind == EntryKind.POMODORO_WORK && !sessions.hasPomodoro()) {
      // 自动钉住只跟着番茄工作段：段一结束就恢复库里那份偏好，临时状态不外溢。
      // 休息段不享这个待遇（他裁的"工作段结束即恢复"），否则钉住会一直挂着到休息结束。
      panel.setTemporaryPin(false);
      panel.setPinned(pinnedPreference());
    }
    if (sessions.isEmpty() && pendingRestKind == null) {
      panel.hide();
      panel.render("--:--", "", 0);
      panel.renderRoster(java.util.List.of(
          new TimerPanel.Line("没有进行中的计时", false)));
    } else {
      refresh();   // 面板留着：还有别的段在跑，或是要写"R 开始短休 5m"
    }
    if (kind == EntryKind.POMODORO_WORK && !asAborted) presentRestPrompt();
    onEntriesChanged.run();
  }

  /** 只在允许打扰时投递一次；已经显示后失焦不会抹掉用户待选的下一步。 */
  private void presentRestPrompt() {
    if (!restPromptPendingDelivery || pendingRestKind == null || deferredUntil > 0 || guard.forced()) return;
    if (dndActive() || foregroundState.get().fullscreen()) return;
    restPromptPendingDelivery = false;
    panel.setAwaitingChoice(true);
    panel.show();
    refresh();
  }

  private void dropPendingRest() {
    deferredUntil = 0;
    restPromptPendingDelivery = false;
    panel.setAwaitingChoice(false);
    if (pendingRestKind != null) {
      System.out.println("REST_DROPPED kind=" + pendingRestKind.wire());
    }
    pendingRestKind = null;
    pendingRestMs = 0;
  }

  /** 提示条上那句"空格 开始…"。休息时长写在这里，面板与自检读的是同一份。 */
  /**
   * 提示条上那句"R 开始…"。键名从绑定表读 —— 改过键之后还写"空格"就是假说明（同 KeyMap 那条规则）。
   */
  private String restSuggestion() {
    boolean longRest = pendingRestKind == EntryKind.REST_LONG;
    return TimerPanel.keyName(panel.keys().primary("rest")) + " 开始"
        + (longRest ? "长休 20分" : "短休 5分") + " · 第 " + lastRound + " 个已完成";
  }

  /** 自检读它确认"待开始的休息"到底是什么状态（不暴露可变引用）。 */
  public String pendingRest() {
    return pendingRestKind == null ? "" : pendingRestKind.wire() + ":" + pendingRestMs;
  }

  public int lastCompletedRound() {
    return lastRound;
  }

  /** 库里存的钉住偏好（"默认"就是它）；读不到按不钉住。 */
  private boolean pinnedPreference() {
    try {
      return "true".equals(settings.get("ui.panel.pinned", "false"));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * 一键开始休息：这是用户按出来的，不是程序替他造的条目。
   * 没有待开始的休息时这一键什么都不做（不许按下就凭空多出一段休息）。
   * 休息段**不享受自动钉住** —— 钉住只跟着番茄工作段（M2-b 采纳"工作段结束即恢复"那条裁法）。
   */
  private void startRest() {
    EntryKind kind = pendingRestKind;
    long ms = pendingRestMs;
    if (kind == null) {
      return;
    }
    dropPendingRest();
    beginRest(kind, ms, false);
  }

  private void beginRest(EntryKind kind, long ms, boolean forced) {
    try {
      // 暂停实际工作累计，不把休息覆盖期间写成学习时间；结束后由用户主动恢复。
      long now = clock.elapsedMs();
      java.util.Map<String, Long> pauseDurations = new java.util.LinkedHashMap<>();
      for (var session : sessions.all()) {
        if (!isRest(session.kind()) && !session.paused()) {
          pauseDurations.put(session.id(), session.elapsedMs(now));
        }
      }
      String task = kind == EntryKind.REST_LONG ? "长休" : "短休";
      if (forced) task = "强制休息";
      Entry started = entries.startBreak(task, kind, ms, pauseDurations);
      for (String id : pauseDurations.keySet()) sessions.byId(id).pause(now);
      sessions.add(new ActiveSessions.Session(started.id(), kind, task, ms, 0,
          clock.elapsedMs(), "休息"));
      activeRestId = started.id();
      if (forced) {
        guard.forceStarted();
        putSetting("guard.rest_until", clock.wallNow().plusNanos(ms * 1_000_000L).format(Clock.WIRE));
      }
      if (forced || settingBool("rest.fullscreen", true)) {
        panel.hide();
        breakWindow.show(forced, !deferredOnce, theme);
        breakWindow.render(ms);
      } else panel.show();
      refresh();
      onEntriesChanged.run();
      System.out.println("REST_START " + started.id() + " kind=" + kind.wire() + " ms=" + ms);
    } catch (SQLException e) {
      System.out.println("REST_START_FAILED " + e.getMessage());
    }
  }

  /** 每 250ms 一遍：谁到点谁处理，一次只响一次（判据住在 ActiveSessions.takeDue）。 */
  private void handleDue() {
    for (ActiveSessions.Session s : sessions.takeDue(clock.elapsedMs())) {
      onTargetReached(s);
    }
  }

  private static boolean isRest(EntryKind kind) {
    return kind == EntryKind.REST_SHORT || kind == EntryKind.REST_LONG;
  }

  public void showWorkbench() {
    if (!Platform.isFxApplicationThread()) { Platform.runLater(this::showWorkbench); return; }
    if (guard.forced()) { breakWindow.raise(); return; }
    if (workbench == null) workbench = new WorkbenchWindow(todos, entries, settings,
        this::startTask, this::completeTodo, this::backfillTodo, this::applyPreferences,
        this::resetPositions, theme);
    workbench.show();
  }

  private void startTask(Todo todo) {
    try {
      submit(new CaptureParser.Plan(EntryKind.POMODORO_WORK, 25 * 60_000L, todo.title(),
          todo.tags(), entries.projectName(todo.projectId()), false, ""));
    } catch (SQLException e) { refusal = e.getMessage(); }
  }

  private void deferRest() {
    if (guard.forced() || deferredOnce) return;
    EntryKind kind = pendingRestKind;
    long ms = pendingRestMs;
    if (activeRestId != null) {
      var rest = sessions.byId(activeRestId);
      if (rest == null) return;
      kind = rest.kind(); ms = rest.targetMs();
      close(rest, true, "延后休息");
      if (activeRestId != null) return;
    }
    if (kind == null) return;
    pendingRestKind = kind; pendingRestMs = ms;
    deferredOnce = true; deferredUntil = clock.elapsedMs() + 5 * 60_000L;
    restPromptPendingDelivery = false; panel.setAwaitingChoice(false);
    refusal = "休息已延后 5 分钟，届时提示开始；延后不计入休息";
    panel.show(); refresh();
  }

  private void skipRest() {
    if (guard.forced()) return;
    if (activeRestId != null) {
      var rest = sessions.byId(activeRestId);
      if (rest != null) close(rest, true, "跳过休息，进入下一番茄");
      if (activeRestId != null) return;
    }
    dropPendingRest(); deferredUntil = 0;
    if (nextPomodoroPlan != null) submit(nextPomodoroPlan);
    else { refusal = "没有上一段番茄任务，请选择任务开始"; panel.show(); refresh(); }
  }

  private void emergencyRest() {
    if (!guard.forced() || activeRestId == null) return;
    var rest = sessions.byId(activeRestId);
    if (rest != null) close(rest, true, "用户紧急结束强制休息");
    if (activeRestId == null) {
      emergencyUntil = clock.elapsedMs() + 5 * 60_000L;
      refusal = "强制休息已紧急结束；累计未清零，5 分钟后重新检查。工作段仍暂停";
      panel.show(); refresh();
    }
  }

  private void extendSelected() {
    var selected = sessions.selected();
    if (selected == null || isRest(selected.kind()) || selected.targetMs() == null) return;
    try {
      entries.extendTarget(selected.id(), 5 * 60_000L);
      selected.extendTarget(5 * 60_000L);
      refresh(); onEntriesChanged.run();
    } catch (Exception e) { refusal = "未延期：" + e.getMessage(); }
  }

  public void applyPreferences() {
    panel.setCompact(settingBool("ui.compact", false));
    panel.setAnimations(settingBool("ui.animations", false));
    panel.setAutoHideOnFocusLost(settingBool("ui.panel.auto_hide", true));
  }

  private void guardPulse() {
    if (deferredUntil > 0 && clock.elapsedMs() >= deferredUntil) {
      deferredUntil = 0;
      refusal = "延后时间已到，按休息键开始休息";
      restPromptPendingDelivery = true;
    }
    presentRestPrompt();
    if (!settingBool("guard.enabled", true)) return;
    if (!restoringRest.isEmpty()) {
      String saved = restoringRest; restoringRest = "";
      try {
        long remaining = java.time.Duration.between(clock.wallNow(), OffsetDateTime.parse(saved)).toMillis();
        if (remaining > 0) {
          beginRest(EntryKind.REST_LONG, Math.min(remaining, guardRestMs()), true);
        } else putSetting("guard.rest_until", "");
      } catch (Exception e) { putSetting("guard.rest_until", ""); }
    }
    boolean activeUse = sessions.all().stream().anyMatch(s -> !isRest(s.kind()) && !s.paused())
        || (activeRestId == null && foregroundState.get().fullscreen());
    boolean force = guard.sample(clock.elapsedMs(), idleSource.get(), activeRestId != null, activeUse,
        Math.max(30, Math.min(180, settingLong("guard.limit_min", 90))) * 60_000L);
    if (force && clock.elapsedMs() >= emergencyUntil) beginRest(EntryKind.REST_LONG, guardRestMs(), true);
    if (clock.elapsedMs() - savedGuardAt >= 30_000 || force) saveGuard();
  }

  private long guardRestMs() { return Math.max(5, Math.min(30, settingLong("guard.rest_min", 10))) * 60_000L; }
  private void saveGuard() { putSetting("guard.used_ms", "" + guard.usedMs()); savedGuardAt = clock.elapsedMs(); }
  private String setting(String key, String fallback) { try { return settings.get(key, fallback); } catch(Exception e) { return fallback; } }
  private long settingLong(String key, long fallback) { try { return Long.parseLong(setting(key, "" + fallback)); } catch(Exception e) { return fallback; } }
  private boolean settingBool(String key, boolean fallback) { return Boolean.parseBoolean(setting(key, "" + fallback)); }
  private void putSetting(String key, String value) { try { settings.put(key, value); } catch(SQLException e) { System.err.println("PREFERENCE_SAVE_FAILED " + key + " " + e.getMessage()); } }

  private void resetPositions() {
    for (String key : List.of("ui.capture.bounds", "ui.panel.bounds", "ui.card.bounds", "ui.todo.bounds", "ui.notecard.bounds", "ui.settings.bounds")) putSetting(key, "");
    var bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
    int row = 0;
    for (var node : List.of(capture.root(), panel.root(), card.root(), todoCard.root(), noteCard.root())) {
      var window = node.getScene().getWindow();
      window.setX(bounds.getMinX() + 120); window.setY(bounds.getMinY() + 120 + 100 * row++);
    }
  }

  public void shutdown() {
    Runnable stop = () -> { guardTick.stop(); tick.stop(); breakWindow.hide(); saveGuard(); desktimer.platform.SoundPlayer.stop(); };
    if (Platform.isFxApplicationThread()) stop.run();
    else {
      var latch = new java.util.concurrent.CountDownLatch(1);
      Platform.runLater(() -> { try { stop.run(); } finally { latch.countDown(); } });
      try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  private void refresh() {
    sweepStaleCapture();
    handleDue();
    publishMeasurements();
    renderPanel();
    updateTickState();
  }

  /** 计时到点仍按 250ms 检查；收起窗口只停止绘制，不停止计时和提醒。 */
  private void pulse() {
    sweepStaleCapture();
    handleDue();
    publishMeasurements();
    if (activeRestId != null) {
      var rest = sessions.byId(activeRestId);
      if (rest != null) breakWindow.render(rest.targetMs() - rest.elapsedMs(clock.elapsedMs()));
    }
    if (panel.showing()) {
      renderPanel();
    }
    updateTickState();
  }

  private void publishMeasurements() {
    java.util.Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
    long now = clock.elapsedMs();
    for (var session : sessions.all()) snapshot.put(session.id(), session.elapsedMs(now));
    measuredSnapshot = java.util.Map.copyOf(snapshot);
  }

  private void updateTickState() {
    if (!Platform.isFxApplicationThread()) {
      Platform.runLater(this::updateTickState);
      return;
    }
    boolean needed = !sessions.isEmpty() || capture.showing() || panel.showing();
    if (needed && tick.getStatus() != Animation.Status.RUNNING) {
      tick.play();
    } else if (!needed && tick.getStatus() != Animation.Status.STOPPED) {
      tick.stop();
    }
  }

  private void renderPanel() {
    panel.restActions(pendingRestKind != null || (activeRestId != null && !guard.forced()), !deferredOnce);
    panel.renderRoster(rosterLines());
    // 提示条只教用得上的键：只有一段时不必教方向键，没有待开始休息时不该教他按休息键
    panel.setKeysShown(sessions.size() > 1, pendingRestKind != null);
    ActiveSessions.Session s = sessions.selected();
    if (s == null) {
      panel.render(pendingRestKind == null ? "--:--" : "已完成", (pendingRestKind == null ? "" : restSuggestion() + " · ") + banner(),
          0);
      return;
    }
    long elapsed = s.elapsedMs(clock.elapsedMs());
    String time;
    String state;
    double ratio;
    if (s.targetMs() == null) {
      time = clockText(elapsed);
      state = s.paused() ? "已暂停（正计时）" : "正计时";
      ratio = 0;
    } else {
      long left = Math.max(0, s.targetMs() - elapsed);
      // 倒计时到点后**继续走**：它是提醒不是"这段就这么久"，14:20 的会开到 14:35 就得记到 14:35
      time = elapsed <= s.targetMs() ? clockText(left) : "+" + clockText(elapsed - s.targetMs());
      state = (s.paused() ? "已暂停 · " : s.fired() ? "已到点 · E 结束 / X 放弃 · " : "")
          + "目标 " + clockText(s.targetMs());
      ratio = s.targetMs() == 0 ? 1 : Math.min(1d, (double) elapsed / s.targetMs());
    }
    panel.render(time, s.task() + " · " + state + banner(), ratio);
  }

  /**
   * 列表行：选中用圆点，行首是这一段的剩余（正计时是已走），任务名后带 kind 与轮次。
   * 大数字给的是选中那一段，所以这里必须和它读同一份数据 —— 两处不一致用户就不知道该信谁。
   */
  private java.util.List<TimerPanel.Line> rosterLines() {
    java.util.List<TimerPanel.Line> out = new java.util.ArrayList<>();
    long now = clock.elapsedMs();
    for (ActiveSessions.Cell c : sessions.cells(MAX_ROWS)) {
      if (c instanceof ActiveSessions.Cell.Shown sh) {
        ActiveSessions.Session s = sh.session();
        long elapsed = s.elapsedMs(now);
        String time = s.targetMs() == null ? clockText(elapsed)
            : elapsed <= s.targetMs() ? clockText(s.targetMs() - elapsed)
                : "+" + clockText(elapsed - s.targetMs());
        String tag = switch (s.kind()) {
          case POMODORO_WORK -> "番茄#" + s.round() + " ";
          case REST_SHORT -> "短休 ";
          case REST_LONG -> "长休 ";
          default -> "";
        };
        out.add(new TimerPanel.Line((sh.selected() ? "● " : "  ") + time + "  "
            + tag + s.task() + (s.project() == null ? "" : " @" + s.project())
            + (s.paused() ? "（暂停）" : ""), sh.selected()));
      } else if (c instanceof ActiveSessions.Cell.Fold fold) {
        out.add(new TimerPanel.Line("  ＋" + fold.hidden() + " 段未显示", false));
      }
    }
    if (out.isEmpty()) {
      out.add(new TimerPanel.Line("没有进行中的计时", false));
    }
    return out;
  }

  /**
   * 某一段到点了。三条判据决定它算不算"没提示到你"：面板不可见、前台是全屏应用、勿扰开着。
   * 后两条是 M1-f 补的 —— 以前只看面板可见性，于是"你在投屏演示"和"你把窗口收起来了"
   * 被记成同一件事，而这两种情况该做的动作完全不同。
   *
   * @param s 到点的那一段（每段各自 fired，所以并发下不会互相顶掉）
   */
  private void onTargetReached(ActiveSessions.Session s) {
    desktimer.platform.FullScreenDetector.State fs = foregroundState.get();
    boolean dnd = dndActive();
    // 提示音绑"新短信通知"事件（设置 → 声音 里换音就换，选"无"就静音）；勿扰彻底不出声
    String sound = dnd || (fs.fullscreen() && !settingBool("sound.fullscreen", true)) ? "off"
        : desktimer.platform.SoundPlayer.play(setting("sound.choice", "system"));
    // L3：正常情况到点要把面板抬起来 —— 以前只响一声不抬窗，用户反馈的就是"到点没任何提示"。
    // 全屏下绝不抢焦点（PRD 第 7 条的兜底：那时声音是唯一还能到达他的通道），勿扰也不抬。
    boolean raised = false;
    if (s.kind() == EntryKind.POMODORO_WORK) {
      // 先提交完成与下一步状态，再抬窗口，避免解除工作段临时钉住时把旧面板隐藏。
      close(s, false, null);
      raised = panel.showing() && !fs.fullscreen() && !dnd;
    } else if (!isRest(s.kind()) && !fs.fullscreen() && !dnd) {
      panel.show();
      raised = panel.showing();
    }
    boolean seenOnTheSpot = breakWindow.showing() || (panel.showing() && !fs.fullscreen() && !dnd);
    System.out.println("EXPIRED " + s.id() + " kind=" + s.kind().wire()
        + " panel_visible=" + panel.showing() + " raised=" + raised
        + " fullscreen=" + fs.fullscreen() + " foreground=\"" + fs.foreground() + "\""
        + " dnd=" + dnd + " sound=" + sound + " → "
        + (seenOnTheSpot ? "当场可见" : "记 missed"));
    if (!seenOnTheSpot) {
      markMissed(s.id(), dnd ? "到点时勿扰开着"
          : fs.fullscreen() ? "到点时前台是全屏应用：" + fs.foreground() : "到点时界面不可见");
    }
    // 番茄与休息：触发即结束，不留"已触发未确认"的中间态（工作段 ⇒ 待开始休息，休息 ⇒ 空闲）。
    // 普通倒计时不自动收表，理由见上面那条注释。
    if (isRest(s.kind())) {
      close(s, false, null);
    }
  }

  /** 随手记/待办的挂接对象：并发时挂"面板上选中的那一段"，没在跑就是 null。 */
  private String selectedId() {
    ActiveSessions.Session s = sessions.selected();
    return s == null ? null : s.id();
  }

  private void markMissed(String id, String why) {
    try {
      entries.markMissed(id);
      System.out.println("MISSED " + id + " " + why);
      refreshQueue();
      onEntriesChanged.run();
    } catch (SQLException e) {
      System.out.println("MISSED_MARK_FAILED " + e.getMessage());
    }
  }

  /** 勿扰：一键再要一小时；已经开着就关掉。存的是 ISO 时刻，重启后依然认。 */
  private void toggleDnd() {
    try {
      if (dndActive()) {
        settings.put(DND_KEY, "");
        System.out.println("DND_OFF");
        return;
      }
      String until = java.time.OffsetDateTime.parse(clock.wall()).plusHours(1)
          .format(Clock.WIRE);
      settings.put(DND_KEY, until);
      System.out.println("DND_ON until=" + until);
    } catch (SQLException | RuntimeException e) {
      System.out.println("DND_TOGGLE_FAILED " + e.getMessage());
    }
  }

  private boolean dndActive() {
    try {
      String until = settings.get(DND_KEY, "");
      if (until == null || until.isBlank()) {
        return false;
      }
      return java.time.OffsetDateTime.parse(until).isAfter(OffsetDateTime.parse(clock.wall()));
    } catch (Exception e) {
      System.out.println("DND_READ_FAILED " + e.getMessage());
      return false;
    }
  }

  /** 勿扰还剩多久（提示条用）；没开着返回 null。 */
  private String dndLine() {
    try {
      String until = settings.get(DND_KEY, "");
      if (until == null || until.isBlank() || !dndActive()) {
        return null;
      }
      return "勿扰至 " + until.substring(11, 16);
    } catch (Exception e) {
      return null;
    }
  }

  /** 面板顶部提示条：钉住、冲突与待确认都走这里，不额外开窗。 */
  private String banner() {
    StringBuilder sb = new StringBuilder();
    if (!refusal.isEmpty()) {
      // 当面拒掉的操作必须写出来，否则用户以为已经在跑了
      sb.append(" · ? ").append(refusal);
    }
    if (panel.pinned()) {
      sb.append(" · 已钉住置顶");
    }
    if (sessions.size() > 1) {
      sb.append(" · 共 ").append(sessions.size()).append(" 段在跑");
    }
    if (conflicts > 0) {
      sb.append(" · ⚠ 与 ").append(conflicts).append(" 条同项目计时重叠（已记为你放行的）");
    }
    if (!queue.isEmpty()) {
      sb.append(" · 待确认 ").append(queue.size()).append(" 条（停下来按快捷键看）");
    }
    if (!openTodos.isEmpty()) {
      sb.append(" · 待办 ").append(openTodos.size()).append(" 件");
    }
    String dnd = dndLine();
    if (dnd != null) {
      sb.append(" · ").append(dnd);
    }
    return sb.toString();
  }

  private static String clockText(long ms) {
    long total = ms / 1000;
    return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
  }

  /** 自检与托盘菜单用：结束当前会话。 */
  public void finishActive() {
    closeSelected(false);
  }

  /** 自检与托盘用：**选中的那一段**的 id（只有一段时就是它，与旧语义一致）。 */
  public String activeEntryId() {
    return selectedId();
  }

  /** 自检用：当前是否有进行中的会话。 */
  public boolean hasActiveSession() {
    return !sessions.isEmpty();
  }

  /** 自检用（M2-b）：现在几段在跑。 */
  public int activeCount() {
    return sessions.size();
  }

  /** 自检用：按列表顺序给出所有在跑段的 id，好断言"↑↓ 换的是哪一段"。 */
  public String sessionIds() {
    return sessions.all().stream().map(ActiveSessions.Session::id)
        .reduce((a, b) -> a + "," + b).orElse("");
  }

  /** 自检用：选中那一段是什么 kind（正计时/倒计时/番茄/休息）。 */
  public String selectedKind() {
    ActiveSessions.Session s = sessions.selected();
    return s == null ? "" : s.kind().wire();
  }

  /** 自检用：选中那一段的任务名（并发时要证明"E 收的是哪一段"）。 */
  public String selectedTask() {
    ActiveSessions.Session s = sessions.selected();
    return s == null ? "" : s.task();
  }

  /** 自检用：换选中（等价于按 ↑/↓，但直接给目标 id，不受出厂键影响）。 */
  public boolean selectSession(String id) {
    boolean ok = sessions.select(id);
    refresh();
    return ok;
  }

  /**
   * 心跳用：**这个进程正在跑的会话** id 快照。心跳线程据此决定给哪几行续命 ——
   * 库里状态是 running 但没有会话的行（上一实例留下的）不许再被喂，否则它永远不进对账队列。
   */
  public java.util.List<String> liveSessionIds() {
    return sessions.idsSnapshot();
  }
}
