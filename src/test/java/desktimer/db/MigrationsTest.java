package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

  @Test
  void migratesToTargetVersionAndIsIdempotent(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Connection c = ds.conn();
      assertEquals(0, Migrations.version(c), "新库的 user_version 应为 0");
      assertEquals(Migrations.TARGET_VERSION, Migrations.migrate(c));
      assertEquals(Migrations.TARGET_VERSION, Migrations.migrate(c), "重复迁移必须无害");
      assertEquals(12, count(c, "type='table' AND name NOT LIKE 'sqlite_%'"));
      assertEquals(1, count(c, "type='trigger' AND name='trg_no_silent_overlap_insert'"));
      assertEquals(1, count(c, "type='trigger' AND name='trg_no_silent_overlap_update'"),
          "003 的收表判据必须存在：并发下的相交是 UPDATE 那一刻才成形的");
      assertEquals(4, count(c, "type='index' AND name LIKE 'idx_%'"));
    }
  }

  private static int count(Connection c, String where) throws Exception {
    try (Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT count(*) FROM sqlite_master WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
