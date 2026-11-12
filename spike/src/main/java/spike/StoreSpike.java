package spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * T-06 存储与契约实测：心跳 + 强杀对账、08:00 归日、重叠约束、原子导出。
 * 角色：--role=crashrun 写入并 halt 模拟断电；--role=recover 对账 + 自检 + 导出。
 */
public final class StoreSpike {

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final String CRASH_ID = "spike-crash-0001";

  public static void main(String[] args) throws Exception {
    String role = arg(args, "--role", "recover");
    Path home = Paths.get(arg(args, "--home", "target/timer-data"));
    Class.forName("org.sqlite.JDBC");
    Files.createDirectories(home.resolve("db"));
    Files.createDirectories(home.resolve("exports"));
    Path dbFile = home.resolve("db/timer.db");

    if (role.equals("crashrun")) {
      crashRun(dbFile, Integer.parseInt(arg(args, "--secs", "8")));
      return;
    }

    int failures = 0;
    failures += attributionSelfTest();
    try (Connection c = open(dbFile)) {
      schema(c);
      failures += seedAndReconcile(c);
      failures += overlapTest(c);
      export(c, home);
    }
    System.out.println(failures == 0 ? "RESULT=PASS" : "RESULT=FAIL failures=" + failures);
  }

  /** 写一条 running 计时并持续心跳，然后 halt(9) —— 不走 shutdown hook，等同断电。 */
  private static void crashRun(Path dbFile, int secs) throws Exception {
    try (Connection c = open(dbFile)) {
      schema(c);
      try (Statement s = c.createStatement()) {
        s.executeUpdate("DELETE FROM time_entry WHERE id='" + CRASH_ID + "'");
      }
      OffsetDateTime start = OffsetDateTime.now();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
              + "checkin_date,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
        ps.setString(1, CRASH_ID);
        ps.setString(2, "count_down");
        ps.setString(3, "running");
        ps.setString(4, "timer");
        ps.setString(5, "写周报");
        ps.setString(6, "unassigned");
        ps.setString(7, start.format(ISO));
        ps.setString(8, checkinDate(start));
        ps.setString(9, start.format(ISO));
        ps.setString(10, start.format(ISO));
        ps.executeUpdate();
      }
      long deadline = System.currentTimeMillis() + secs * 1000L;
      int beats = 0;
      while (System.currentTimeMillis() < deadline) {
        Thread.sleep(1000);
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE time_entry SET last_heartbeat_at=?, updated_at=? WHERE id=?")) {
          String now = OffsetDateTime.now().format(ISO);
          ps.setString(1, now);
          ps.setString(2, now);
          ps.setString(3, CRASH_ID);
          ps.executeUpdate();
        }
        beats++;
        System.out.println("HEARTBEAT n=" + beats + " ts=" + OffsetDateTime.now().format(ISO));
      }
      System.out.println("HALTING 模拟断电 beats=" + beats);
      Runtime.getRuntime().halt(9);
    }
  }

  /** 重启对账：running 且无 ended_at ⇒ interrupted，结束时间取最后心跳而不是"现在"。 */
  private static int seedAndReconcile(Connection c) throws SQLException {
    OffsetDateTime yStart = OffsetDateTime.now().minusDays(1).withHour(21).withMinute(0).withSecond(0);
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT OR IGNORE INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
            + "ended_at,duration_ms,end_precision,checkin_date,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, "spike-history-0001");
      ps.setString(2, "count_up");
      ps.setString(3, "finished");
      ps.setString(4, "timer");
      ps.setString(5, "回邮件");
      ps.setString(6, "unassigned");
      ps.setString(7, yStart.format(ISO));
      ps.setString(8, yStart.plusMinutes(30).format(ISO));
      ps.setLong(9, 30 * 60 * 1000L);
      ps.setString(10, "user");
      ps.setString(11, checkinDate(yStart));
      ps.setString(12, yStart.format(ISO));
      ps.setString(13, yStart.format(ISO));
      ps.executeUpdate();
    }

    int failures = 0;
    int reconciled = 0;
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT id,started_at,last_heartbeat_at FROM time_entry"
                 + " WHERE status='running' AND ended_at IS NULL")) {
      List<String[]> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
      }
      for (String[] r : rows) {
        String hb = r[2] == null ? r[1] : r[2];
        long dur = Math.max(0, OffsetDateTime.parse(hb).toInstant().toEpochMilli()
            - OffsetDateTime.parse(r[1]).toInstant().toEpochMilli());
        try (PreparedStatement up = c.prepareStatement(
            "UPDATE time_entry SET status='interrupted', ended_at=?, duration_ms=?,"
                + " end_precision='heartbeat', cause='crash', needs_review=1, updated_at=?"
                + " WHERE id=?")) {
          up.setString(1, hb);
          up.setLong(2, dur);
          up.setString(3, OffsetDateTime.now().format(ISO));
          up.setString(4, r[0]);
          up.executeUpdate();
        }
        reconciled++;
        // 判据是 ended_at 精确等于最后心跳，而不是"和当前时刻差一分钟"（那是巧合判据）
        boolean usesHeartbeat = false;
        try (PreparedStatement v = c.prepareStatement(
            "SELECT ended_at, last_heartbeat_at FROM time_entry WHERE id=?")) {
          v.setString(1, r[0]);
          try (ResultSet rs2 = v.executeQuery()) {
            if (rs2.next()) {
              usesHeartbeat = rs2.getString(1) != null && rs2.getString(1).equals(rs2.getString(2));
            }
          }
        }
        System.out.println("RECONCILED id=" + r[0] + " ended_at=" + hb + " duration_ms=" + dur);
        System.out.println("CHECK_ENDED_EQUALS_LAST_HEARTBEAT ok=" + usesHeartbeat);
        if (!usesHeartbeat) {
          failures++;
        }
      }
    }
    System.out.println("RECONCILED_COUNT=" + reconciled + " (期望 1，来自 crashrun 的 " + CRASH_ID + ")");
    if (reconciled != 1) {
      failures++;
    }
    return failures;
  }

  /** 同 exclusivity_key 的静默重叠必须被拦；用户放行（overlap_waived=1）必须能过。 */
  private static int overlapTest(Connection c) throws SQLException {
    int failures = 0;
    insert(c, "spike-ov-a", "14:00", "14:30", 0);
    boolean blocked = false;
    try {
      insert(c, "spike-ov-b", "14:20", "14:40", 0);
    } catch (SQLException e) {
      blocked = true;
      System.out.println("BLOCKED_WITH_REASON=" + e.getMessage().replace('\n', ' '));
    }
    System.out.println("CHECK_SILENT_OVERLAP_BLOCKED ok=" + blocked);
    if (!blocked) {
      failures++;
    }
    boolean waivedOk = true;
    try {
      insert(c, "spike-ov-c", "14:20", "14:40", 1);
    } catch (SQLException e) {
      waivedOk = false;
    }
    System.out.println("CHECK_WAIVED_ALLOWED ok=" + waivedOk);
    if (!waivedOk) {
      failures++;
    }
    return failures;
  }

  private static void insert(Connection c, String id, String startHm, String endHm, int waived)
      throws SQLException {
    OffsetDateTime day = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
    OffsetDateTime st = day.plusHours(Long.parseLong(startHm.substring(0, 2)))
        .plusMinutes(Long.parseLong(startHm.substring(3)));
    OffsetDateTime en = day.plusHours(Long.parseLong(endHm.substring(0, 2)))
        .plusMinutes(Long.parseLong(endHm.substring(3)));
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT OR REPLACE INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
            + "ended_at,duration_ms,end_precision,checkin_date,overlap_waived,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, "count_up");
      ps.setString(3, "finished");
      ps.setString(4, "timer");
      ps.setString(5, "重叠测试");
      ps.setString(6, "unassigned");
      ps.setString(7, st.format(ISO));
      ps.setString(8, en.format(ISO));
      ps.setLong(9, java.time.Duration.between(st, en).toMillis());
      ps.setString(10, "user");
      ps.setString(11, checkinDate(st));
      ps.setInt(12, waived);
      ps.setString(13, st.format(ISO));
      ps.setString(14, en.format(ISO));
      ps.executeUpdate();
    }
  }

  /** 导出：全部产物先写 .tmp，逐个 rename，manifest 最后落 —— 消费方以 manifest 为提交点。 */
  private static void export(Connection c, Path home) throws Exception {
    List<String[]> rows = new ArrayList<>();
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT id,kind,status,origin,task,exclusivity_key,started_at,ended_at,duration_ms,"
                 + "end_precision,checkin_date,missed,needs_review,overlap_waived,cause "
                 + "FROM time_entry WHERE status<>'superseded' ORDER BY started_at")) {
      while (rs.next()) {
        rows.add(new String[] {
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getObject(9) == null ? null : Long.toString(rs.getLong(9)),
            rs.getString(10), rs.getString(11),
            rs.getInt(12) != 0 ? "true" : "false",
            rs.getInt(13) != 0 ? "true" : "false",
            rs.getInt(14) != 0 ? "true" : "false",
            rs.getString(15)});
      }
    }

    List<String> lines = new ArrayList<>();
    StringBuilder csv = new StringBuilder(
        "entry_id,kind,status,origin,task,project,checkin_date,started_at,ended_at,"
        + "duration_ms,end_precision\n");
    for (String[] r : rows) {
      lines.add("{"
          + "\"entry_id\":\"" + r[0] + "\","
          + "\"kind\":\"" + r[1] + "\","
          + "\"status\":\"" + r[2] + "\","
          + "\"origin\":\"" + r[3] + "\","
          + "\"task\":\"" + esc(r[4]) + "\","
          + "\"project\":null,"
          + "\"tags\":[],"
          + "\"exclusivity_key\":\"" + r[5] + "\","
          + "\"checkin_date\":\"" + r[10] + "\","
          + "\"started_at\":\"" + r[6] + "\","
          + (r[7] == null ? "\"ended_at\":null," : "\"ended_at\":\"" + r[7] + "\",")
          + (r[8] == null ? "\"duration_ms\":null," : "\"duration_ms\":" + r[8] + ",")
          + (r[9] == null ? "\"end_precision\":null," : "\"end_precision\":\"" + r[9] + "\",")
          + "\"missed\":" + r[11] + ","
          + "\"needs_review\":" + r[12] + ","
          + "\"overlap_waived\":" + r[13] + ","
          + (r[14] == null ? "\"cause\":null," : "\"cause\":\"" + r[14] + "\",")
          + "\"schema_version\":\"1.0.0\"}");
      csv.append(csvCell(r[0])).append(',').append(csvCell(r[1])).append(',').append(csvCell(r[2]))
          .append(',').append(csvCell(r[3])).append(',').append(csvCell(r[4])).append(',')
          .append(',').append(csvCell(r[10])).append(',').append(csvCell(r[6])).append(',')
          .append(csvCell(r[7])).append(',').append(r[8] == null ? "" : r[8]).append(',')
          .append(csvCell(r[9])).append('\n');
    }

    Path dir = home.resolve("exports");
    String jsonl = String.join("\n", lines) + "\n";
    moveAtomic(dir.resolve("time-entries.jsonl.tmp"), jsonl.getBytes(StandardCharsets.UTF_8),
        dir.resolve("time-entries.jsonl"));
    moveAtomic(dir.resolve("time-entries.csv.tmp"), csv.toString().getBytes(StandardCharsets.UTF_8),
        dir.resolve("time-entries.csv"));

    String sha = sha256(jsonl.getBytes(StandardCharsets.UTF_8));
    String manifest = "{\n"
        + "  \"schema_version\": \"1.0.0\",\n"
        + "  \"app_version\": \"0.0.1-spike\",\n"
        + "  \"generated_at\": \"" + OffsetDateTime.now().format(ISO) + "\",\n"
        + "  \"timer_home\": \"" + home.toAbsolutePath().toString().replace('\\', '/') + "\",\n"
        + "  \"day_attribution\": {\"rule\": \"local_08:00\"},\n"
        + "  \"files\": [{\"name\": \"time-entries.jsonl\", \"kind\": \"time_entries\",\n"
        + "    \"rows\": " + lines.size() + ", \"sha256\": \"" + sha + "\"}],\n"
        + "  \"fields\": {\"time_entries\": [\"entry_id\",\"kind\",\"status\",\"origin\",\"task\","
        + "\"project\",\"tags\",\"exclusivity_key\",\"checkin_date\",\"started_at\",\"ended_at\","
        + "\"duration_ms\",\"end_precision\",\"missed\",\"needs_review\",\"overlap_waived\",\"cause\","
        + "\"schema_version\"]}\n"
        + "}\n";
    moveAtomic(dir.resolve("manifest.json.tmp"), manifest.getBytes(StandardCharsets.UTF_8),
        dir.resolve("manifest.json"));
    System.out.println("EXPORTED rows=" + lines.size() + " sha256=" + sha.substring(0, 12)
        + " dir=" + dir.toAbsolutePath());
  }

  private static String csvCell(String v) {
    if (v == null) {
      return "";
    }
    return v.indexOf(',') >= 0 || v.indexOf('"') >= 0
        ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
  }

  private static void moveAtomic(Path tmp, byte[] content, Path target) throws Exception {
    Files.write(tmp, content);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static int attributionSelfTest() {
    String[][] cases = {
        {"2026-10-06T01:12:03+08:00", "2026-10-05"},
        {"2026-10-06T07:59:59+08:00", "2026-10-05"},
        {"2026-10-06T08:00:00+08:00", "2026-10-06"},
        {"2026-10-06T23:30:00+08:00", "2026-10-06"},
        {"2026-11-01T00:30:00+08:00", "2026-10-31"},
    };
    int failures = 0;
    for (String[] tc : cases) {
      String got = checkinDate(OffsetDateTime.parse(tc[0]));
      boolean ok = got.equals(tc[1]);
      System.out.println("ATTRIB in=" + tc[0] + " out=" + got + " want=" + tc[1] + " ok=" + ok);
      if (!ok) {
        failures++;
      }
    }
    return failures;
  }

  /** 08:00 归日：本地 08:00 之前的记录归前一天。 */
  static String checkinDate(OffsetDateTime at) {
    LocalDateTime local = at.toLocalDateTime();
    return (local.getHour() < 8 ? local.minusDays(1) : local).toLocalDate().toString();
  }

  private static Connection open(Path dbFile) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=3000");
    }
    return c;
  }

  private static void schema(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.executeUpdate("CREATE TABLE IF NOT EXISTS time_entry ("
          + "id TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL,"
          + "origin TEXT NOT NULL DEFAULT 'timer', task TEXT NOT NULL,"
          + "project_id TEXT, exclusivity_key TEXT NOT NULL,"
          + "started_at TEXT NOT NULL, ended_at TEXT, duration_ms INTEGER,"
          + "end_precision TEXT, last_heartbeat_at TEXT, checkin_date TEXT NOT NULL,"
          + "cause TEXT, missed INTEGER NOT NULL DEFAULT 0,"
          + "needs_review INTEGER NOT NULL DEFAULT 0,"
          + "overlap_waived INTEGER NOT NULL DEFAULT 0,"
          + "superseded_by TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entry_key_int ON time_entry"
          + "(exclusivity_key, started_at, ended_at)");
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entry_date ON time_entry(checkin_date)");
      s.executeUpdate("DROP TRIGGER IF EXISTS trg_no_silent_overlap_insert");
      s.executeUpdate("CREATE TRIGGER trg_no_silent_overlap_insert BEFORE INSERT ON time_entry"
          + " WHEN NEW.overlap_waived = 0 AND NEW.ended_at IS NOT NULL"
          + " AND NEW.status NOT IN ('aborted','superseded') BEGIN"
          + " SELECT RAISE(ABORT, 'overlap: 同 exclusivity_key 已有相交区间，需用户确认')"
          + " FROM time_entry e WHERE e.exclusivity_key = NEW.exclusivity_key AND e.id <> NEW.id"
          + " AND e.status NOT IN ('aborted','superseded')"
          + " AND substr(e.started_at,1,19) < substr(NEW.ended_at,1,19)"
          + " AND substr(NEW.started_at,1,19) < substr(COALESCE(e.ended_at,'9999'),1,19); END");
    }
  }

  private static String sha256(byte[] data) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder sb = new StringBuilder();
    for (byte b : d) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String arg(String[] args, String key, String fallback) {
    for (String a : args) {
      if (a.startsWith(key + "=")) {
        return a.substring(key.length() + 1);
      }
    }
    return fallback;
  }
}
