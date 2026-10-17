package desktimer.ui;

import desktimer.core.time.EntryKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 面板上那一列"正在跑的会话"的运行时影子。不碰 FX，不碰数据库，所以每条判据都能直接问。 */
public final class ActiveSessions {

  /** 列表按加入顺序排列（新加入的排在末尾），下标就是面板上的行号。 */
  private final List<Session> items = new ArrayList<>();

  /** 当前选中行；空列表是 -1。动作键只作用在这一行上。 */
  private int selectedIndex = -1;

  /** 一个活动会话。时间一律用单调时钟的读数算，不用墙上时间（时长是量出来的）。 */
  public static final class Session {

    private final String id;
    private final EntryKind kind;
    private final String task;
    /** 项目名（面板行要用它区分两段并行；null = 没挂项目）。 */
    private final String project;
    private Long targetMs;
    private final int round;
    /** 已经量下来、结算进暂停里的那段时长。 */
    private long accumulatedMs;
    /** 最近一次开始（或继续）计时的单调读数。 */
    private long resumedAtElapsed;
    private boolean paused;
    private boolean fired;

    /**
     * 起点由调用方传入（而不是内部去抓时钟），这样时长与到点判定都是纯函数，能直接断言。
     *
     * @param targetMs  null = 正计时，没有"到点"
     * @param round     番茄轮次；非番茄为 0
     */
    /** 没挂项目的写法（休息段就是这种）：省掉最后一个参数，别逼调用方补 null。 */
    public Session(String id, EntryKind kind, String task, Long targetMs, int round,
        long startedAtElapsed) {
      this(id, kind, task, targetMs, round, startedAtElapsed, null);
    }

    public Session(String id, EntryKind kind, String task, Long targetMs, int round,
        long startedAtElapsed, String project) {
      this.id = Objects.requireNonNull(id, "没有 id 就删不掉也选不上");
      this.kind = Objects.requireNonNull(kind, "kind 决定这一行怎么渲染、算不算番茄");
      this.task = task == null ? "" : task;
      this.project = project == null || project.isBlank() ? null : project;
      this.targetMs = targetMs;
      this.round = round;
      this.accumulatedMs = 0;
      this.resumedAtElapsed = startedAtElapsed;
    }

    public final String id() {
      return id;
    }

    public final EntryKind kind() {
      return kind;
    }

    public final String task() {
      return task;
    }

    /** 项目名，没挂就是 null（面板行尾的 `@项目` 靠它）。 */
    public final String project() {
      return project;
    }

    /** null = 正计时，没有"到点"。 */
    public final Long targetMs() {
      return targetMs;
    }

    public void extendTarget(long ms) {
      if (targetMs == null || ms <= 0) throw new IllegalArgumentException("只有倒计时能延长提醒目标");
      targetMs = Math.addExact(targetMs, ms);
      fired = false;
    }

    /** 番茄轮次；非番茄为 0。 */
    public final int round() {
      return round;
    }

    /** 与 UiController 过去单会话那套算法一致：暂停期间不再增长。 */
    public final long elapsedMs(long nowElapsed) {
      return accumulatedMs + (paused ? 0 : nowElapsed - resumedAtElapsed);
    }

    public final void pause(long nowElapsed) {
      if (paused) {
        return;
      }
      accumulatedMs += nowElapsed - resumedAtElapsed;
      paused = true;
    }

    public final void resume(long nowElapsed) {
      if (!paused) {
        return;
      }
      resumedAtElapsed = nowElapsed;
      paused = false;
    }

    public final boolean paused() {
      return paused;
    }

    /** 已经为"到点"处理过一次（takeDue 返回时就置位，所以每段只响一次）。 */
    public final boolean fired() {
      return fired;
    }

    /** 该按"到点"处理的那三条：有目标、正在跑（没暂停）、还没处理过，且量够了。 */
    private boolean due(long nowElapsed) {
      return targetMs != null && !paused && !fired && elapsedMs(nowElapsed) >= targetMs;
    }

    private void markFired() {
      fired = true;
    }

    @Override
    public final String toString() {
      // 断言失败时要读得出是哪一行：id + kind + 任务 + 目标/轮次 + 当前状态
      return "Session[" + id + " " + kind.wire() + " \"" + task + "\""
          + (targetMs == null ? " 正计时" : " 目标" + targetMs + "ms")
          + (round > 0 ? " 第" + round + "轮" : "")
          + (paused ? " 已暂停" : "")
          + (fired ? " 已到点" : "") + "]";
    }
  }

  /** 面板上的一格：要么是一个会话，要么是"N 段没显示"的折叠指示。 */
  public sealed interface Cell permits Cell.Shown, Cell.Fold {

    record Shown(Session session, boolean selected) implements Cell {}

    record Fold(int hidden) implements Cell {}
  }

  /**
   * @return 加入后的下标。列表按加入顺序排列（新加入的排在末尾），并且**新加的这条成为选中项**。
   */
  public synchronized int add(Session s) {
    Objects.requireNonNull(s, "不能塞一个空会话进来");
    items.add(s);
    // 刚起的那一段拿住动作键：捕获路径是"输入即走"，他敲完紧接着按的那个键，
    // 意图一定是这一段。反过来（不抢）会出事故 —— 番茄在跑时新起 10m 煮饭，
    // 按 E 结束掉的是跑到一半的番茄。想动旧的先按方向键，面板上标着选中的是哪行。
    selectedIndex = items.size() - 1;
    return selectedIndex;
  }

  /** 移除某个 id。@return 是否真的删掉了。删掉的是当前选中项时，选中号前移并保持合法。 */
  public synchronized boolean remove(String id) {
    if (id == null) {
      return false;
    }
    int at = indexOf(id);
    if (at < 0) {
      return false;
    }
    items.remove(at);
    if (at == selectedIndex) {
      // 后面的行会顶上来：选中号留在原位就是"落在同一位置的下一条"；
      // 删的是最后一条 ⇒ 原位已经没了，退回前一条。
      selectedIndex = Math.min(selectedIndex, items.size() - 1);
    } else if (at < selectedIndex) {
      // 选中项没变，只是自己往前挪了一格
      selectedIndex--;
    }
    if (items.isEmpty()) {
      selectedIndex = -1;
    }
    return true;
  }

  public int size() {
    return items.size();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  /** 不可变副本，外部改不动。 */
  public List<Session> all() {
    return List.copyOf(items);
  }

  /**
   * 当前在跑的会话 id 快照。心跳线程在池线程上调它，而增删都在 FX 线程 ⇒ 这里必须自带锁，
   * 与 add/remove 共用同一把锁，避免遍历途中列表被改而漏喂心跳。
   * 其余选择与计时操作仍由 FX 线程独占；这里仅跨线程发布不可变的 id 列表。
   */
  public synchronized List<String> idsSnapshot() {
    List<String> out = new ArrayList<>(items.size());
    for (Session s : items) {
      out.add(s.id());
    }
    return out;
  }

  /** 空列表返回 null。 */
  public Session selected() {
    return selectedIndex < 0 ? null : items.get(selectedIndex);
  }

  /** 空列表返回 -1。 */
  public int selectedIndex() {
    return selectedIndex;
  }

  /** 找不到返回 null。 */
  public Session byId(String id) {
    if (id == null) {
      return null;
    }
    int at = indexOf(id);
    return at < 0 ? null : items.get(at);
  }

  /** @return 是否选中了。选不上时原选中项不动。 */
  public boolean select(String id) {
    int at = id == null ? -1 : indexOf(id);
    if (at < 0) {
      return false;
    }
    selectedIndex = at;
    return true;
  }

  /** 环绕：最后一行 -> 第一行。 */
  public void selectNext() {
    if (items.isEmpty()) {
      return;
    }
    selectedIndex = (selectedIndex + 1) % items.size();
  }

  /** 环绕：第一行 -> 最后一行。 */
  public void selectPrev() {
    if (items.isEmpty()) {
      return;
    }
    selectedIndex = Math.floorMod(selectedIndex - 1, items.size());
  }

  /** 有 kind==POMODORO_WORK 的会话在跑（番茄同时只允许一个的判据）。 */
  public boolean hasPomodoro() {
    return anyKind(EntryKind.POMODORO_WORK);
  }

  /** 番茄或休息段在跑（自动钉住的判据）。 */
  public boolean hasPomodoroOrRest() {
    return anyKind(EntryKind.POMODORO_WORK) || anyKind(EntryKind.REST_SHORT)
        || anyKind(EntryKind.REST_LONG);
  }

  /**
   * 谁这次该按"到点"处理：targetMs 非空、没暂停、还没处理过、且 elapsedMs>=targetMs。
   * 返回的同时把这些会话标记为已处理，所以每段只响一次（tick 每 250ms 跑一遍）。
   */
  public List<Session> takeDue(long nowElapsed) {
    List<Session> due = new ArrayList<>();
    for (Session s : items) {
      if (s.due(nowElapsed)) {
        s.markFired();
        due.add(s);
      }
    }
    return due;
  }

  /**
   * 面板可见区。
   *
   * 判据（已与用户定死）：
   *  - size &lt;= maxRows ⇒ 全部是 Shown，没有 Fold；
   *  - size &gt; maxRows ⇒ 恰好 maxRows 格：maxRows-1 格 Shown + 最后一格 Fold(hidden = size-(maxRows-1))，
   *    Shown 那几格是一个连续窗口且一定包含当前选中项；
   *  - 选中项在窗口之外时窗口滑动到把它包进来（默认顶上对齐，于是 Fold 顺延到腾不下的那一行）；
   *  - 空列表（或一行都放不下）⇒ 空输出。
   *
   * @return 每格带 selected 标记
   */
  public List<Cell> cells(int maxRows) {
    if (items.isEmpty() || maxRows <= 0) {
      return List.of();
    }
    int n = items.size();
    if (n <= maxRows) {
      List<Cell> all = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        all.add(new Cell.Shown(items.get(i), i == selectedIndex));
      }
      return all;
    }
    int shown = maxRows - 1;
    int start = windowStart(n, shown, selectedIndex);
    List<Cell> cells = new ArrayList<>(maxRows);
    for (int i = start; i < start + shown; i++) {
      cells.add(new Cell.Shown(items.get(i), i == selectedIndex));
    }
    cells.add(new Cell.Fold(n - shown));
    return cells;
  }

  /**
   * 折叠时可见窗口的起点：能顶上对齐就顶上对齐（用户认得"最早那几段在上面"），
   * 选中项掉到窗口外面才把窗口滑下去，并且只滑到刚好把它包进来。
   */
  private static int windowStart(int n, int shown, int selected) {
    if (shown <= 0) {
      return 0;
    }
    int maxStart = Math.max(0, n - shown);
    if (selected < 0) {
      return 0;
    }
    return Math.min(maxStart, Math.max(0, selected - shown + 1));
  }

  private boolean anyKind(EntryKind kind) {
    for (Session s : items) {
      if (s.kind() == kind) {
        return true;
      }
    }
    return false;
  }

  private int indexOf(String id) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }
}
