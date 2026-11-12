package desktimer.export;

import desktimer.core.time.Clock;
import desktimer.db.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 导出器：把 time_entry 写成消费方该读的文件。
 *
 * 提交协议：所有产物先写 .tmp，逐个 rename，**manifest 最后落**。
 * 消费方以 manifest.json 的出现作为"这一轮导出完整"的唯一信号，
 * 因此中途被杀只会留下孤儿 .tmp，不会让消费方读到半截数据。
 *
 * 这里不做任何聚合：时长照抄 duration_ms，不写总和、不算占比 —— 统计属于消费方。
 */
public final class Exporter {

  /**
   * 1.1.0：新增 notes.jsonl；1.2.0：新增 todos.jsonl。
   * 两次都是"加 kind 走 minor"，老字段的语义一个都没动。
   * 判据是两级 —— major 不认就停，minor 只读自己认识的 kind。
   */
  public static final String SCHEMA_VERSION = "1.2.0";

  private static final List<String> ENTRY_FIELDS = List.of(
      "entry_id", "kind", "status", "origin", "task", "project", "tags", "exclusivity_key",
      "checkin_date", "started_at", "ended_at", "duration_ms", "end_precision", "missed",
      "needs_review", "overlap_waived", "aborted_reason", "cause", "pomodoro_round",
      "schema_version");

  private static final String CSV_HEADER =
      "entry_id,kind,status,origin,task,project,tags,exclusivity_key,checkin_date,started_at,"
          + "ended_at,duration_ms,end_precision,missed,needs_review,overlap_waived,"
          + "aborted_reason,cause,pomodoro_round,schema_version";

  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private final Connection c;
  private final Object lock;
  private final Path exportsDir;
  private final Clock clock;
  private final String appVersion;

  public Exporter(DataSource ds, Path exportsDir, Clock clock, String appVersion) {
    this.c = ds.conn();
    this.lock = ds.lock();
    this.exportsDir = exportsDir;
    this.clock = clock;
    this.appVersion = appVersion;
  }

  public record Result(int rows, String sha256_12, Path dir) {
  }

  /** 导出跑在池线程、写库跑在 JavaFX 线程：两者共用一个连接，必须过同一把锁。 */
  public Result exportAll() throws SQLException, IOException {
    synchronized (lock) {
      return exportLocked();
    }
  }

  private Result exportLocked() throws SQLException, IOException {
    Files.createDirectories(exportsDir);
    List<Row> rows = read();
    List<NoteRow> noteRows = readNotes();
    List<TodoRow> todoRows = readTodos();
    String generatedAt = clock.wall();

    byte[] jsonlBytes = toJsonl(rows).getBytes(StandardCharsets.UTF_8);
    byte[] csvBytes = toCsv(rows, false).getBytes(StandardCharsets.UTF_8);
    byte[] excelBytes = concat(UTF8_BOM, toCsv(rows, true).getBytes(StandardCharsets.UTF_8));
    byte[] notesBytes = toNotesJsonl(noteRows).getBytes(StandardCharsets.UTF_8);
    byte[] todosBytes = toTodosJsonl(todoRows).getBytes(StandardCharsets.UTF_8);
    Map<String, String> monthly = toMonthlyMarkdown(rows, noteRows, todoRows);

    moveAtomic("time-entries.jsonl", jsonlBytes);
    moveAtomic("time-entries.csv", csvBytes);
    moveAtomic("time-entries.excel.csv", excelBytes);
    // 没有笔记/待办也产出空文件：消费方不必为"文件可能不存在"写一套分支
    moveAtomic("notes.jsonl", notesBytes);
    moveAtomic("todos.jsonl", todosBytes);
    List<Artifact> artifacts = new ArrayList<>(List.of(
        new Artifact("time-entries.jsonl", "time_entries", jsonlBytes),
        new Artifact("time-entries.csv", "time_entries_csv", csvBytes),
        new Artifact("time-entries.excel.csv", "time_entries_csv_excel", excelBytes),
        new Artifact("notes.jsonl", "notes", notesBytes),
        new Artifact("todos.jsonl", "todos", todosBytes)));
    for (Map.Entry<String, String> e : monthly.entrySet()) {
      byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
      moveAtomic(e.getKey(), bytes);
      artifacts.add(new Artifact(e.getKey(), "time_entries_markdown", bytes));
    }

    // manifest 最后落：它的存在就是"这轮导出完整"的提交点
    moveAtomic("manifest.json",
        manifest(generatedAt, artifacts).getBytes(StandardCharsets.UTF_8));
    return new Result(rows.size(), sha256(jsonlBytes).substring(0, 12), exportsDir);
  }

  /**
   * rows 只用于 time_entries（等于条目数）；其余产物用 lines（非空行数）。
   * 一个字段名只能有一种语义 —— 混用会让消费方静默算错。
   */
  private record Artifact(String name, String kind, byte[] bytes) {

    String countKey() {
      return "time_entries".equals(kind) ? "rows" : "lines";
    }

    int count(byte[] b) {
      int n = 0;
      for (String line : new String(b, StandardCharsets.UTF_8).split("\n", -1)) {
        if (!line.isBlank()) {
          n++;
        }
      }
      return n;
    }
  }

  private List<Row> read() throws SQLException {
    Map<String, List<String>> tagsByEntry = readTags();
    List<Row> out = new ArrayList<>();
    // 两类行不进导出：superseded（已被别的记录取代）与 overlap_pending（收表时撞出未放行的
    // 相交、正等他认）。共同点是"这一行还不是已定的数据"，而导出一旦写出去就是契约，
    // 未放行的相交写出去就是 CONTRACT_FAIL —— 宁可晚一个周期出现，也不制造违例。
    String sql = "SELECT e.id, e.kind, e.status, e.origin, e.task, p.name AS project_name,"
        + " e.exclusivity_key, e.started_at, e.ended_at, e.duration_ms, e.end_precision,"
        + " e.checkin_date, e.missed, e.needs_review, e.overlap_waived, e.aborted_reason,"
        + " e.cause, e.pomodoro_round"
        + " FROM time_entry e LEFT JOIN project p ON p.id = e.project_id"
        + " WHERE e.status <> 'superseded' AND e.overlap_pending = 0"
        + " ORDER BY e.checkin_date, e.started_at";
    try (PreparedStatement ps = c.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        // wasNull() 只对"最后一次读取"有效，所以每列读完立刻取值，不许攒到最后
        long duration = rs.getLong("duration_ms");
        boolean durationMissing = rs.wasNull();
        int round = rs.getInt("pomodoro_round");
        boolean roundMissing = rs.wasNull();
        out.add(new Row(
            rs.getString("id"),
            rs.getString("kind"),
            rs.getString("status"),
            rs.getString("origin"),
            rs.getString("task"),
            rs.getString("project_name"),
            tagsByEntry.getOrDefault(rs.getString("id"), List.of()),
            rs.getString("exclusivity_key"),
            rs.getString("started_at"),
            rs.getString("ended_at"),
            durationMissing ? null : duration,
            rs.getString("end_precision"),
            rs.getString("checkin_date"),
            rs.getInt("missed") != 0,
            rs.getInt("needs_review") != 0,
            rs.getInt("overlap_waived") != 0,
            rs.getString("aborted_reason"),
            rs.getString("cause"),
            roundMissing ? null : round));
      }
    }
    return out;
  }

  private Map<String, List<String>> readTags() throws SQLException {
    Map<String, List<String>> out = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT et.entry_id, t.name FROM entry_tag et JOIN tag t ON t.id = et.tag_id"
            + " ORDER BY et.entry_id, t.name");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
      }
    }
    return out;
  }

  /** 随手记的导出视图。{@code project}/{@code tags} 是 join 出来的派生列。 */
  private record NoteRow(String id, String body, String createdAt, String checkinDate,
      String entryId, String project, List<String> tags) {
  }

  private List<NoteRow> readNotes() throws SQLException {
    Map<String, List<String>> tagsByNote = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT nt.note_id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id"
            + " ORDER BY nt.note_id, t.name");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        tagsByNote.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
      }
    }
    List<NoteRow> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT n.id, n.body_md, n.created_at, n.checkin_date, n.entry_id, p.name AS project_name"
            + " FROM note n LEFT JOIN project p ON p.id = n.project_id"
            + " ORDER BY n.created_at, n.id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String id = rs.getString("id");
        out.add(new NoteRow(id, rs.getString("body_md"), rs.getString("created_at"),
            rs.getString("checkin_date"), rs.getString("entry_id"), rs.getString("project_name"),
            tagsByNote.getOrDefault(id, List.of())));
      }
    }
    return out;
  }

  private String toNotesJsonl(List<NoteRow> notes) {
    StringBuilder sb = new StringBuilder();
    for (NoteRow n : notes) {
      sb.append('{')
          .append("\"note_id\":\"").append(esc(n.id())).append("\",")
          .append("\"body\":\"").append(esc(n.body())).append("\",")
          .append("\"created_at\":\"").append(n.createdAt()).append("\",")
          .append("\"checkin_date\":\"").append(n.checkinDate()).append("\",")
          .append("\"linked_entry_id\":")
          .append(n.entryId() == null ? "null" : '"' + esc(n.entryId()) + '"').append(",")
          .append("\"project\":")
          .append(n.project() == null ? "null" : '"' + esc(n.project()) + '"').append(",")
          .append("\"tags\":").append(jsonTags(n.tags())).append(",")
          .append("\"schema_version\":\"").append(SCHEMA_VERSION).append("\"")
          .append('}').append('\n');
    }
    return sb.toString();
  }

  /**
   * 待办的导出视图。{@code done} 用 0/1（与条目里的 missed/needs_review 同一写法），
   * {@code completedAt} 未完成时为 null；{@code entryIds} 是这条待办挂过的计时条目（补录来的）。
   */
  private record TodoRow(String id, String title, int done, String createdAt, String completedAt,
      String checkinDate, String project, List<String> tags, List<String> entryIds) {
  }

  private List<TodoRow> readTodos() throws SQLException {
    Map<String, List<String>> tagsByTodo = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT tt.todo_id, t.name FROM todo_tag tt JOIN tag t ON t.id = tt.tag_id"
            + " ORDER BY tt.todo_id, t.name");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        tagsByTodo.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
      }
    }
    Map<String, List<String>> entriesByTodo = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT todo_id, entry_id FROM todo_entry ORDER BY linked_at, entry_id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        entriesByTodo.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
      }
    }
    List<TodoRow> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT d.id, d.title, d.done, d.created_at, d.completed_at, d.checkin_date,"
            + " p.name AS project_name"
            + " FROM todo d LEFT JOIN project p ON p.id = d.project_id"
            + " ORDER BY d.created_at, d.id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String id = rs.getString("id");
        out.add(new TodoRow(id, rs.getString("title"), rs.getInt("done"),
            rs.getString("created_at"), rs.getString("completed_at"), rs.getString("checkin_date"),
            rs.getString("project_name"), tagsByTodo.getOrDefault(id, List.of()),
            entriesByTodo.getOrDefault(id, List.of())));
      }
    }
    return out;
  }

  private String toTodosJsonl(List<TodoRow> todos) {
    StringBuilder sb = new StringBuilder();
    for (TodoRow t : todos) {
      sb.append('{')
          .append("\"todo_id\":\"").append(esc(t.id())).append("\",")
          .append("\"title\":\"").append(esc(t.title())).append("\",")
          .append("\"done\":").append(t.done()).append(",")
          .append("\"created_at\":\"").append(t.createdAt()).append("\",")
          .append("\"completed_at\":")
          .append(t.completedAt() == null ? "null" : '"' + t.completedAt() + '"').append(",")
          .append("\"checkin_date\":\"").append(t.checkinDate()).append("\",")
          .append("\"project\":")
          .append(t.project() == null ? "null" : '"' + esc(t.project()) + '"').append(",")
          .append("\"tags\":").append(jsonTags(t.tags())).append(",")
          .append("\"linked_entry_ids\":").append(jsonTags(t.entryIds())).append(",")
          .append("\"schema_version\":\"").append(SCHEMA_VERSION).append("\"")
          .append('}').append('\n');
    }
    return sb.toString();
  }

  private static String jsonTags(List<String> tags) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < tags.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(esc(tags.get(i))).append('"');
    }
    return sb.append(']').toString();
  }

  private String toJsonl(List<Row> rows) {
    StringBuilder sb = new StringBuilder();
    for (Row r : rows) {
      String tags = jsonTags(r.tags());
      sb.append('{')
          .append("\"entry_id\":\"").append(esc(r.id())).append("\",")
          .append("\"kind\":\"").append(r.kind()).append("\",")
          .append("\"status\":\"").append(r.status()).append("\",")
          .append("\"origin\":\"").append(r.origin()).append("\",")
          .append("\"task\":\"").append(esc(r.task())).append("\",")
          .append("\"project\":").append(r.project() == null ? "null" : '"' + esc(r.project()) + '"')
          .append(",")
          .append("\"tags\":").append(tags).append(",")
          .append("\"exclusivity_key\":\"").append(esc(r.exclusivityKey())).append("\",")
          .append("\"checkin_date\":\"").append(r.checkinDate()).append("\",")
          .append("\"started_at\":\"").append(r.startedAt()).append("\",")
          .append("\"ended_at\":").append(r.endedAt() == null ? "null" : '"' + r.endedAt() + '"')
          .append(",")
          .append("\"duration_ms\":").append(r.durationMs() == null ? "null" : r.durationMs())
          .append(",")
          .append("\"end_precision\":")
          .append(r.endPrecision() == null ? "null" : '"' + r.endPrecision() + '"').append(",")
          .append("\"missed\":").append(r.missed()).append(",")
          .append("\"needs_review\":").append(r.needsReview()).append(",")
          .append("\"overlap_waived\":").append(r.overlapWaived()).append(",")
          .append("\"aborted_reason\":")
          .append(r.abortedReason() == null ? "null" : '"' + esc(r.abortedReason()) + '"').append(",")
          .append("\"cause\":").append(r.cause() == null ? "null" : '"' + r.cause() + '"').append(",")
          .append("\"pomodoro_round\":")
          .append(r.pomodoroRound() == null ? "null" : r.pomodoroRound()).append(",")
          .append("\"schema_version\":\"").append(SCHEMA_VERSION).append("\"")
          .append('}').append('\n');
    }
    return sb.toString();
  }

  private String toCsv(List<Row> rows, boolean excel) {
    StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
    for (Row r : rows) {
      sb.append(displayCell(excel, r.id())).append(',')
          .append(displayCell(excel, r.kind())).append(',')
          .append(displayCell(excel, r.status())).append(',')
          .append(displayCell(excel, r.origin())).append(',')
          .append(displayCell(excel, r.task())).append(',')
          .append(displayCell(excel, r.project())).append(',')
          .append(displayCell(excel, String.join("|", r.tags()))).append(',')
          .append(displayCell(excel, r.exclusivityKey())).append(',')
          .append(displayCell(excel, r.checkinDate())).append(',')
          .append(displayCell(excel, r.startedAt())).append(',')
          .append(displayCell(excel, r.endedAt())).append(',')
          .append(r.durationMs() == null ? "" : r.durationMs()).append(',')
          .append(displayCell(excel, r.endPrecision())).append(',')
          .append(r.missed()).append(',')
          .append(r.needsReview()).append(',')
          .append(r.overlapWaived()).append(',')
          .append(displayCell(excel, r.abortedReason())).append(',')
          .append(displayCell(excel, r.cause())).append(',')
          .append(r.pomodoroRound() == null ? "" : r.pomodoroRound()).append(',')
          .append(SCHEMA_VERSION).append('\n');
    }
    return sb.toString();
  }

  /**
   * 一个自然月一个文件：daily-2026-10.md，内部按归属日分节，计时条目与随手记按时间混排。
   * 只列事实，不求和 —— 聚合归消费方。头部刻意不含任何导出时刻：
   * 同一份库重导两次必须字节相同，否则"再导出一次"本身就制造 diff。
   *
   * <p>笔记落在某段计时的区间内时缩进挂到那条下面，落在条目之间时顶格 ——
   * 既保住时刻顺序，又不让一行笔记把一段计时读断。</p>
   */
  private static Map<String, String> toMonthlyMarkdown(List<Row> rows, List<NoteRow> notes,
      List<TodoRow> todos) {
    Map<String, List<Line>> byDay = new TreeMap<>();
    for (Row r : rows) {
      byDay.computeIfAbsent(r.checkinDate(), k -> new ArrayList<>()).add(
          new Line(OffsetDateTime.parse(r.startedAt()), 0, entryText(r),
              OffsetDateTime.parse(r.startedAt()),
              r.endedAt() == null ? null : OffsetDateTime.parse(r.endedAt())));
    }
    for (NoteRow n : notes) {
      byDay.computeIfAbsent(n.checkinDate(), k -> new ArrayList<>())
          .add(new Line(OffsetDateTime.parse(n.createdAt()), 1, noteText(n), null, null));
    }
    // 待办按"想起的那一刻"进时间轴，与它的归日同一口径；完成时刻写在行尾
    for (TodoRow t : todos) {
      byDay.computeIfAbsent(t.checkinDate(), k -> new ArrayList<>())
          .add(new Line(OffsetDateTime.parse(t.createdAt()), 2, todoText(t), null, null));
    }

    Map<String, StringBuilder> byMonth = new TreeMap<>();
    String day = null;
    for (Map.Entry<String, List<Line>> e : byDay.entrySet()) {
      String month = e.getKey().substring(0, 7);
      StringBuilder sb = byMonth.computeIfAbsent(month,
          k -> new StringBuilder("# desk-timer ").append(k).append('\n'));
      if (!e.getKey().equals(day)) {
        day = e.getKey();
        sb.append("\n## ").append(day).append("（归属日，08:00 边界）\n");
      }
      List<Line> lines = new ArrayList<>(e.getValue());
      lines.sort(Comparator.comparing((Line l) -> l.at).thenComparingInt(l -> l.order));
      Line previousEntry = null;
      for (Line l : lines) {
        if (l.start == null) {
          boolean inside = previousEntry != null && previousEntry.contains(l.at);
          sb.append(inside ? "    - " : "- ").append(l.text).append('\n');
        } else {
          sb.append("- ").append(l.text).append('\n');
          previousEntry = l;
        }
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    byMonth.forEach((month, sb) -> out.put("daily-" + month + ".md", sb.toString()));
    return out;
  }

  /** 时间轴上的一行：order 让同一时刻的条目排在笔记前面。 */
  private record Line(OffsetDateTime at, int order, String text, OffsetDateTime start,
      OffsetDateTime end) {

    /** 半开区间 [start, end)：与 Interval 同一套口径，end 为 null 表示还在跑。 */
    boolean contains(OffsetDateTime t) {
      return !t.isBefore(start) && (end == null || t.isBefore(end));
    }
  }

  private static String entryText(Row r) {
    StringBuilder sb = new StringBuilder();
    sb.append(hhmm(r.startedAt())).append('-')
        .append(r.endedAt() == null ? "进行中" : hhmm(r.endedAt()))
        .append(' ').append(r.task())
        .append("（").append(r.kind()).append(" · ").append(r.status());
    if (r.durationMs() != null) {
      sb.append(" · ").append(r.durationMs()).append("ms");
    }
    if (r.endPrecision() != null) {
      sb.append(" · 结束时间精度=").append(r.endPrecision());
    }
    if (r.needsReview()) {
      sb.append(" · 待确认");
    }
    if (r.missed()) {
      sb.append(" · 当时未提示");
    }
    if (!r.tags().isEmpty()) {
      sb.append(" · ").append(String.join("/", r.tags()));
    }
    return sb.append("）").toString();
  }

  private static String noteText(NoteRow n) {
    StringBuilder sb = new StringBuilder(hhmm(n.createdAt())).append(" ! ").append(n.body());
    List<String> marks = new ArrayList<>();
    n.tags().forEach(t -> marks.add("#" + t));
    if (n.project() != null) {
      marks.add("@" + n.project());
    }
    return marks.isEmpty() ? sb.toString() : sb.append("（").append(String.join(" ", marks))
        .append("）").toString();
  }

  /** 待办行：`＋ 标题`，完成就在行尾补一个 `✓ hh:mm`（打卡侧要的是"今天干成了什么"）。 */
  private static String todoText(TodoRow t) {
    StringBuilder sb = new StringBuilder(hhmm(t.createdAt())).append(" ＋ ").append(t.title());
    if (t.done() == 1) {
      sb.append(" ✓").append(t.completedAt() == null ? "" : " " + hhmm(t.completedAt()));
    }
    List<String> marks = new ArrayList<>();
    t.tags().forEach(one -> marks.add("#" + one));
    if (t.project() != null) {
      marks.add("@" + t.project());
    }
    return marks.isEmpty() ? sb.toString() : sb.append("（").append(String.join(" ", marks))
        .append("）").toString();
  }

  private String manifest(String generatedAt, List<Artifact> artifacts) {
    StringBuilder files = new StringBuilder();
    for (int i = 0; i < artifacts.size(); i++) {
      Artifact a = artifacts.get(i);
      if (i > 0) {
        files.append(",\n");
      }
      files.append("    {\"name\": \"").append(a.name())
          .append("\", \"kind\": \"").append(a.kind())
          .append("\", \"").append(a.countKey()).append("\": ").append(a.count(a.bytes()))
          .append(", \"sha256\": \"").append(sha256(a.bytes()))
          .append("\", \"updated_at\": \"").append(generatedAt).append("\"}");
    }
    return "{\n"
        + "  \"schema_version\": \"" + SCHEMA_VERSION + "\",\n"
        + "  \"app_version\": \"" + esc(appVersion) + "\",\n"
        + "  \"generated_at\": \"" + generatedAt + "\",\n"
        + "  \"timer_home\": \"" + home() + "\",\n"
        + "  \"day_attribution\": {\"rule\": \"local_08:00\","
        + " \"note\": \"本地 08:00 前的记录归前一日\"},\n"
        + "  \"files\": [\n" + files + "\n  ],\n"
        + "  \"fields\": {\"time_entries\": [" + quote(ENTRY_FIELDS) + "]}\n"
        + "}\n";
  }

  private String home() {
    Path parent = exportsDir.getParent();
    String s = (parent == null ? exportsDir : parent).toString()
        .replace('\\', '/').replace("\"", "\\\"");
    return s;
  }

  private void moveAtomic(String name, byte[] content) throws IOException {
    Path tmp = exportsDir.resolve(name + ".tmp");
    Path target = exportsDir.resolve(name);
    Files.write(tmp, content);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String quote(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(values.get(i)).append('"');
    }
    return sb.toString();
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.toString();
  }

  /** Excel 专用展示层；机器 CSV 与 JSONL 保留原值。参见 docs/EXPORT_DISPLAY.md。 */
  static String displayCell(boolean excel, String v) {
    if (excel && v != null && !v.isEmpty()) {
      String leading = v.stripLeading();
      boolean control = v.charAt(0) == '\t' || v.charAt(0) == '\r' || v.charAt(0) == '\n';
      if (control || (!leading.isEmpty() && "=+-@＝＋－＠".indexOf(leading.charAt(0)) >= 0))
        return '\"' + "\t" + v.replace("\"", "\"\"") + '\"';
    }
    return csvCell(v);
  }

  private static String csvCell(String v) {
    if (v == null) {
      return "";
    }
    boolean needsQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
        || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.startsWith(" ") || v.endsWith(" ");
    return needsQuote ? '"' + v.replace("\"", "\"\"") + '"' : v;
  }

  private static String hhmm(String iso) {
    return iso.length() >= 16 ? iso.substring(11, 16) : iso;
  }

  static String sha256(byte[] data) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder sb = new StringBuilder();
      for (byte b : d) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Row(
      String id, String kind, String status, String origin, String task, String project,
      List<String> tags, String exclusivityKey, String startedAt, String endedAt,
      Long durationMs, String endPrecision, String checkinDate, boolean missed,
      boolean needsReview, boolean overlapWaived, String abortedReason, String cause,
      Integer pomodoroRound) {
  }
}
