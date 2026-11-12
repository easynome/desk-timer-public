package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.core.time.Recovery;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 对账队列的读写面：谁能进队列、处理完怎么出去、出去了证据还在不在。
 *
 * "丢弃"只改状态不删行是这里的重点断言 —— 删行会让导出的对账数字对不上，
 * 而用户事后反悔时也没东西可查。
 */
class ReviewQueueTest {

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
  void interruptedRowsEnterTheQueueWithTheirReason() throws Exception {
    Entry e = crashBefore("断电前在写", 30, 12);
    assertEquals(1, repo.reconcileRunning(Recovery.Cause.CRASH).size());

    List<ReviewItem> queue = repo.reviewQueue();
    assertEquals(1, queue.size());
    ReviewItem item = queue.get(0);
    assertEquals(e.id(), item.id());
    assertEquals(EntryStatus.INTERRUPTED, item.status());
    assertTrue(item.guessedEnd(), "结束时间来自心跳，得让用户知道这是猜的");
    assertTrue(item.needsReview());
    assertEquals("crash", item.cause());
    assertEquals(18, item.durationMs() / 60000, "时长 = 开始到最后心跳，不是到重启那一刻");
  }

  @Test
  void confirmAndDiscardBothEmptyTheQueueButKeepTheRow() throws Exception {
    Entry a = crashBefore("要确认的", 30, 12);
    Entry b = crashBefore("要丢弃的", 90, 61);
    repo.reconcileRunning(Recovery.Cause.CRASH);
    List<ReviewItem> queue = repo.reviewQueue();
    assertEquals(2, queue.size());
    // 队列按 started_at 排，取第几条取决于造数据的手劲 —— 按 id 找，别赌顺序
    ReviewItem itemA = queue.stream().filter(i -> i.id().equals(a.id())).findFirst().orElseThrow();
    ReviewItem itemB = queue.stream().filter(i -> i.id().equals(b.id())).findFirst().orElseThrow();
    String suggestedForA = itemA.endedAt();

    repo.confirmRecovery(a.id(), suggestedForA, itemA.durationMs());
    repo.discard(b.id(), "对账时用户丢弃");
    assertTrue(repo.reviewQueue().isEmpty(), "处理过的条目必须离开队列，不然会反复提示");

    assertEquals(EntryStatus.FINISHED, repo.find(a.id()).orElseThrow().status());
    assertEquals(EntryStatus.ABORTED, repo.find(b.id()).orElseThrow().status());
    assertEquals(suggestedForA, flags(a.id()).endedAt(), "确认不许偷偷改动已经给出的结束时间");
    assertEquals("对账时用户丢弃", flags(b.id()).reason());
    assertEquals(itemB.endedAt(), repo.find(b.id()).orElseThrow().endedAt(),
        "丢弃是作废，不是删证据");
  }

  @Test
  void missedRowShowsUpAndAcknowledgingLeavesTheTimerRunning() throws Exception {
    Entry e = repo.start("到点没看见", EntryKind.COUNT_DOWN, null, 60_000L, false);
    assertTrue(repo.reviewQueue().isEmpty(), "正常跑着的条目不该出现在队列里");

    repo.markMissed(e.id());
    List<ReviewItem> queue = repo.reviewQueue();
    assertEquals(1, queue.size());
    assertTrue(queue.get(0).missed());
    assertFalse(queue.get(0).needsReview(), "missed 是「没提示到」，不是「程序猜了时间」");

    repo.acknowledge(e.id());
    assertTrue(repo.reviewQueue().isEmpty());
    Entry back = repo.find(e.id()).orElseThrow();
    assertEquals(EntryStatus.RUNNING, back.status(), "「知道了」不许把还在跑的计时停掉");
  }

  @Test
  void queueIsOrderedOldestFirst() throws Exception {
    crashBefore("先崩的", 120, 100);
    crashBefore("后崩的", 30, 10);
    repo.reconcileRunning(Recovery.Cause.POWER_LOSS);
    assertEquals(List.of("先崩的", "后崩的"),
        repo.reviewQueue().stream().map(ReviewItem::task).toList());
  }

  /** 造一条"上次崩溃前在跑"的条目：开始于 startedMin 前、最后心跳 heartbeatMin 前。 */
  private Entry crashBefore(String task, long startedMin, long heartbeatMin) throws Exception {
    return crashBefore(task, null, startedMin, heartbeatMin);
  }

  /**
   * 造一段"断电前正在跑"的计时：先正常起段，再把开始与最后心跳往前挪。
   *
   * @param project 不同项目 ⇒ 不同 `exclusivity_key`，两段才能合法并行存在（同桶相交会被触发器拦）
   */
  private Entry crashBefore(String task, String project, long startedMin, long heartbeatMin)
      throws Exception {
    String projectId = project == null ? null : repo.ensureProject(project);
    Entry e = repo.start(task, EntryKind.COUNT_UP, projectId, false);
    OffsetDateTime now = OffsetDateTime.parse(clock.wall());
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "UPDATE time_entry SET started_at=?, last_heartbeat_at=? WHERE id=?")) {
      ps.setString(1, now.minusMinutes(startedMin).format(Clock.WIRE));
      ps.setString(2, now.minusMinutes(heartbeatMin).format(Clock.WIRE));
      ps.setString(3, e.id());
      ps.executeUpdate();
    }
    return e;
  }

  /** 并发下断电：两段都在跑 ⇒ 两段都得被问一次，一段都不许悄悄丢（M2-b 的数据面判据）。 */
  @Test
  void everyRunningRowEntersTheQueueAfterACrash() throws Exception {
    Entry a = crashBefore("并行甲", "并发甲", 30, 12);
    Entry b = crashBefore("并行乙", "并发乙", 30, 12);
    assertEquals(2, repo.reconcileRunning(Recovery.Cause.CRASH).size(),
        "两段心跳都停了 ⇒ 对账不能只问第一条");
    assertEquals(2, repo.reviewQueue().size());
    assertEquals(EntryStatus.INTERRUPTED, repo.find(a.id()).orElseThrow().status());
    assertEquals(EntryStatus.INTERRUPTED, repo.find(b.id()).orElseThrow().status());
  }

  private Flags flags(String id) throws Exception {
    try (Statement s = ds.conn().createStatement();
        ResultSet rs = s.executeQuery("SELECT ended_at, aborted_reason FROM time_entry WHERE id='"
            + id + "'")) {
      rs.next();
      return new Flags(rs.getString(1), rs.getString(2));
    }
  }

  private record Flags(String endedAt, String reason) {
  }
}
