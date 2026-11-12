package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.DayAttribution;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.core.time.Recovery;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryRepositoryTest {

  private DataSource ds;
  private EntryRepository repo;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    repo = new EntryRepository(ds, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void startWritesRunningRowWithAttributedDate() throws Exception {
    Entry e = repo.start("写周报", EntryKind.COUNT_DOWN, null, false);
    Entry back = repo.find(e.id()).orElseThrow();
    assertEquals(EntryStatus.RUNNING, back.status());
    assertEquals("unassigned", back.exclusivityKey());
    assertEquals(DayAttribution.checkinDate(back.startedAt()), back.checkinDate(),
        "checkin_date 必须由 started_at 算出来");
    assertTrue(back.startedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"),
        "落库必须是定宽时间戳：" + back.startedAt());
  }

  @Test
  void lifecycleTransitionsAndIllegalResurrect() throws Exception {
    Entry e = repo.start("回邮件", EntryKind.COUNT_UP, null, false);
    repo.pause(e.id());
    assertEquals(EntryStatus.PAUSED, repo.find(e.id()).orElseThrow().status());
    repo.resume(e.id());
    assertEquals(EntryStatus.RUNNING, repo.find(e.id()).orElseThrow().status());
    repo.finish(e.id(), 60000);
    Entry done = repo.find(e.id()).orElseThrow();
    assertEquals(EntryStatus.FINISHED, done.status());
    assertEquals(60000L, done.durationMs());
    assertThrows(IllegalStateException.class, () -> repo.resume(e.id()));
    assertTrue(repo.unfinished().isEmpty());
  }

  @Test
  void abortKeepsReason() throws Exception {
    String projectId = repo.ensureProject("Portal");
    Entry e = repo.start("方案评审", EntryKind.POMODORO_WORK, projectId, false);
    repo.abort(e.id(), "中途放弃", 30000);
    Entry aborted = repo.find(e.id()).orElseThrow();
    assertEquals(EntryStatus.ABORTED, aborted.status());
    assertEquals("prj:" + projectId, aborted.exclusivityKey(), "key 必须由项目派生");
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "SELECT aborted_reason FROM time_entry WHERE id=?")) {
      ps.setString(1, e.id());
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertEquals("中途放弃", rs.getString(1));
      }
    }
  }

  @Test
  void ensureProjectIsIdempotentAndTagsSurvive() throws Exception {
    String first = repo.ensureProject("Portal");
    String again = repo.ensureProject("Portal");
    assertEquals(first, again, "同名项目不许建第二条");
    assertNull(repo.ensureProject(null));
    assertNull(repo.ensureProject("  "));

    Entry e = repo.start("评审演练", EntryKind.COUNT_DOWN, first, false);
    repo.attachTags(e.id(), List.of("评审", " 评审 ", "", "并发"));
    try (Statement s = ds.conn().createStatement();
         var rs = s.executeQuery("SELECT count(*) FROM entry_tag WHERE entry_id='" + e.id() + "'")) {
      rs.next();
      assertEquals(2, rs.getInt(1), "重复与空白标签都不该产生新行");
    }
    repo.attachTags(e.id(), List.of("评审"));
    try (Statement s = ds.conn().createStatement();
         var rs = s.executeQuery("SELECT count(*) FROM entry_tag WHERE entry_id='" + e.id() + "'")) {
      rs.next();
      assertEquals(2, rs.getInt(1), "重复挂载必须是幂等的");
    }
  }

  @Test
  void conflictsRespectKeyAndIgnoreAborted() throws Exception {
    // 用显式时间窗建样本：仓储写的是"现在"，秒级粒度下同秒起止会退化成零长区间
    seed("k1", "unassigned", "2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00", "finished");
    seed("k2", "prj:x", "2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00", "finished");
    seed("k3", "unassigned", "2026-10-06T14:10:00+08:00", "2026-10-06T14:20:00+08:00", "aborted");

    List<Entry> hits = repo.conflictsFor(null,
        "2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00");
    assertEquals(List.of("k1"), hits.stream().map(Entry::id).toList(),
        "同 key 的未作废条目才算冲突；别的 key 与 aborted 都不该报");
    assertEquals(List.of("k2"), repo.conflictsFor("x",
        "2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00")
        .stream().map(Entry::id).toList());
  }

  @Test
  void runningSegmentCountsAsAConflictForAFreshOne() throws Exception {
    // 面板并发的真实形状：甲在跑（ended_at 为 NULL），此刻起乙 —— 乙的 started_at 与 now 同一秒。
    // 把"进行中"的右端解析成 now 会让乙变成零长区间，冲突数为 0 ⇒ 放行没写进库 ⇒
    // 两段都收表后导出里是一对没标 overlap_waived 的相交（2026-10-08 真实库上红过一次）
    Entry running = repo.start("煮饭", EntryKind.COUNT_UP, null, false);
    String now = clock.wall();
    assertEquals(List.of(running.id()), repo.conflictsFor(null, now, null)
        .stream().map(Entry::id).toList(),
        "两段都在跑就是此刻并行，必须报冲突（放行由调用方写进行）");
    // 往回补一段、窗口盖住正在跑的那段：闭区间对 open 区间要比到对方的"当下右端"
    String fiveMinAgo = OffsetDateTime.parse(now).minusMinutes(5).format(Clock.WIRE);
    String tenMinAgo = OffsetDateTime.parse(now).minusMinutes(10).format(Clock.WIRE);
    String oneMinAgo = OffsetDateTime.parse(now).minusMinutes(1).format(Clock.WIRE);
    seedOpen("r-unassigned", "unassigned", fiveMinAgo);
    assertTrue(repo.conflictsFor(null, tenMinAgo, oneMinAgo).stream()
        .anyMatch(x -> x.id().equals("r-unassigned")), "补录的窗口盖住了一段在跑的");
    // 首尾相接仍不算重叠（那条规矩不因 open 而变）：补录到 running 的那一刻为止
    assertTrue(repo.conflictsFor(null, tenMinAgo, fiveMinAgo).stream()
        .noneMatch(x -> x.id().equals("r-unassigned")), "接到起点是相邻，不是重叠");
  }

  /**
   * A 方案：启动时**任何**没结束的行都要摊开给用户，包括心跳还很新的一条。
   *
   * 原来这里断言的是"宽限期内的条目不许被作废"——那条断言把一个不存在的前提当成了事实：
   * `reconcileRunning` 只在启动时跑，而启动的那一刻是新进程，它手里没有任何会话对象可以
   * "接着跑"这一段。于是 `CONTINUE_RUNNING` 的分支让行原样留在 `running`，又被心跳线程按
   * status 一直喂着（见 `heartbeatOnlyFeedsRowsWithALiveSession`），结果：既不结束、不进队列、
   * 也没有窗口 —— 2026-10-11 真实库上跑了两小时二十分钟才被他手动抓出来。
   */
  @Test
  void startupReconcilesEveryUnfinishedRowBecauseNothingCanContinueIt() throws Exception {
    Entry fresh = repo.start("刚开的", EntryKind.COUNT_UP, null, false);
    Entry stale = repo.start("断电前开的", EntryKind.COUNT_UP, null, false);
    String old = OffsetDateTime.parse(clock.wall()).minusMinutes(10).format(Clock.WIRE);
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "UPDATE time_entry SET started_at=?, last_heartbeat_at=? WHERE id=?")) {
      ps.setString(1, old);
      ps.setString(2, old);
      ps.setString(3, stale.id());
      ps.executeUpdate();
    }

    List<Entry> reconciled = repo.reconcileRunning(Recovery.Cause.SUSPEND);
    assertEquals(2, reconciled.size(), "启动时没有主人 ⇒ 两条都得摊开，不论心跳新旧");
    Entry r = reconciled.get(0);
    assertEquals(stale.id(), r.id());
    assertEquals(EntryStatus.INTERRUPTED, r.status());
    assertEquals(old, r.endedAt(), "结束时间必须等于最后心跳");
    assertEquals(0L, r.durationMs());
    assertEquals(EntryStatus.INTERRUPTED, repo.find(fresh.id()).orElseThrow().status(),
        "心跳还新的那条也不许留在 running：留着它就是僵尸行");
    assertEquals(0, repo.unfinished().size(), "reconcileRunning 跑完库里不该再有没主人的活行");

    String confirmedEnd = OffsetDateTime.parse(old).plusMinutes(4).format(Clock.WIRE);
    repo.confirmRecovery(r.id(), confirmedEnd, 240000);
    Entry confirmed = repo.find(r.id()).orElseThrow();
    assertEquals(EntryStatus.FINISHED, confirmed.status());
    assertEquals(confirmedEnd, confirmed.endedAt());
    try (Statement s = ds.conn().createStatement();
         var rs = s.executeQuery("SELECT needs_review, end_precision FROM time_entry WHERE id='"
             + r.id() + "'")) {
      rs.next();
      assertEquals(0, rs.getInt(1), "确认后不该再挂着待审标记");
      assertEquals("user", rs.getString(2), "用户确认过的结束时间不该再标 heartbeat 精度");
    }
  }

  /**
   * 心跳必须**只喂有主人的行**。
   *
   * 原来它是按库里的 status 刷（`WHERE status IN ('running','paused')`），这正好和 A 方案的前一半
   * 组成一个死循环：上一实例留下的行没人认领 ⇒ 它状态还是 running ⇒ 心跳线程每秒给它续命 ⇒
   * 下次启动"间隙小于宽限"⇒ 继续不摊开。2026-10-11 那条跑了两小时多的僵尸行就是这么来的。
   */
  @Test
  void heartbeatOnlyFeedsRowsWithALiveSession() throws Exception {
    Entry owned = repo.start("面板上在跑", EntryKind.COUNT_UP, null, false);
    Entry zombie = repo.start("上一实例留下的", EntryKind.COUNT_UP, null, false);
    Entry done = repo.start("已结束的", EntryKind.COUNT_UP, null, false);
    repo.finish(done.id(), 1000);
    Thread.sleep(1100);

    assertEquals(1, repo.touchHeartbeats(List.of(owned.id())),
        "只有既没结束、又有会话主人的才被盖心跳");
    assertNotNull(repo.find(owned.id()).orElseThrow().lastHeartbeatAt());
    assertNull(repo.find(zombie.id()).orElseThrow().lastHeartbeatAt(),
        "没有会话的行一格都不许刷——给它续命就是造僵尸");
    assertNull(repo.find(done.id()).orElseThrow().lastHeartbeatAt(),
        "finish 之后心跳列必须保持为空，否则对账会拿它当存活证据");

    String zombieBeat = OffsetDateTime.parse(clock.wall()).minusMinutes(10).format(Clock.WIRE);
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "UPDATE time_entry SET last_heartbeat_at=? WHERE id=?")) {
      ps.setString(1, zombieBeat);
      ps.setString(2, zombie.id());
      ps.executeUpdate();
    }
    assertEquals(0, repo.touchHeartbeats(List.of()), "一个会话都没有 ⇒ 不该刷到任何一行");
    assertEquals(zombieBeat, repo.find(zombie.id()).orElseThrow().lastHeartbeatAt(),
        "空主人清单不许改动僵尸行的心跳，否则它又活过来了");
  }

  /**
   * 重叠在真实路径上不是靠 INSERT 进来的：先开始（ended_at 为空，触发器管不着），
   * 之后才结束（UPDATE 不触发）。所以"用户被警告过并放行"这件事只能记在这一列上，
   * 否则外部消费者读到相交区间时无法区分静默产生与用户认可（契约要求前者响亮失败）。
   */
  @Test
  void waiveOverlapIsRecordedOnTheRow() throws Exception {
    Entry e = repo.start("与别人重叠的一段", EntryKind.COUNT_UP, null, false);
    assertEquals(0, waived(e.id()));
    repo.waiveOverlap(e.id());
    assertEquals(1, waived(e.id()));
    assertThrows(Exception.class, () -> repo.waiveOverlap("no-such-id"));
  }

  private int waived(String id) throws Exception {
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "SELECT overlap_waived FROM time_entry WHERE id=?")) {
      ps.setString(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  @Test
  void unknownIdFailsLoudly() {
    assertThrows(Exception.class, () -> repo.pause("no-such-id"));
  }

  // 触发器兜底那一层由 TimeEntryOverlapTest 负责，这里不再重复一份证据。

  /** 造一条"还在跑"的行：ended_at 必须是 NULL，所以借不到 {@link #seed}（它把 updated_at 绑到 end）。 */
  private void seedOpen(String id, String key, String start) throws Exception {
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
            + "end_precision,checkin_date,overlap_waived,created_at,updated_at)"
            + " VALUES (?, 'count_up', 'running', 'timer', '样本', ?, ?, 'user', '2026-10-06', 0, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, key);
      ps.setString(3, start);
      ps.setString(4, start);
      ps.setString(5, start);
      ps.executeUpdate();
    }
  }

  private void seed(String id, String key, String start, String end, String status)
      throws Exception {
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,ended_at,"
            + "duration_ms,end_precision,checkin_date,overlap_waived,created_at,updated_at)"
            + " VALUES (?, 'count_up', ?, 'timer', '样本', ?, ?, ?, 1800000, 'user',"
            + " '2026-10-06', 0, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, status);
      ps.setString(3, key);
      ps.setString(4, start);
      ps.setString(5, end);
      ps.setString(6, start);
      ps.setString(7, end);
      ps.executeUpdate();
    }
  }
}
