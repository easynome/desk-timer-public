package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 随手记的读写面。表是 001 就建好的，这里只证明三件事：
 * 归日口径与计时条目一致、标签真的落进 note_tag、挂接可以为空。
 */
class NoteRepositoryTest {

  private DataSource ds;
  private NoteRepository notes;
  private EntryRepository entries;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    notes = new NoteRepository(ds, clock);
    entries = new EntryRepository(ds, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void addReadsBackTheSameRow() throws Exception {
    Note n = notes.add("写周报写到一半", null, null, List.of("工作", "灵感"));
    Note back = notes.find(n.id()).orElseThrow();
    assertEquals("写周报写到一半", back.body());
    assertEquals(List.of("工作", "灵感"), back.tags());
    assertNull(back.projectId(), "没给项目就是 null，不许造一个空项目");
    assertNull(back.entryId());
    assertEquals(n.createdAt(), back.createdAt());
    // 归日是 08:00 边界，不等于自然日：凌晨写的记录归前一天，这里必须按同一算法比
    assertEquals(desktimer.core.time.DayAttribution.checkinDate(
        java.time.OffsetDateTime.parse(back.createdAt())), back.checkinDate());
  }

  @Test
  void tagsLandInTheLinkTableNotOnlyInTheBody() throws Exception {
    Note n = notes.add("正文", null, null, List.of("a", "a", " b "));
    assertEquals(List.of("a", "b"), notes.find(n.id()).orElseThrow().tags(),
        "标签去重去空白：重复标签会让时间轴一行里出现两遍");
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "SELECT COUNT(*) FROM note_tag WHERE note_id=?")) {
      ps.setString(1, n.id());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(2, rs.getInt(1));
      }
    }
  }

  @Test
  void linksToTheRunningEntryWhenGivenOne() throws Exception {
    Entry running = entries.start("写周报", desktimer.core.time.EntryKind.COUNT_UP, null, false);
    String projectId = entries.ensureProject("Portal");
    Note n = notes.add("被需求变更打断了", projectId, running.id(), List.of());
    Note back = notes.find(n.id()).orElseThrow();
    assertEquals(running.id(), back.entryId());
    assertEquals(projectId, back.projectId());
  }

  @Test
  void listsByDayInTimeOrder() throws Exception {
    notes.add("第一条", null, null, List.of());
    notes.add("第二条", null, null, List.of());
    List<Note> today = notes.forDate(desktimer.core.time.DayAttribution.checkinDate(
        java.time.OffsetDateTime.parse(clock.wall())));
    assertEquals(2, today.size());
    assertTrue(today.get(0).createdAt().compareTo(today.get(1).createdAt()) <= 0,
        "时间轴按 created_at 升序，顺序乱了就不是时间轴");
  }

  @Test
  void blankBodyIsRejectedAtTheBoundary() {
    assertThrowsBlank(notes, "   ");
  }

  /** 默认视图＝今天 ∪ 所有置顶；置顶排最前，今天的按写入倒序（新的在上面）。 */
  @Test
  void pinnedNotesStayInTheDefaultViewEvenFromAnotherDay() throws Exception {
    Note morning = notes.add("今天早上的", null, null, List.of());
    Note afternoon = notes.add("今天下午的", null, null, List.of());
    Note kept = notes.add("昨天写的但置顶了", null, null, List.of());
    stampDay(kept.id(), "2026-09-30");
    notes.setPinned(kept.id(), true);

    List<Note> view = notes.pinnedFirst(today());
    assertEquals(3, view.size(), "置顶跨归属日照样常驻 —— 这正是置顶的全部意义");
    assertEquals(kept.id(), view.get(0).id(), "置顶在最前，哪怕它不是今天的");
    assertEquals(List.of(afternoon.id(), morning.id()),
        List.of(view.get(1).id(), view.get(2).id()), "今天的新的在上面");
    assertTrue(view.get(0).pinned());
    assertFalse(view.get(1).pinned(), "pinned 得真的读得出来，不然界面画不出★");
  }

  /** 取消置顶后，别天的那条就该从今天视图里消失 —— 往返必须对称。 */
  @Test
  void unpinDropsItOutOfAnotherDaysView() throws Exception {
    Note kept = notes.add("先置顶再取消", null, null, List.of());
    stampDay(kept.id(), "2026-09-30");
    notes.setPinned(kept.id(), true);
    assertEquals(1, notes.pinnedFirst(today()).size());
    notes.setPinned(kept.id(), false);
    assertEquals(0, notes.pinnedFirst(today()).size(), "取消之后不许留下幽灵行");
    assertFalse(notes.find(kept.id()).orElseThrow().pinned());
  }

  /** 搜索是全库的：正文中文子串、标签名、项目名都算命中，结果按归属日倒序。 */
  @Test
  void searchMatchesBodyTagAndProjectAcrossDays() throws Exception {
    // add 的第二个参数是 project **id**，不是名字：直接传名字会被外键当场拒掉
    String interview = entries.ensureProject("评审");
    Note today = notes.add("Q49 闭卷复测没过的题", interview, null, List.of("错题"));
    Note older = notes.add("评审时提到并发这块", interview, null, List.of());
    stampDay(older.id(), "2026-09-12");
    notes.add("晚饭吃了面", null, null, List.of("生活"));

    assertEquals(List.of(today.id()), ids(notes.search("闭卷")), "中文子串要能命中");
    assertEquals(List.of(today.id()), ids(notes.search("错题")), "标签名也算命中");
    assertEquals(List.of(today.id(), older.id()), ids(notes.search("评审")), "项目名也算命中，今天的在前");
    assertEquals(List.of(), notes.search("   "), "纯空白不许把全库倒进卡里");
    assertEquals(List.of(), notes.search(null));
  }

  /**
   * 上限 200：一次搜索不许把整库拉进 FX 线程。
   * 这条是硬约束而不是优化 —— 卡是每秒可能在敲的交互，全库读取会真的卡住界面。
   */
  @Test
  void searchIsCappedAtTwoHundredRows() throws Exception {
    for (int i = 0; i < 205; i++) {
      notes.add("占位条目 " + i, null, null, List.of());
    }
    assertEquals(200, notes.search("占位").size(), "命中超过 200 条时截断，并把范围写在状态行上");
  }

  /**
   * 搜索框上写的是"搜正文 / #标签 / 项目"，实现就得认这两个前缀：`#` 只按标签名、`@` 只按项目名。
   * 不带符号仍是三处都搜（宽松）。这条是从界面承诺倒推出来的：整串去 LIKE 正文时，
   * 用户打 `#错题本` 一定搜不到（标签名里不存井号），界面等于在骗他。
   */
  @Test
  void sigilPrefixNarrowsTheSearchScope() throws Exception {
    String interview = entries.ensureProject("评审");
    Note tagged = notes.add("复测记录", interview, null, List.of("错题本"));
    Note mentions = notes.add("正文里顺手写了 #错题本 四个字", null, null, List.of());

    assertEquals(List.of(tagged.id()), ids(notes.search("#错题本")),
        "打 # 就该按标签名找，不碰正文");
    assertEquals(List.of(tagged.id()), ids(notes.search("@评审")), "打 @ 就该只按项目名找");
    assertEquals(List.of(), notes.search("@不存在的项目"), "项目没命中就是没命中");
    assertEquals(List.of(), notes.search("#"), "只打一个符号不许把全库倒进卡里");
    assertEquals(2, notes.search("错题本").size(),
        "不带符号保持宽松：正文里提到这个词的也算命中（" + mentions.body() + "）");
  }

  private String today() {
    return desktimer.core.time.DayAttribution.checkinDate(clock.wall());
  }

  private static List<String> ids(List<Note> rows) {
    return rows.stream().map(Note::id).toList();
  }

  /** 挪归属日必须连时间戳一起挪吗？不用：这里只改 checkin_date，不碰 started/created 的区间判据。 */
  private void stampDay(String id, String day) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "UPDATE note SET checkin_date=? WHERE id=?")) {
        ps.setString(1, day);
        ps.setString(2, id);
        ps.executeUpdate();
      }
    }
  }

  private static void assertThrowsBlank(NoteRepository repo, String body) {
    try {
      repo.add(body, null, null, List.of());
      throw new AssertionError("空正文应当被拒绝，否则时间轴上会出现一条空白");
    } catch (IllegalArgumentException expected) {
      assertEquals("笔记正文为空", expected.getMessage());
    } catch (Exception e) {
      throw new AssertionError("异常类型不对：" + e, e);
    }
  }
}
