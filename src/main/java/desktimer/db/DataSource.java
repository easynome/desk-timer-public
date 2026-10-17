package desktimer.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 主库连接。单写者规则：只有应用进程持有写连接；CLI 只读（mode=ro）。
 * WAL 让外部只读消费者能在应用运行时读，但跨工具交换仍首选 exports/ 文件。
 */
public final class DataSource implements AutoCloseable {

  private final Connection connection;

  /**
   * 这一个连接上的所有读写共用的锁。
   * sqlite-jdbc 的 Connection 不是线程安全的，而本项目至少有三个线程碰它：
   * JavaFX 线程（界面建条目、存窗口位置）和心跳/导出池。
   * 每个仓储各自一把锁挡不住跨仓储的并发 —— 所以锁由连接的所有者给，不由仓储自己造。
   */
  private final Object lock = new Object();

  public DataSource(Path dbFile) throws SQLException {
    this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    try (Statement s = connection.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=3000");
      s.execute("PRAGMA synchronous=NORMAL");
    }
  }

  public Connection conn() {
    return connection;
  }

  public Object lock() {
    return lock;
  }

  @FunctionalInterface
  public interface Work<T> {
    T run() throws SQLException;
  }

  /** 一次业务动作的提交边界；所有仓储与后台任务仍共用连接锁。 */
  public <T> T transaction(Work<T> work) throws SQLException {
    synchronized (lock) {
      boolean outer = connection.getAutoCommit();
      java.sql.Savepoint savepoint = null;
      if (outer) connection.setAutoCommit(false);
      else savepoint = connection.setSavepoint();
      try {
        T result = work.run();
        if (outer) connection.commit();
        else connection.releaseSavepoint(savepoint);
        return result;
      } catch (SQLException | RuntimeException | Error failure) {
        try {
          if (outer) connection.rollback();
          else connection.rollback(savepoint);
        } catch (SQLException rollback) { failure.addSuppressed(rollback); }
        throw failure;
      } finally {
        if (outer) connection.setAutoCommit(true);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    synchronized (lock) {
      try (Statement s = connection.createStatement()) {
        s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      } finally { connection.close(); }
    }
  }
}
