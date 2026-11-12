import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Restore-drill helper: reads ONE database file and prints the facts a restore must prove.
 *
 * Kept separate from the app on purpose: a drill that asks the product to verify itself
 * proves nothing. This program opens the restored file read-only-ish (no writes) and prints
 * integrity_check, the migration version, and per-table row counts as one JSON line,
 * so restore-drill.ps1 can compare them against the zip's backup-manifest.json.
 *
 * Run: java -cp "target/lib/sqlite-jdbc-*.jar" tools/RestoreVerify.java <path-to-timer.db>
 * ASCII only in this file: it is compiled by javac/java on a box whose default charset is GBK.
 */
public class RestoreVerify {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("{\"error\":\"usage: RestoreVerify <timer.db>\"}");
      System.exit(2);
    }
    Properties cfg = new Properties();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[0], cfg)) {
      StringBuilder out = new StringBuilder("{");
      try (Statement s = c.createStatement()) {
        append(out, "integrity", scalar(s, "PRAGMA integrity_check"));
        append(out, "user_version", Integer.parseInt(scalar(s, "PRAGMA user_version")));
        append(out, "time_entry", count(s, "time_entry"));
        append(out, "note", count(s, "note"));
        append(out, "todo", count(s, "todo"));
        append(out, "superseded", countWhere(s, "time_entry", "status='superseded'"));
        append(out, "overlap_pending", countWhere(s, "time_entry", "overlap_pending=1"));
        append(out, "unwaived_overlap", countOf(s,
            "SELECT count(*) FROM time_entry a JOIN time_entry b"
                + " ON a.exclusivity_key=b.exclusivity_key AND a.id<b.id"
                + " WHERE a.status NOT IN ('aborted','superseded')"
                + " AND b.status NOT IN ('aborted','superseded')"
                + " AND a.overlap_waived=0 AND b.overlap_waived=0"
                + " AND a.ended_at IS NOT NULL AND b.ended_at IS NOT NULL"
                + " AND substr(a.started_at,1,19) < substr(b.ended_at,1,19)"
                + " AND substr(b.started_at,1,19) < substr(a.ended_at,1,19)"));
      }
      append(out, "file", args[0]);
      out.append('}');
      System.out.println(out);
    }
  }

  private static void append(StringBuilder sb, String key, Object value) {
    if (sb.length() > 1) {
      sb.append(',');
    }
    sb.append('"').append(key).append("\":");
    if (value instanceof String) {
      // Windows 路径里的反斜杠必须转义，否则输出不是合法 JSON（ConvertFrom-Json 当场就红）
      sb.append('"').append(((String) value).replace("\\", "\\\\")).append('"');
    } else {
      sb.append(value);
    }
  }

  private static String scalar(Statement s, String sql) throws Exception {
    try (ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : "";
    }
  }

  private static int count(Statement s, String table) throws Exception {
    return Integer.parseInt(scalar(s, "SELECT count(*) FROM " + table));
  }

  private static int countOf(Statement s, String fullQuery) throws Exception {
    return Integer.parseInt(scalar(s, fullQuery));
  }

  private static int countWhere(Statement s, String table, String where) throws Exception {
    return Integer.parseInt(scalar(s, "SELECT count(*) FROM " + table + " WHERE " + where));
  }
}
