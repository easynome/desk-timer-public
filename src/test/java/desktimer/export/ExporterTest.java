package desktimer.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.db.DataSource;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import desktimer.db.Migrations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExporterTest {

  private DataSource ds;
  private EntryRepository repo;
  private Exporter exporter;
  private Path exports;

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    Clock clock = new Clock();
    repo = new EntryRepository(ds, clock);
    exports = dir.resolve("exports");
    exporter = new Exporter(ds, exports, clock, "0.1.0-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void writesAllArtifactsAndManifestAgreesWithThem() throws Exception {
    Entry e = repo.start("写周报", EntryKind.COUNT_DOWN, null, false);
    repo.finish(e.id(), 1545000);
    Exporter.Result r = exporter.exportAll();

    assertEquals(1, r.rows());
    for (String name : List.of("manifest.json", "time-entries.jsonl", "time-entries.csv",
        "time-entries.excel.csv", "daily-2026-10.md")) {
      assertTrue(Files.exists(exports.resolve(name)), "缺产物 " + name);
    }
    try (var stream = Files.list(exports)) {
      List<String> tmps = stream.map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".tmp")).toList();
      assertTrue(tmps.isEmpty(), "不该留下孤儿 .tmp：" + tmps);
    }

    String manifest = Files.readString(exports.resolve("manifest.json"), StandardCharsets.UTF_8);
    String jsonl = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    String expectedSha = Exporter.sha256(jsonl.getBytes(StandardCharsets.UTF_8));
    assertTrue(manifest.contains("\"" + expectedSha + "\""),
        "manifest 里的 sha256 必须等于文件实际内容摘要");
    assertTrue(manifest.contains("\"rows\": 1"), "time_entries 用 rows 计条目数");
    assertTrue(manifest.contains("\"lines\":"), "非条目产物改用 lines 计数，一个字段名不许两种语义");
    assertTrue(manifest.contains("\"rule\": \"local_08:00\""), "归日口径要随导出一起给");
  }

  @Test
  void supersededRowsNeverLeaveTheDatabaseView() throws Exception {
    Entry a = repo.start("要被取代", EntryKind.COUNT_UP, null, false);
    repo.finish(a.id(), 60000);
    try (Statement s = ds.conn().createStatement()) {
      s.executeUpdate("UPDATE time_entry SET status='superseded' WHERE id='" + a.id() + "'");
    }
    repo.start("还活着", EntryKind.COUNT_UP, null, false);
    Exporter.Result r = exporter.exportAll();

    String jsonl = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    assertEquals(1, r.rows());
    assertFalse(jsonl.contains("要被取代"), "superseded 的行不许出现在导出里");
    assertTrue(jsonl.contains("还活着"));
  }

  @Test
  void escapesJsonDangerousCharacters() throws Exception {
    Entry e = repo.start("他说\"带引号\"\n第二行 C:\\path", EntryKind.COUNT_UP, null,
        false);
    repo.finish(e.id(), 1000);
    exporter.exportAll();
    String jsonl = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);

    assertTrue(jsonl.contains("\\\"带引号\\\""), jsonl);
    assertTrue(jsonl.contains("\\n第二行"), jsonl);
    assertTrue(jsonl.contains("C:\\\\path"), jsonl);
    assertEquals(1, jsonl.lines().count(), "一条记录必须是一行，换行必须被转义");
  }

  @Test
  void projectAndTagsAreSerialized() throws Exception {
    String projectId = repo.ensureProject("Portal");
    Entry e = repo.start("评审演练", EntryKind.POMODORO_WORK, projectId, false);
    repo.attachTags(e.id(), List.of("评审"));
    repo.finish(e.id(), 400000);
    exporter.exportAll();
    String jsonl = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    assertTrue(jsonl.contains("\"project\":\"Portal\""), jsonl);
    assertTrue(jsonl.contains("\"tags\":[\"评审\"]"), jsonl);
    assertTrue(jsonl.contains("\"exclusivity_key\":\"prj:"), "key 必须由项目派生：" + jsonl);
  }

  @Test
  void excelVariantHasBomAndPlainCsvDoesNot() throws Exception {
    repo.start("回邮件", EntryKind.COUNT_UP, null, false);
    exporter.exportAll();
    byte[] plain = Files.readAllBytes(exports.resolve("time-entries.csv"));
    byte[] excel = Files.readAllBytes(exports.resolve("time-entries.excel.csv"));
    assertFalse(isBom(plain), "给脚本的 CSV 必须无 BOM，否则首列名会带上不可见字符");
    assertTrue(isBom(excel), "给 Excel 的变体必须带 BOM，否则中文乱码");
    assertEquals(new String(excel, 3, excel.length - 3, StandardCharsets.UTF_8),
        new String(plain, StandardCharsets.UTF_8), "两个变体的内容应当只差一个 BOM");
  }

  @Test
  void markdownListsEntriesWithoutTotals() throws Exception {
    Entry e = repo.start("站会", EntryKind.COUNT_UP, null, false);
    repo.finish(e.id(), 900000);
    exporter.exportAll();
    String md = Files.readString(exports.resolve("daily-2026-10.md"), StandardCharsets.UTF_8);
    assertTrue(md.contains("## "), md);
    assertTrue(md.contains("站会"), md);
    for (String banned : List.of("合计", "总计", "总时长", "共 ")) {
      assertFalse(md.contains(banned), "Markdown 里不许出现聚合：" + banned);
    }
  }

  @Test
  void repeatedExportIsIdempotentForDataFiles() throws Exception {
    Entry e = repo.start("重复导出", EntryKind.COUNT_UP, null, false);
    repo.finish(e.id(), 5000);
    exporter.exportAll();
    String first = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    exporter.exportAll();
    String second = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    assertEquals(first, second, "同样内容重复导出必须字节一致，否则消费方会误判为变化");
  }

  @Test
  void interruptedRowsCarryReviewFlags() throws Exception {
    Entry e = repo.start("断电前开的", EntryKind.COUNT_UP, null, false);
    try (PreparedStatement ps = ds.conn().prepareStatement(
        "UPDATE time_entry SET started_at=?, last_heartbeat_at=? WHERE id=?")) {
      String old = "2026-10-06T14:00:00+08:00";
      ps.setString(1, old);
      ps.setString(2, old);
      ps.setString(3, e.id());
      ps.executeUpdate();
    }
    assertEquals(1, repo.reconcileRunning(desktimer.core.time.Recovery.Cause.CRASH).size());
    exporter.exportAll();
    String jsonl = Files.readString(exports.resolve("time-entries.jsonl"), StandardCharsets.UTF_8);
    assertTrue(jsonl.contains("\"status\":\"interrupted\""), jsonl);
    assertTrue(jsonl.contains("\"needs_review\":true"), jsonl);
    assertTrue(jsonl.contains("\"cause\":\"crash\""), jsonl);
    assertNotNull(jsonl);
  }

  private static boolean isBom(byte[] b) {
    return b.length > 2 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
  }
}
