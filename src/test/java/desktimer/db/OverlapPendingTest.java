package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.DayAttribution;
import desktimer.core.time.Recovery;
import desktimer.export.Exporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M2-g ②：相交是**收表那一刻**才成形的，所以判据、队列与导出三处都得在那一刻说话。
 *
 * 口径（用户裁的）：新成形且没人认过的重叠**不自动放行** —— 行标成"待确认"、进对账队列，
 * 结论出来之前**不进导出**。理由是导出一旦落盘就是契约：未放行的相交写出去，
 * 消费方按 1.2.0 必须 CONTRACT_FAIL，而那正是这次要修的洞。
 *
 * 断言一律挑"自己造的那两行"（a / b）说事，不比总行数 —— 跑着的行本来就在导出里。
 */
class OverlapPendingTest {

  private DataSource ds;
  private EntryRepository repo;
  private Exporter exporter;
  private Clock clock;
  private Path exported;

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    clock = new Clock();
    repo = new EntryRepository(ds, clock);
    exported = dir.resolve("exports");
    exporter = new Exporter(ds, exported, clock, "0.1.0-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    // 不关连接的话 .db-wal 一直被占着，Windows 上 JUnit 删不掉 @TempDir
    ds.close();
  }

  @Test
  void closingIntoAnUnacknowledgedOverlapParksItInTheQueue() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(10), 0);
    seedOpen("b", "unassigned", minutesAgo(5), 0);
    repo.finish("a", 60_000L);

    List<ReviewItem> queue = repo.reviewQueue();
    assertEquals(List.of("a"), queue.stream().filter(ReviewItem::overlapPending)
        .map(ReviewItem::id).toList(), "b 还在跑（ended_at 为空）不是已成的重叠，不该一起进队列");
    assertTrue(queue.stream().anyMatch(r -> r.id().equals("a")), "待确认的行必须在队列里");
    assertEquals("这段与另一段相交且你还没认过 —— 两段都算，还是这段作废？",
        queue.stream().filter(r -> r.id().equals("a")).findFirst().orElseThrow().question());
  }

  @Test
  void thePendingRowStaysOutOfTheExportUntilHeWaivesIt() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(10), 0);
    seedOpen("b", "unassigned", minutesAgo(5), 0);
    repo.finish("a", 60_000L);
    assertFalse(exportedIds().contains("a"), "未放行的相交一旦导出就是契约违例，所以先压住");

    repo.waiveOverlap("a");
    assertTrue(exportedIds().contains("a"), "他说「两段都算」之后这行就重新进导出");
    assertTrue(repo.reviewQueue().stream().noneMatch(r -> r.id().equals("a")),
        "认过之后不该再挂在队列上");
  }

  /** 起段那一刻已经放行过的对端（overlap_waived=1）不该把同一段重叠再报一遍。 */
  @Test
  void aWaivedPartnerMeansTheClosingRowNeedsNoConfirmation() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(10), 0);
    seedOpen("b", "unassigned", minutesAgo(5), 1);
    repo.finish("a", 60_000L);
    assertTrue(repo.reviewQueue().isEmpty(), "对端认过的重叠不是新问题");
    assertEquals(Set.of("a", "b"), exportedIds());
  }

  /** 自己已经放行过的行，收表时不该再被问一遍（并发起段那一刻就是他的确认）。 */
  @Test
  void aWaivedRowIsNeverParkedAgain() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(10), 1);
    seedOpen("b", "unassigned", minutesAgo(5), 0);
    repo.finish("a", 60_000L);
    assertTrue(repo.reviewQueue().isEmpty(), "一对相交有一边认过就合法，别把认过的那边再压住");
    assertEquals(Set.of("a", "b"), exportedIds());
  }

  /** 不同桶（项目不同）永远各走各的 —— 并发是功能，不是违例。 */
  @Test
  void differentBucketClosesCleanly() throws Exception {
    seedOpen("a", "prj:x", minutesAgo(10), 0);
    seedOpen("b", "unassigned", minutesAgo(5), 0);
    repo.finish("a", 60_000L);
    assertTrue(repo.reviewQueue().isEmpty());
    assertEquals(Set.of("a", "b"), exportedIds());
  }

  /** 他对"待确认"的另一种回答是"这段不作数"：superseded 的行不导出，那对相交从头就不存在。 */
  @Test
  void supersedingThePendingRowDissolvesTheOverlap() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(10), 0);
    seedOpen("b", "unassigned", minutesAgo(5), 0);
    repo.finish("a", 60_000L);
    repo.supersedeOverlap("a");
    assertTrue(repo.reviewQueue().isEmpty(), "作废的段没有可确认的重叠了");
    assertEquals(1, count("SELECT count(*) FROM time_entry WHERE id='a' AND overlap_pending=0"),
        "行留着（ended_at/duration_ms 都是证据），只是 pending 归零");
    assertEquals(1, count("SELECT count(*) FROM time_entry WHERE id='a' AND superseded_by='b'"),
        "被谁顶掉要留血缘");
    assertFalse(exportedIds().contains("a"), "superseded 不进导出");
    assertTrue(exportedIds().contains("b"));
  }

  /** 断电对账写 ended_at 走的是同一条路：必须标待确认，而不是被触发器打死。 */
  @Test
  void crashReconcileMarksPendingInsteadOfThrowing() throws Exception {
    seedOpen("a", "unassigned", minutesAgo(60), 0);
    seedOpen("b", "unassigned", minutesAgo(50), 0);
    // 心跳停在半小时前 ⇒ Recovery 判 RECONCILE
    stamp("a", minutesAgo(30));
    stamp("b", minutesAgo(30));
    List<Entry> reconciled = repo.reconcileRunning(Recovery.Cause.CRASH);
    assertEquals(2, reconciled.size(), "两段都该被判成需要确认");
    assertTrue(repo.reviewQueue().stream().anyMatch(ReviewItem::overlapPending),
        "恢复出来的相交也得有人认，不能两个都当已定数据导出");
    Set<String> out = exportedIds();
    assertFalse(out.containsAll(List.of("a", "b")), "一对未放行的相交不能同时进导出：" + out);
  }

  private String minutesAgo(int minutes) {
    return OffsetDateTime.parse(clock.wall()).minusMinutes(minutes).format(Clock.WIRE);
  }

  private void seedOpen(String id, String key, String start, int waived) throws Exception {
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
            + "end_precision,checkin_date,overlap_waived,last_heartbeat_at,created_at,updated_at)"
            + " VALUES (?, 'count_up', 'running', 'timer', '重叠待确认', ?, ?, 'user',"
            + " ?, ?, ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, key);
      ps.setString(3, start);
      ps.setString(4, DayAttribution.checkinDate(start));
      ps.setInt(5, waived);
      ps.setString(6, start);
      ps.setString(7, start);
      ps.setString(8, start);
      ps.executeUpdate();
    }
  }

  private void stamp(String id, String heartbeat) throws Exception {
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "UPDATE time_entry SET last_heartbeat_at=? WHERE id=?")) {
      ps.setString(1, heartbeat);
      ps.setString(2, id);
      ps.executeUpdate();
    }
  }

  private int count(String query) throws Exception {
    try (var st = ds.conn().createStatement();
         var rs = st.executeQuery(query)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** 真跑一次导出，返回文件里的 entry_id —— 判"进没进导出"只认落盘字节。 */
  private Set<String> exportedIds() throws Exception {
    Exporter.Result r = exporter.exportAll();
    String body = Files.readString(exported.resolve("time-entries.jsonl"));
    Set<String> ids = new LinkedHashSet<>();
    long lines = 0;
    for (String line : body.lines().filter(l -> !l.isBlank()).toList()) {
      ids.add(line.replaceAll(".*\"entry_id\":\"", "").replaceAll("\".*", ""));
      lines++;
    }
    assertEquals(r.rows(), lines, "manifest 的 rows 必须与文件行数一致");
    return ids;
  }
}
