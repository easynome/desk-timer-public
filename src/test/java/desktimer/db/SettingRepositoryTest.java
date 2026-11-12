package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingRepositoryTest {

  private DataSource ds;
  private SettingRepository settings;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    settings = new SettingRepository(ds, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void missingKeyYieldsFallback() throws Exception {
    assertEquals("120,240", settings.get("ui.panel.bounds", "120,240"));
    assertNull(settings.get("nope", null));
  }

  @Test
  void putOverwritesInsteadOfAddingASecondRow() throws Exception {
    settings.put("ui.panel.pinned", "false");
    settings.put("ui.panel.pinned", "true");
    assertEquals("true", settings.get("ui.panel.pinned", "?"));
    try (Statement s = ds.conn().createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM setting")) {
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void stampsUpdatedAt() throws Exception {
    settings.put("k", "v");
    try (Statement s = ds.conn().createStatement();
        ResultSet rs = s.executeQuery("SELECT updated_at FROM setting WHERE key='k'")) {
      rs.next();
      assertNotNull(rs.getString(1));
    }
  }

  /**
   * 这条是连接锁的回归：JavaFX 线程存窗口位置、池线程写心跳，共用一条 sqlite 连接。
   * 没有 DataSource#lock 时这里会随机抛 "statement closed" 之类的错。
   */
  @Test
  void concurrentWritersOnOneConnection() throws Exception {
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    Runnable body = () -> {
      try {
        go.await();
        for (int i = 0; i < 150; i++) {
          settings.put(Thread.currentThread().getName(), "v" + i);
        }
      } catch (Throwable t) {
        errors.add(t);
      } finally {
        done.countDown();
      }
    };
    Thread a = new Thread(body, "fx-thread-standin");
    Thread b = new Thread(body, "heartbeat-standin");
    a.start();
    b.start();
    go.countDown();
    assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "并发写没跑完");
    assertTrue(errors.isEmpty(), "并发写报错: " + errors);
    assertEquals("v149", settings.get("fx-thread-standin", "?"));
    assertEquals("v149", settings.get("heartbeat-standin", "?"));
  }
}
