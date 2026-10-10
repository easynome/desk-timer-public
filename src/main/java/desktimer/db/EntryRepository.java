package desktimer.db;

import desktimer.core.time.Clock;
import desktimer.core.time.DayAttribution;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.core.time.Interval;
import desktimer.core.time.Recovery;
import desktimer.core.time.Transitions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 时间条目的读写入口。
 *
 * 两件事在这里做实：
 *  1. 状态转移先读后写并过 {@link Transitions}，非法转移抛错而不是静默改。
 *  2. 重叠判定用 {@link Interval} 在 Java 里算，不在 SQL 里再写一遍语义 ——
 *     数据库触发器只是兜底，主判据是这里，两处语义必须只有一个来源。
 *
 * 所有公开方法都在 {@link DataSource#lock()} 这把锁内跑：sqlite-jdbc 的 Connection
 * 不是线程安全的，而心跳/导出在独立线程、界面在 JavaFX 线程。
 */
public final class EntryRepository {

  private static final String SELECT_COLS =
      "SELECT id, task, status, exclusivity_key, started_at, ended_at, duration_ms, checkin_date,"
          + " last_heartbeat_at, target_ms";

  private final Connection c;
  private final Clock clock;
  private final Object lock;

  public EntryRepository(DataSource ds, Clock clock) {
    this.c = ds.conn();
    this.clock = clock;
    this.lock = ds.lock();
  }

  /** key 只有一个来源：有项目就是 prj:&lt;id&gt;，没有就是 unassigned。不许调用方手填。 */
  public static String exclusivityKey(String projectId) {
    return projectId == null || projectId.isBlank() ? "unassigned" : "prj:" + projectId;
  }

  /** 与 [startIso, endIso) 相交的、同 key 的未作废条目。endIso 传 null 表示"进行中"。 */
  public List<Entry> conflictsFor(String projectId, String startIso, String endIso)
      throws SQLException {
    String key = exclusivityKey(projectId);
    synchronized (lock) {
      OffsetDateTime now = OffsetDateTime.parse(clock.wall());
      Interval candidate = new Interval(OffsetDateTime.parse(startIso),
          endIso == null ? null : OffsetDateTime.parse(endIso));
      List<Entry> hits = new ArrayList<>();
      for (Entry e : byKey(key)) {
        Interval existing = new Interval(OffsetDateTime.parse(e.startedAt()),
            e.endedAt() == null ? null : OffsetDateTime.parse(e.endedAt()));
        if (Interval.intersects(candidate, existing, now)) {
          hits.add(e);
        }
      }
      return hits;
    }
  }

  /**
   * 收表（或改时间）那一刻的相交判据：与 {@link #conflictsFor} 同一套区间语义，
   * 但对端**已经放行**或**已经挂着待确认**的行不算新冲突 —— 那两个状态说明这段重叠
   * 已经在流程里了，同一次并发不该被报第二遍。
   *
   * 为什么主判据在 Java 而触发器还照抄一份：触发器是兜底（外部工具直接改库、以后新增的写路径），
   * 而"该不该送进对账队列"是业务判断，只能在有 {@link Clock} 与 Interval 语义的这里算。
   */
  public List<Entry> unwaivedOverlaps(String id, String startIso, String endIso)
      throws SQLException {
    OffsetDateTime now = OffsetDateTime.parse(clock.wall());
    Interval candidate = new Interval(OffsetDateTime.parse(startIso),
        endIso == null ? null : OffsetDateTime.parse(endIso));
    List<Entry> hits = new ArrayList<>();
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          SELECT_COLS + " FROM time_entry"
              + " WHERE exclusivity_key=? AND id<>? AND overlap_waived=0 AND overlap_pending=0"
              + " AND status NOT IN ('aborted','superseded') ORDER BY started_at")) {
        ps.setString(1, keyOf(id));
        ps.setString(2, id);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            Entry e = map(rs);
            Interval other = new Interval(OffsetDateTime.parse(e.startedAt()),
                e.endedAt() == null ? null : OffsetDateTime.parse(e.endedAt()));
            if (Interval.intersects(candidate, other, now)) {
              hits.add(e);
            }
          }
        }
      }
    }
    return hits;
  }

  /** {@code Entry} 是计时主视图，不带放行位；这一句只服务判据，不往主视图上加列。 */
  private boolean isWaived(String id) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT overlap_waived FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() && rs.getInt(1) != 0;
        }
      }
    }
  }

  private String keyOf(String id) throws SQLException {
    return require(id).exclusivityKey();
  }

  /**
   * 这一行该不该标"重叠待确认"。标上就意味着两件事：导出暂时不认它（与 superseded 同类，
   * 它还不是已定的数据），以及对账队列里有它 —— 由他选"两段都算（放行）"还是"这段作废"。
   *
   * 自己已经放行过的行不再问一遍：并发起段那一刻他认过一次（{@code overlap_waived=1} 在新起的那行上），
   * 收表时它对端那个"未放行"的行不需要重复确认 —— 一对相交只要有一边认过就是合法形状。
   *
   * <b>绝不代替用户写 overlap_waived</b>：那个字段的语义是"用户认过这段重叠"，是导出契约的凭据。
   */
  private int pendingFlag(String id, String startIso, String endIso) throws SQLException {
    if (isWaived(id)) {
      return 0;
    }
    List<Entry> hits = unwaivedOverlaps(id, startIso, endIso);
    if (hits.isEmpty()) {
      return 0;
    }
    System.out.println("OVERLAP_PENDING entry=" + id + " 与 " + hits.size() + " 段相交未放行："
        + hits.stream().map(Entry::id).toList() + " ⇒ 送对账队列，导出暂不认这一行");
    return 1;
  }

  public Entry start(String task, EntryKind kind, String projectId, boolean overlapWaived)
      throws SQLException {
    return start(task, kind, projectId, null, overlapWaived);
  }

  /** targetMs 只有倒计时才有；正计时传 null。 */
  public Entry start(String task, EntryKind kind, String projectId, Long targetMs,
      boolean overlapWaived) throws SQLException {
    return insert(kind, task, projectId, targetMs, null, overlapWaived);
  }

  /**
   * 番茄工作段。kind 固定 `pomodoro_work`，**轮次在开始这一刻就写**：面板一抬头要显示
   * "第几个"，而中途断电/休眠留下的 interrupted 行也得带轮次，否则账断在半路。
   */
  public Entry startPomodoro(String task, String projectId, long targetMs, int round,
      boolean overlapWaived) throws SQLException {
    return insert(EntryKind.POMODORO_WORK, task, projectId, targetMs, round, overlapWaived);
  }

  /**
   * 某个归属日内**已完成**的番茄数。放弃与中断都不计入（PRD：中途放弃不算一个番茄），
   * 但那些行仍带着 round —— 所以同一个轮次号可能出现两行，消费方要按 status 过滤。
   */
  public int completedPomodoros(String checkinDate) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT COUNT(*) FROM time_entry WHERE kind='pomodoro_work'"
              + " AND status='finished' AND checkin_date=?")) {
        ps.setString(1, checkinDate);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getInt(1) : 0;
        }
      }
    }
  }

  private Entry insert(EntryKind kind, String task, String projectId, Long targetMs,
      Integer round, boolean overlapWaived) throws SQLException {
    synchronized (lock) {
      String id = UUID.randomUUID().toString();
      String started = clock.wall();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO time_entry (id, kind, status, origin, task, project_id, exclusivity_key,"
              + " started_at, checkin_date, overlap_waived, target_ms, pomodoro_round,"
              + " created_at, updated_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        ps.setString(1, id);
        ps.setString(2, kind.wire());
        ps.setString(3, EntryStatus.RUNNING.wire());
        ps.setString(4, "timer");
        ps.setString(5, task);
        ps.setString(6, projectId);
        ps.setString(7, exclusivityKey(projectId));
        ps.setString(8, started);
        ps.setString(9, DayAttribution.checkinDate(started));
        ps.setInt(10, overlapWaived ? 1 : 0);
        if (targetMs == null) {
          ps.setNull(11, java.sql.Types.INTEGER);
        } else {
          ps.setLong(11, targetMs);
        }
        if (round == null) {
          ps.setNull(12, java.sql.Types.INTEGER);
        } else {
          ps.setInt(12, round);
        }
        ps.setString(13, started);
        ps.setString(14, started);
        ps.executeUpdate();
      }
      // 回读而不是手拼返回值：调用方拿到的视图必须与库里一致
      return require(id);
    }
  }

  /**
   * 补录一段"当时忘了开计时"的条目：一写入就是 finished，origin=manual_backfill。
   *
   * 时长来自用户敲的那个数字，进程内的单调时钟对这段没有任何依据 ⇒ duration_ms 只能由墙上
   * 时间算；{@code end_precision='user'} 就是"这时长是人给的、不是量出来的"那个标记。
   * kind 记 count_up：补录只回答"这段多长"，不回答"本来打算多久"。
   *
   * 与同项目已有区间相交就整条拒，绝不写 overlap_waived=1：那个字段的语义是"用户当场认过
   * 这段重叠"，补录这一刻没人问过他，写下去外部消费者就再也分不出"静默造的"和"认过的"。
   * 库里的触发器是第二道闸，这里只是把它的 SQLITE_ERROR 翻译成人话。
   */
  public Entry finishBackfill(String task, String projectId, String startedAt, String endedAt)
      throws SQLException {
    synchronized (lock) {
      OffsetDateTime start = OffsetDateTime.parse(startedAt);
      OffsetDateTime end = OffsetDateTime.parse(endedAt);
      long ms = java.time.Duration.between(start, end).toMillis();
      if (ms <= 0) {
        throw new IllegalArgumentException("补录的时长必须为正，收到 " + ms + " 毫秒");
      }
      List<Entry> clashes = conflictsFor(projectId, startedAt, endedAt);
      if (!clashes.isEmpty()) {
        Entry first = clashes.get(0);
        throw new IllegalStateException("与 " + clashes.size()
            + " 条同项目计时重叠（例如「" + first.task() + "」" + first.startedAt() + "→"
            + (first.endedAt() == null ? "进行中" : first.endedAt())
            + "）：先把那几段处理掉，补录不替你认重叠");
      }
      String id = UUID.randomUUID().toString();
      String now = clock.wall();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO time_entry (id, kind, status, origin, task, project_id, exclusivity_key,"
              + " started_at, ended_at, duration_ms, end_precision, checkin_date,"
              + " overlap_waived, created_at, updated_at)"
              + " VALUES (?,?,?,'manual_backfill',?,?,?,?,?,?,'user',?,0,?,?)")) {
        ps.setString(1, id);
        ps.setString(2, EntryKind.COUNT_UP.wire());
        ps.setString(3, EntryStatus.FINISHED.wire());
        ps.setString(4, task);
        ps.setString(5, projectId);
        ps.setString(6, exclusivityKey(projectId));
        ps.setString(7, startedAt);
        ps.setString(8, endedAt);
        ps.setLong(9, ms);
        // 归日按 started_at：补录的是"那天下午干的事"，不是"今天按下的这一下"
        ps.setString(10, DayAttribution.checkinDate(startedAt));
        ps.setString(11, now);
        ps.setString(12, now);
        ps.executeUpdate();
      }
      return require(id);
    }
  }

  public void pause(String id) throws SQLException {
    move(id, EntryStatus.PAUSED);
  }

  public void resume(String id) throws SQLException {
    move(id, EntryStatus.RUNNING);
  }

  /** 用户主动结束：时长由进程内单调时钟算出，结束时间取墙上时间。 */
  public void finish(String id, long durationMs) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), EntryStatus.FINISHED);
      String now = clock.wall();
      // 相交是这一刻才成形的（两段都在跑时谁都没有 ended_at），所以判据也必须做在这一刻。
      // needs_review 一律不碰：进队列靠 overlap_pending 自己说话，一个标记只表示一件事
      int pending = pendingFlag(id, e.startedAt(), now);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status='finished', ended_at=?, duration_ms=?,"
              + " end_precision='user', overlap_pending=?, updated_at=? WHERE id=?")) {
        ps.setString(1, now);
        ps.setLong(2, durationMs);
        ps.setInt(3, pending);
        ps.setString(4, now);
        ps.setString(5, id);
        ps.executeUpdate();
      }
    }
  }

  public void abort(String id, String reason, long durationMs) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), EntryStatus.ABORTED);
      String now = clock.wall();
      // 作废的段不参与重叠（触发器与 byKey 都这么认），所以 pending 一律清零
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status='aborted', ended_at=?, duration_ms=?, aborted_reason=?,"
              + " end_precision='user', overlap_pending=0, updated_at=? WHERE id=?")) {
        ps.setString(1, now);
        ps.setLong(2, durationMs);
        ps.setString(3, reason);
        ps.setString(4, now);
        ps.setString(5, id);
        ps.executeUpdate();
      }
    }
  }

  public int touchHeartbeats() throws SQLException {
    synchronized (lock) {
      String now = clock.wall();
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET last_heartbeat_at=?, updated_at=?"
              + " WHERE status IN ('running','paused')")) {
        ps.setString(1, now);
        ps.setString(2, now);
        return ps.executeUpdate();
      }
    }
  }

  /**
   * 启动时对账：把"有开始没结束"的条目交给 {@link Recovery} 判定。
   * 只有需要用户确认的（RECONCILE）会落库并返回；间隙小于宽限期的原样继续跑。
   */
  public List<Entry> reconcileRunning(Recovery.Cause cause) throws SQLException {
    synchronized (lock) {
      String now = clock.wall();
      List<Entry> reconciled = new ArrayList<>();
      for (Entry e : unfinished()) {
        Recovery.Decision d = Recovery.decide(e.startedAt(), lastHeartbeat(e.id()), now, cause);
        if (d.action() != Recovery.Action.RECONCILE) {
          continue;
        }
        // 断电/休眠对账同样要过这一关：建议的 ended_at 也可能把两段凑成相交
        int pending = pendingFlag(e.id(), e.startedAt(), d.suggestedEnd());
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE time_entry SET status='interrupted', ended_at=?, duration_ms=?,"
                + " end_precision=?, cause=?, needs_review=1, overlap_pending=?, updated_at=?"
                + " WHERE id=?")) {
          ps.setString(1, d.suggestedEnd());
          ps.setLong(2, d.durationMs());
          ps.setString(3, d.precision() == null ? null : d.precision().wire());
          ps.setString(4, d.cause().name().toLowerCase());
          ps.setInt(5, pending);
          ps.setString(6, now);
          ps.setString(7, e.id());
          ps.executeUpdate();
        }
        reconciled.add(new Entry(e.id(), e.task(), EntryStatus.INTERRUPTED, e.exclusivityKey(),
            e.startedAt(), d.suggestedEnd(), d.durationMs(), e.checkinDate(), e.lastHeartbeatAt(),
            e.targetMs()));
      }
      return reconciled;
    }
  }

  /** 对账后用户确认或改时间。改完的时间同样要过相交判据 —— 他可能把两段改到重叠。*/
  public void confirmRecovery(String id, String endedAtIso, long durationMs) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), EntryStatus.FINISHED);
      String now = clock.wall();
      int pending = pendingFlag(id, e.startedAt(), endedAtIso);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status='finished', ended_at=?, duration_ms=?,"
              + " end_precision='user', needs_review=0, missed=0, overlap_pending=?,"
              + " updated_at=? WHERE id=?")) {
        ps.setString(1, endedAtIso);
        ps.setLong(2, durationMs);
        ps.setInt(3, pending);
        ps.setString(4, now);
        ps.setString(5, id);
        ps.executeUpdate();
      }
    }
  }

  /** 丢弃 = 这段不作数。作废但保留建议的 ended_at/duration_ms，证据不许被"丢弃"动作销毁。 */
  public void discard(String id, String reason) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), EntryStatus.ABORTED);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status='aborted', aborted_reason=?, needs_review=0, missed=0,"
              + " overlap_pending=0, updated_at=? WHERE id=?")) {
        ps.setString(1, reason);
        ps.setString(2, clock.wall());
        ps.setString(3, id);
        ps.executeUpdate();
      }
    }
  }

  /**
   * 记下"用户已经被告知重叠、并且选择继续"。
   * 重叠在开始那一刻只能警告（面板提示条），放行与否必须落到行上 ——
   * 否则外部消费者读到相交区间时无法区分"程序静默造的"和"用户认过的"，
   * 而它的契约要求前者必须响亮失败（见 docs/verify-contract.mjs）。
   *
   * 顺带清 {@code overlap_pending}：这一句就是他站在对账卡前回答的"两段都算"，
   * 行也由此重新进导出（pending 的行是不导出的）。
   */
  public void waiveOverlap(String id) throws SQLException {
    synchronized (lock) {
      require(id);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET overlap_waived=1, overlap_pending=0, updated_at=? WHERE id=?")) {
        ps.setString(1, clock.wall());
        ps.setString(2, id);
        ps.executeUpdate();
      }
    }
  }

  /**
   * 他对重叠的另一种回答："这一段不作数"。用 {@code superseded} 而不是 {@code aborted}：
   * superseded 的行不进导出，那对相交就从头不存在，契约不会被"作废"这件事弄红；
   * 而 aborted 是会导出的，两条 aborted/finished 相交仍算违例。
   * 行本身留着（ended_at/duration_ms 都是证据），并用 {@code superseded_by} 记下被谁顶掉。
   */
  public void supersedeOverlap(String id) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), EntryStatus.SUPERSEDED);
      List<Entry> hits = unwaivedOverlaps(id, e.startedAt(), e.endedAt());
      String by = hits.isEmpty() ? null : hits.get(0).id();
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status='superseded', superseded_by=?, overlap_pending=0,"
              + " needs_review=0, missed=0, updated_at=? WHERE id=?")) {
        ps.setString(1, by);
        ps.setString(2, clock.wall());
        ps.setString(3, id);
        ps.executeUpdate();
      }
      System.out.println("OVERLAP_SUPERSEDED entry=" + id + " by=" + by + " ⇒ 这段不再导出，重叠消失");
    }
  }

  /** 到点时屏幕上没有任何可见界面 ⇒ 记 missed，下次唤起补给用户看。 */
  public void markMissed(String id) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET missed=1, updated_at=? WHERE id=?")) {
        ps.setString(1, clock.wall());
        ps.setString(2, id);
        ps.executeUpdate();
      }
    }
  }

  /** 需要用户过目的两条来源：对账打上的 needs_review，和到点没提示到的 missed。 */
  public List<ReviewItem> reviewQueue() throws SQLException {
    synchronized (lock) {
      List<ReviewItem> out = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT id, task, status, started_at, ended_at, duration_ms, end_precision, cause,"
              + " missed, needs_review, overlap_pending FROM time_entry"
              + " WHERE needs_review=1 OR missed=1 OR overlap_pending=1 ORDER BY started_at")) {
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.add(new ReviewItem(rs.getString("id"), rs.getString("task"),
                EntryStatus.parse(rs.getString("status")), rs.getString("started_at"),
                rs.getString("ended_at"), longOrNull(rs, "duration_ms"),
                rs.getString("end_precision"), rs.getString("cause"),
                rs.getInt("missed") != 0, rs.getInt("needs_review") != 0,
                rs.getInt("overlap_pending") != 0));
          }
        }
      }
      return out;
    }
  }

  /** "知道了"：只清标记，不动状态与时区 —— 已结束的条目没有可改的了。 */
  public void acknowledge(String id) throws SQLException {
    synchronized (lock) {
      require(id);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET needs_review=0, missed=0, updated_at=? WHERE id=?")) {
        ps.setString(1, clock.wall());
        ps.setString(2, id);
        ps.executeUpdate();
      }
    }
  }

  public List<Entry> unfinished() throws SQLException {
    synchronized (lock) {
      List<Entry> out = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement(
          SELECT_COLS + " FROM time_entry WHERE status IN ('running','paused') ORDER BY started_at")) {
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.add(map(rs));
          }
        }
      }
      return out;
    }
  }

  public Optional<Entry> find(String id) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          SELECT_COLS + " FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
      }
    }
  }

  /**
   * 按名字取或建项目，返回 id；name 为空返回 null。
   * 解析器给的是 `@Portal` 这种名字，不解析成 id 就会静默丢掉用户输入。
   */
  public String ensureProject(String name) throws SQLException {
    if (name == null || name.isBlank()) {
      return null;
    }
    String trimmed = name.trim();
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement("SELECT id FROM project WHERE name=?")) {
        ps.setString(1, trimmed);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
      String id = UUID.randomUUID().toString();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT OR IGNORE INTO project (id,name,archived,created_at) VALUES (?,?,0,?)")) {
        ps.setString(1, id);
        ps.setString(2, trimmed);
        ps.setString(3, clock.wall());
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT id FROM project WHERE name=?")) {
        ps.setString(1, trimmed);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : id;
        }
      }
    }
  }

  /** 给条目挂标签，标签不存在就建。用户敲的 #标签 不许在半路消失。 */
  public void attachTags(String entryId, List<String> tags) throws SQLException {
    if (tags == null || tags.isEmpty()) {
      return;
    }
    synchronized (lock) {
      Tags.link(c, clock, "entry_tag", "entry_id", entryId, tags);
    }
  }

  private void move(String id, EntryStatus to) throws SQLException {
    synchronized (lock) {
      Entry e = require(id);
      Transitions.require(e.status(), to);
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE time_entry SET status=?, updated_at=? WHERE id=?")) {
        ps.setString(1, to.wire());
        ps.setString(2, clock.wall());
        ps.setString(3, id);
        ps.executeUpdate();
      }
    }
  }

  private Entry require(String id) throws SQLException {
    return find(id).orElseThrow(() -> new SQLException("找不到条目 " + id));
  }

  private List<Entry> byKey(String exclusivityKey) throws SQLException {
    List<Entry> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
        SELECT_COLS + " FROM time_entry WHERE exclusivity_key=?"
            + " AND status NOT IN ('aborted','superseded') ORDER BY started_at")) {
      ps.setString(1, exclusivityKey);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  private String lastHeartbeat(String id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT last_heartbeat_at FROM time_entry WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static Entry map(ResultSet rs) throws SQLException {
    return new Entry(
        rs.getString("id"),
        rs.getString("task"),
        EntryStatus.parse(rs.getString("status")),
        rs.getString("exclusivity_key"),
        rs.getString("started_at"),
        rs.getString("ended_at"),
        longOrNull(rs, "duration_ms"),
        rs.getString("checkin_date"),
        rs.getString("last_heartbeat_at"),
        longOrNull(rs, "target_ms"));
  }

  /** wasNull() 只对最后一次读取有效，所以取值和判空必须贴在一起。 */
  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
