package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 把 spike 里验过的"静默重叠必须被拦、放行必须能过"固化成常驻测试。
 * 主判据在应用层事务（S5），这里是触发器兜底那一层。
 */
class TimeEntryOverlapTest {

  private DataSource ds;
  private Connection c;

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    c = ds.conn();
    Migrations.migrate(c);
    try (Statement s = c.createStatement()) {
      s.execute("DELETE FROM time_entry");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    // 不关连接的话 .db-wal 一直被占着，Windows 上 JUnit 删不掉 @TempDir
    ds.close();
  }

  @Test
  void blocksSilentOverlapOnSameExclusivityKey() throws Exception {
    insert("a", "2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00", 0);
    SQLException ex = assertThrows(SQLException.class,
        () -> insert("b", "2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00", 0));
    assertTrue(ex.getMessage().toLowerCase().contains("overlap"), ex.getMessage());
  }

  @Test
  void allowsWaivedOverlapAndDifferentKeys() throws Exception {
    insert("a", "2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00", 0);
    insert("b", "2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00", 1);
    insert("c", "2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00", "prj:other", 0);
  }

  /**
   * M2-g 的主判据：反重叠不能只看 INSERT。两段都在跑时插入阶段谁都没有 {@code ended_at}，
   * 相交是**收表那一刻才成形**的 —— 所以 UPDATE 写 ended_at 时必须有同一条判据。
   */
  @Test
  void closingARunningRowIntoUnwaivedOverlapIsBlocked() throws Exception {
    insertOpen("oa", "unassigned", "2026-10-06T14:00:00+08:00", 0);
    insertOpen("ob", "unassigned", "2026-10-06T14:20:00+08:00", 0);
    SQLException ex = assertThrows(SQLException.class,
        () -> close("oa", "2026-10-06T15:00:00+08:00", 0));
    assertTrue(ex.getMessage().toLowerCase().contains("overlap"), ex.getMessage());
  }

  /** 送进对账队列的那条路必须走得通：标了"待确认"就不是静默重叠，导出侧此刻还不认它。 */
  @Test
  void closingIntoOverlapIsAllowedWhenMarkedPending() throws Exception {
    insertOpen("oa", "unassigned", "2026-10-06T14:00:00+08:00", 0);
    insertOpen("ob", "unassigned", "2026-10-06T14:20:00+08:00", 0);
    close("oa", "2026-10-06T15:00:00+08:00", 1);
  }

  /** 另一边已经放行（并发起段那一刻的确认）⇒ 这对区间是合法的，关表不该被触发器拦住。 */
  @Test
  void closingIntoOverlapIsAllowedWhenTheOtherSideIsWaived() throws Exception {
    insertOpen("oa", "unassigned", "2026-10-06T14:00:00+08:00", 0);
    insertOpen("ob", "unassigned", "2026-10-06T14:20:00+08:00", 1);
    close("oa", "2026-10-06T15:00:00+08:00", 0);
  }

  /** 不同桶照旧放行；而**已经闭上过的行**再改标记位不许被再次拦住。 */
  @Test
  void otherKeyAndAlreadyClosedRowsAreNotBlocked() throws Exception {
    insertOpen("oc", "prj:other", "2026-10-06T14:00:00+08:00", 0);
    insertOpen("od", "unassigned", "2026-10-06T14:20:00+08:00", 0);
    close("oc", "2026-10-06T15:00:00+08:00", 0);
    // 旧版遗留的一对静默重叠（两边都已闭上、都没放行）：只改标记位必须过得去，
    // 否则对账卡与"知道了"都会抛，应用连清理历史的能力都没有
    insert("oe", "2026-10-06T14:00:00+08:00", "2026-10-06T15:00:00+08:00", 1);
    insertOpen("of", "unassigned", "2026-10-06T14:20:00+08:00", 0);
    close("of", "2026-10-06T14:40:00+08:00", 1);
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE time_entry SET needs_review=1, missed=1, updated_at=? WHERE id='of'")) {
      ps.setString(1, "2026-10-06T14:40:00+08:00");
      ps.executeUpdate();
    }
  }

  private void insertOpen(String id, String key, String start, int waived) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,"
            + "end_precision,checkin_date,overlap_waived,created_at,updated_at)"
            + " VALUES (?, 'count_up', 'running', 'timer', '重叠测试', ?, ?, 'user',"
            + " '2026-10-06', ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, key);
      ps.setString(3, start);
      ps.setInt(4, waived);
      ps.setString(5, start);
      ps.setString(6, start);
      ps.executeUpdate();
    }
  }

  /** 关表那一刻：pending=1 表示"这条已送进对账队列，还没人认"。 */
  private void close(String id, String end, int pending) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE time_entry SET status='finished', ended_at=?, duration_ms=1800000,"
            + " end_precision='user', overlap_pending=?, updated_at=? WHERE id=?")) {
      ps.setString(1, end);
      ps.setInt(2, pending);
      ps.setString(3, end);
      ps.setString(4, id);
      ps.executeUpdate();
    }
  }

  private void insert(String id, String start, String end, int waived) throws SQLException {
    insert(id, start, end, "unassigned", waived);
  }

  private void insert(String id, String start, String end, String key, int waived)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO time_entry (id,kind,status,origin,task,exclusivity_key,started_at,ended_at,"
            + "duration_ms,end_precision,checkin_date,overlap_waived,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, "count_up");
      ps.setString(3, "finished");
      ps.setString(4, "timer");
      ps.setString(5, "重叠测试");
      ps.setString(6, key);
      ps.setString(7, start);
      ps.setString(8, end);
      ps.setLong(9, 1800000);
      ps.setString(10, "user");
      ps.setString(11, "2026-10-06");
      ps.setInt(12, waived);
      ps.setString(13, start);
      ps.setString(14, end);
      ps.executeUpdate();
    }
  }
}
