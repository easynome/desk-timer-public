package desktimer.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.db.DataSource;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import desktimer.db.Migrations;
import desktimer.db.TodoRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * todos.jsonl 与时间轴里的待办行。判据与 notes 那一套同构，因为它们是同一类产物：
 * 加一种 kind 不许动 `time-entries.jsonl` 的字节，空也要产出文件，
 * 同一份库两次导出必须字节相同，版本按 minor 走（1.2.0）。
 */
class TodoExportTest {

  private DataSource ds;
  private TodoRepository todos;
  private EntryRepository entries;
  private Exporter exporter;
  private Path dir;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path tmp) throws Exception {
    ds = new DataSource(tmp.resolve("t.db"));
    Migrations.migrate(ds.conn());
    entries = new EntryRepository(ds, clock);
    todos = new TodoRepository(ds, clock);
    dir = tmp.resolve("exports");
    exporter = new Exporter(ds, dir, clock, "0.1.0-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void todosLandInJsonlWithStableOrderAndExplicitNulls() throws Exception {
    todos.add("整理发布清单", null, List.of());
    String projectId = entries.ensureProject("Portal");
    todos.add("整理笔记", projectId, List.of("复盘"));
    exporter.exportAll();

    List<String> lines = Files.readAllLines(dir.resolve("todos.jsonl"), StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    String second = lines.stream().filter(l -> l.contains("整理笔记")).findFirst().orElseThrow();
    assertTrue(second.contains("\"done\":0"), second);
    assertTrue(second.contains("\"completed_at\":null"), "没完成就是 null，不许写空字符串");
    assertTrue(second.contains("\"project\":\"Portal\""), second);
    assertTrue(second.contains("\"tags\":[\"复盘\"]"), second);
    assertTrue(second.contains("\"linked_entry_ids\":[]"), second);
    assertTrue(second.contains("\"schema_version\":\"1.2.0\""), "加 kind 走 minor");

    byte[] once = Files.readAllBytes(dir.resolve("todos.jsonl"));
    exporter.exportAll();
    assertEquals(new String(once, StandardCharsets.UTF_8),
        new String(Files.readAllBytes(dir.resolve("todos.jsonl")), StandardCharsets.UTF_8),
        "排序必须稳定：同一份库两次导出字节相同");
  }

  @Test
  void completingWritesTheDoneFlagAndTheCompletionMoment() throws Exception {
    var t = todos.add("补录昨天的评审", null, List.of());
    String day = clock.wall().substring(0, 10);
    stamp(ds, t.id(), day + "T21:00:00+08:00", null);
    todos.complete(t.id());
    stamp(ds, t.id(), day + "T21:00:00+08:00", day + "T21:12:00+08:00");
    exporter.exportAll();
    String line = Files.readString(dir.resolve("todos.jsonl"), StandardCharsets.UTF_8);
    assertTrue(line.contains("\"done\":1"), line);
    assertTrue(line.contains("\"completed_at\":\"" + day + "T21:12:00+08:00\""), line);
    // 归日按创建时刻，完成时刻另说：同一天的账不能因为"晚上才点完成"就跳到后一天
    assertTrue(line.contains("\"checkin_date\":\"" + day + "\""), line);
  }

  @Test
  void emptyTodosStillProduceAnEmptyFileListedInManifest() throws Exception {
    exporter.exportAll();
    assertTrue(Files.exists(dir.resolve("todos.jsonl")), "没有待办也要产出空文件，消费方不必判存在性");
    assertEquals(0, Files.size(dir.resolve("todos.jsonl")));
    String manifest = Files.readString(dir.resolve("manifest.json"));
    assertTrue(manifest.contains("todos.jsonl"), manifest);
    assertTrue(manifest.contains("\"lines\": 0") || manifest.contains("\"lines\":0"), manifest);
  }

  @Test
  void addingATodoLeavesTheTimeEntriesBytesUntouched() throws Exception {
    entries.start("写周报", EntryKind.COUNT_UP, null, false);
    exporter.exportAll();
    byte[] before = Files.readAllBytes(dir.resolve("time-entries.jsonl"));

    todos.add("被叫走了十分钟", null, List.of("打断"));
    exporter.exportAll();
    assertEquals(new String(before, StandardCharsets.UTF_8),
        new String(Files.readAllBytes(dir.resolve("time-entries.jsonl")), StandardCharsets.UTF_8));
  }

  @Test
  void todoInsideAnIntervalIsIndentedWhileGapsStayFlush() throws Exception {
    String day = clock.wall().substring(0, 10);
    Entry e = entries.start("写周报", EntryKind.COUNT_UP, null, false);
    stampEntry(ds, e.id(), day + "T09:00:00+08:00", day + "T09:10:00+08:00");
    var in = todos.add("区间内想起的事", null, List.of());
    stamp(ds, in.id(), day + "T09:05:00+08:00", null);
    var out = todos.add("区间之后想起的事", null, List.of());
    stamp(ds, out.id(), day + "T09:30:00+08:00", null);
    exporter.exportAll();
    String md = Files.readString(dir.resolve("daily-" + day.substring(0, 7) + ".md"));

    String inside = md.lines().filter(l -> l.contains("区间内想起的事")).findFirst().orElseThrow();
    String outside = md.lines().filter(l -> l.contains("区间之后想起的事")).findFirst().orElseThrow();
    assertTrue(inside.startsWith("    - "), "落在计时区间内要缩进挂在该条目下：" + inside);
    assertTrue(outside.startsWith("- "), "落在条目之间要顶格：" + outside);
    assertTrue(inside.contains("＋ 区间内想起的事"), "待办行要一眼认得出：" + inside);
  }

  /**
   * 打时间戳时要连 `checkin_date` 一起改：那一列是插入那一刻按 08:00 边界算的，
   * 只改 created_at 会留下"created_at 在 21:00 而归日是前一天"的自相矛盾夹具
   * （凌晨跑测试时就会露出来）。
   */
  private static void stamp(DataSource ds, String todoId, String createdAt, String completedAt)
      throws Exception {
    synchronized (ds.lock()) {
      try (var ps = ds.conn().prepareStatement("UPDATE todo SET created_at=?, checkin_date=?"
          + (completedAt == null ? "" : ", completed_at=?") + " WHERE id=?")) {
        ps.setString(1, createdAt);
        ps.setString(2, desktimer.core.time.DayAttribution.checkinDate(
            java.time.OffsetDateTime.parse(createdAt)));
        if (completedAt == null) {
          ps.setString(3, todoId);
        } else {
          ps.setString(3, completedAt);
          ps.setString(4, todoId);
        }
        ps.executeUpdate();
      }
    }
  }

  private static void stampEntry(DataSource ds, String id, String startedAt, String endedAt)
      throws Exception {
    synchronized (ds.lock()) {
      try (var ps = ds.conn().prepareStatement(
          "UPDATE time_entry SET started_at=?, ended_at=?, duration_ms=?, checkin_date=? WHERE id=?")) {
        ps.setString(1, startedAt);
        ps.setString(2, endedAt);
        ps.setLong(3, 600_000L);
        ps.setString(4, desktimer.core.time.DayAttribution.checkinDate(
            java.time.OffsetDateTime.parse(startedAt)));
        ps.setString(5, id);
        ps.executeUpdate();
      }
    }
  }
}
