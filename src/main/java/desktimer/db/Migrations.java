package desktimer.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 迁移链：PRAGMA user_version 就是 schema_version 的整数形态（1.0.0 -> 10000）。
 * 规则：只加不改，新增结构请追加 00N_*.sql 并把版本号写进 CHAIN。
 */
public final class Migrations {

  public static final int TARGET_VERSION = 10200;

  private static final Migration[] CHAIN = {
      new Migration(10000, "/schema/001_init.sql"),
      new Migration(10100, "/schema/002_target_ms.sql"),
      new Migration(10200, "/schema/003_overlap_pending.sql"),
  };

  private Migrations() {
  }

  public static int migrate(Connection c) throws SQLException, IOException {
    int v = version(c);
    for (Migration m : CHAIN) {
      if (v < m.version) {
        for (String stmt : SqlSplitter.split(readResource(m.resource))) {
          try (Statement s = c.createStatement()) {
            s.execute(stmt);
          }
        }
        setVersion(c, m.version);
        v = m.version;
      }
    }
    return version(c);
  }

  public static int version(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("PRAGMA user_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  private static void setVersion(Connection c, int version) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA user_version=" + version);
    }
  }

  static String readResource(String path) throws IOException {
    try (InputStream in = Migrations.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("找不到迁移脚本资源：" + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private record Migration(int version, String resource) {
  }
}
