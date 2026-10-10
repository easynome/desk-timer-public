package desktimer.db;

import desktimer.core.time.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 键值设置（窗口位置、钉住状态等）。
 * 存库而不是存外部文件：这样一次备份 zip 就能带走全部状态。
 *
 * 这些值只在界面线程读写，但仍要过连接锁 —— 心跳线程正在同一条连接上跑。
 */
public final class SettingRepository {

  private final Connection c;
  private final Clock clock;
  private final Object lock;

  public SettingRepository(DataSource ds, Clock clock) {
    this.c = ds.conn();
    this.clock = clock;
    this.lock = ds.lock();
  }

  public String get(String key, String fallback) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT value FROM setting WHERE key=?")) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : fallback;
        }
      }
    }
  }

  public void put(String key, String value) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO setting (key, value, updated_at) VALUES (?,?,?)"
              + " ON CONFLICT(key) DO UPDATE SET value=excluded.value,"
              + " updated_at=excluded.updated_at")) {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.setString(3, clock.wall());
        ps.executeUpdate();
      }
    }
  }
}
