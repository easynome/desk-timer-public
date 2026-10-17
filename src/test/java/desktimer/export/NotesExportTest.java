package desktimer.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.db.DataSource;
import desktimer.db.Entry;
import desktimer.db.Note;
import desktimer.db.EntryRepository;
import desktimer.db.Migrations;
import desktimer.db.NoteRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * notes.jsonl 与时间轴的导出契约。三条最容易被写坏的地方：
 * 加笔记不许动 time-entries.jsonl 的字节；空也要产出文件；
 * 渲染结果不许带任何随导出时刻变化的值（否则"重导一次"本身就是 diff）。
 */
class NotesExportTest {

  private DataSource ds;
  private NoteRepository notes;
  private EntryRepository entries;
  private Exporter exporter;
  private Path dir;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path tmp) throws Exception {
    ds = new DataSource(tmp.resolve("t.db"));
    Migrations.migrate(ds.conn());
    entries = new EntryRepository(ds, clock);
    notes = new NoteRepository(ds, clock);
    dir = tmp.resolve("exports");
    exporter = new Exporter(ds, dir, clock, "0.1.0-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void notesGoToJsonlWithStableOrderAndExplicitNulls() throws Exception {
    notes.add("第一条", null, null, List.of());
    String projectId = entries.ensureProject("Portal");
    notes.add("第二条", projectId, null, List.of("灵感"));
    exporter.exportAll();

    List<String> lines = Files.readAllLines(dir.resolve("notes.jsonl"), StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    String second = lines.stream().filter(l -> l.contains("第二条")).findFirst().orElseThrow();
    assertTrue(second.contains("\"tags\":[\"灵感\"]"), second);
    assertTrue(second.contains("\"project\":\"Portal\""), second);
    assertTrue(second.contains("\"linked_entry_id\":null"), "空值必须是 null，不能是空字符串");
    assertTrue(second.contains("\"schema_version\":\"1.2.0\""), "行级版本必须等于 manifest 的 major.minor");

    byte[] once = Files.readAllBytes(dir.resolve("notes.jsonl"));
    exporter.exportAll();
    assertArrayEqualsIgnoreIdentity(once, Files.readAllBytes(dir.resolve("notes.jsonl")),
        "排序必须稳定：同一份库两次导出字节相同");
  }

  @Test
  void emptyNotesStillProduceAnEmptyFileListedInManifest() throws Exception {
    exporter.exportAll();
    assertTrue(Files.exists(dir.resolve("notes.jsonl")), "没有笔记也要产出空文件，消费方不必判存在性");
    assertEquals(0, Files.size(dir.resolve("notes.jsonl")));
    String manifest = Files.readString(dir.resolve("manifest.json"));
    assertTrue(manifest.contains("\"kind\": \"notes\"") || manifest.contains("\"kind\":\"notes\""),
        manifest);
    assertTrue(manifest.contains("\"lines\": 0") || manifest.contains("\"lines\":0"), manifest);
  }

  @Test
  void addingANoteLeavesTheTimeEntriesBytesUntouched() throws Exception {
    entries.start("写周报", EntryKind.COUNT_UP, null, false);
    exporter.exportAll();
    byte[] before = Files.readAllBytes(dir.resolve("time-entries.jsonl"));

    notes.add("被叫走了十分钟", null, null, List.of("打断"));
    exporter.exportAll();
    assertArrayEqualsIgnoreIdentity(before, Files.readAllBytes(dir.resolve("time-entries.jsonl")));
  }

  /**
   * 置顶是**应用内部**的状态：`pinned` 列 001 就有，但它不进 notes.jsonl 的 8 个字段。
   * 这条断言防的是"顺手把 pinned 加进导出" —— 那是契约变更，得走 minor 并让消费方重新验收，
   * 不该被一个界面开关顺带做掉。
   */
  @Test
  void pinningLeavesTheExportedBytesUntouched() throws Exception {
    Note n = notes.add("值得反复看的一条", null, null, List.of("错题"));
    exporter.exportAll();
    byte[] before = Files.readAllBytes(dir.resolve("notes.jsonl"));

    notes.setPinned(n.id(), true);
    exporter.exportAll();
    byte[] after = Files.readAllBytes(dir.resolve("notes.jsonl"));
    assertTrue(java.util.Arrays.equals(before, after),
        "pinned 一改，外部读到的行就跟着变 —— 它不该出现在导出里");
    assertTrue(new String(after, java.nio.charset.StandardCharsets.UTF_8).indexOf("pinned") < 0,
        "导出字段里不许冒出 pinned");
  }

  @Test
  void dailyMarkdownIsByteIdenticalAcrossExportsAndCarriesNoExportTimestamp() throws Exception {
    Entry e = entries.start("写周报", EntryKind.COUNT_UP, null, false);
    entries.finish(e.id(), 600_000L);
    notes.add("这段很顺", null, e.id(), List.of());
    exporter.exportAll();
    Path md = dir.resolve("daily-" + clock.wall().substring(0, 7) + ".md");
    String first = Files.readString(md);
    String generatedAt = generatedAtOf(dir.resolve("manifest.json"));

    exporter.exportAll();
    String second = Files.readString(md);
    assertEquals(first, second, "同一份库两次导出必须字节相同");
    assertFalse(second.contains(generatedAt), "时间轴里不许出现本次导出时刻：" + generatedAt);
    assertTrue(first.startsWith("# desk-timer "), first);
  }

  @Test
  void noteInsideAnIntervalIsIndentedWhileGapsStayFlush() throws Exception {
    // 显式打时间戳：finish 写的 ended_at 是"现在"，同一秒内起止就是零长区间
    // （秒级定宽粒度的已知后果），那样测不出"区间内"这件事。
    String day = clock.wall().substring(0, 10);
    Entry e = entries.start("写周报", EntryKind.COUNT_UP, null, false);
    stamp(ds, "time_entry", e.id(), day + "T09:00:00+08:00", day + "T09:10:00+08:00");
    var n1 = notes.add("区间内的笔记", null, e.id(), List.of());
    stamp(ds, "note", n1.id(), day + "T09:05:00+08:00", null);
    var n2 = notes.add("区间之后的笔记", null, null, List.of());
    stamp(ds, "note", n2.id(), day + "T09:30:00+08:00", null);
    exporter.exportAll();
    String md = Files.readString(dir.resolve("daily-" + day.substring(0, 7) + ".md"));

    String inside = md.lines().filter(l -> l.contains("区间内的笔记")).findFirst().orElseThrow();
    String outside = md.lines().filter(l -> l.contains("区间之后的笔记")).findFirst().orElseThrow();
    assertTrue(inside.startsWith("    - "), "落在计时区间内要缩进挂在该条目下：" + inside);
    assertTrue(outside.startsWith("- "), "落在条目之间要顶格：" + outside);
    assertTrue(inside.contains("! 区间内的笔记"), "笔记行要能一眼看出是笔记：" + inside);
  }

  private static void stamp(DataSource ds, String table, String id, String at, String end)
      throws Exception {
    String column = "time_entry".equals(table) ? "started_at" : "created_at";
    try (var ps = ds.conn().prepareStatement(
        "UPDATE " + table + " SET " + column + "=?" + (end == null ? "" : ", ended_at=?")
            + " WHERE id=?")) {
      ps.setString(1, at);
      if (end != null) {
        ps.setString(2, end);
        ps.setString(3, id);
      } else {
        ps.setString(2, id);
      }
      ps.executeUpdate();
    }
  }

  private static String generatedAtOf(Path manifest) throws Exception {
    Matcher m = Pattern.compile("\"generated_at\"\\s*:\\s*\"([^\"]+)\"")
        .matcher(Files.readString(manifest));
    assertTrue(m.find(), "manifest 里得有 generated_at，否则这条断言是空的");
    return m.group(1);
  }

  private static void assertArrayEqualsIgnoreIdentity(byte[] expected, byte[] actual) {
    assertArrayEqualsIgnoreIdentity(expected, actual, null);
  }

  private static void assertArrayEqualsIgnoreIdentity(byte[] expected, byte[] actual, String why) {
    assertEquals(new String(actual, StandardCharsets.UTF_8),
        new String(expected, StandardCharsets.UTF_8), why);
  }
}
