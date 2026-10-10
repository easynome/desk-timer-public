package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 待办的读写面。三张表（todo / todo_tag / todo_entry）001 就建好了，这里不动 schema，
 * 只钉住四条口径：归日跟计时同一个算法、完成只改状态不删行、链接表挂的是计时条目本身、
 * 丢弃只删待办与链接（计时条目必须活着，那是证据）。
 */
class TodoRepositoryTest {

  private DataSource ds;
  private TodoRepository todos;
  private EntryRepository entries;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    todos = new TodoRepository(ds, clock);
    entries = new EntryRepository(ds, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void addReadsBackTheSameRow() throws Exception {
    Todo t = todos.add("整理发布清单", null, List.of("复盘"));
    Todo back = todos.find(t.id()).orElseThrow();
    assertEquals("整理发布清单", back.title());
    assertFalse(back.done());
    assertNull(back.completedAt(), "没完成就没有完成时刻");
    assertEquals(List.of("复盘"), back.tags());
    assertTrue(back.entryIds().isEmpty(), "新建时不该挂着任何计时条目");
    assertEquals(t.createdAt(), back.createdAt());
  }

  @Test
  void checkinDateUsesTheSameEightAmRuleAsEntries() throws Exception {
    Todo t = todos.add("晚饭后想起的事", null, List.of());
    assertEquals(desktimer.core.time.DayAttribution.checkinDate(
        java.time.OffsetDateTime.parse(t.createdAt())), t.checkinDate(),
        "待办的归日必须和计时条目同一口径，否则同一天两份账");
  }

  @Test
  void completeStampsTheCompletionAndKeepsTheRow() throws Exception {
    Todo t = todos.add("整理笔记", null, List.of());
    Todo done = todos.complete(t.id());
    assertTrue(done.done());
    assertEquals(done.createdAt().substring(0, 10), done.completedAt().substring(0, 10));
    assertEquals(1, count(ds, "SELECT COUNT(*) FROM todo WHERE id='" + done.id() + "'"));
    assertTrue(todos.pending().isEmpty(), "完成的条目不再出现在未完成清单里");
    assertEquals(1, todos.all().size(), "但导出与库里都还得看到它");
  }

  /** 幂等判据：重复"标记完成"不许把第一次的完成时刻抹掉，否则打卡素材里的时间假了。 */
  @Test
  void completingTwiceKeepsTheFirstTimestamp() throws Exception {
    Todo t = todos.add("刷 Q49", null, List.of());
    String first = todos.complete(t.id()).completedAt();
    Todo again = todos.complete(t.id());
    assertTrue(again.done());
    assertEquals(first, again.completedAt());
  }

  @Test
  void backfilledEntryLinksToTheTodoAndSurvivesItsDeletion() throws Exception {
    Todo t = todos.add("补录昨天的评审", null, List.of());
    String projectId = entries.ensureProject("评审");
    String ended = clock.wall();
    String started = java.time.OffsetDateTime.parse(ended).minusMinutes(40).format(Clock.WIRE);
    Entry e = entries.finishBackfill("评审", projectId, started, ended);
    assertEquals(EntryStatus.FINISHED.wire(), e.status().wire());
    assertEquals(40 * 60 * 1000L, e.durationMs().longValue());
    assertEquals(started, e.startedAt());
    assertEquals("manual_backfill", originOf(ds, e.id()),
        "补录的条目必须自带 origin：消费者才分得清量出来的和人给的");

    Todo linked = todos.linkEntry(t.id(), e.id());
    assertEquals(List.of(e.id()), linked.entryIds());

    todos.remove(t.id());
    assertTrue(entries.find(e.id()).isPresent(),
        "删待办只该删链接；计时条目是证据，不能被一次丢弃带走");
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM todo_entry WHERE todo_id='" + t.id() + "'"));
  }

  /**
   * 同项目重叠的补录一律拒：写进去就得给 overlap_waived=1，可用户并没有当场认过重叠 ——
   * 那个字段一贬值，外部消费者就再也分不出"静默造的"和"用户认过的"。
   */
  @Test
  void overlappingBackfillIsRefusedNotWaived() throws Exception {
    String projectId = entries.ensureProject("周报");
    Entry live = entries.start("写周报", EntryKind.COUNT_UP, projectId, false);
    // 等一秒再取 ended：墙上时间是秒级，同一秒内 start 与 ended 相等 ⇒ 判成"首尾相接"而不是重叠
    Thread.sleep(1100);
    String ended = clock.wall();
    String started = java.time.OffsetDateTime.parse(ended).minusMinutes(5).format(Clock.WIRE);
    try {
      entries.finishBackfill("补一段写周报", projectId, started, ended);
      throw new AssertionError("与在跑的段重叠的补录应当被拒");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("重叠"), expected.getMessage());
    }
    assertEquals(0, count(ds, "SELECT COUNT(*) FROM time_entry WHERE origin='manual_backfill'"));
    entries.abort(live.id(), "自检收尾", 1000L);
  }

  private static String originOf(DataSource ds, String id) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT origin FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  @Test
  void blankTitleIsRejectedAndTagsAreDeduped() throws Exception {
    try {
      todos.add("   ", null, List.of());
      throw new AssertionError("空标题应当被拒绝，否则卡片上是一行空白");
    } catch (IllegalArgumentException expected) {
      assertEquals("待办标题为空", expected.getMessage());
    }
    Todo t = todos.add("标题", null, List.of("a", "a", " b "));
    assertEquals(List.of("a", "b"), todos.find(t.id()).orElseThrow().tags());
  }

  /** 稳定排序：同一份库两次导出必须字节相同，这是契约验收的一条硬判据。 */
  @Test
  void pendingAndAllAreStablyOrdered() throws Exception {
    todos.add("乙", null, List.of());
    todos.add("甲", null, List.of());
    List<Todo> all = todos.all();
    assertEquals(2, all.size());
    assertTrue(all.get(0).createdAt().compareTo(all.get(1).createdAt()) <= 0,
        "按 created_at 升序，再按 id 兜住同一秒内的两条");
    assertEquals(2, todos.pending().size());
  }

  private static int count(DataSource ds, String sql) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(sql);
           ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }
}
