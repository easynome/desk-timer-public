package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 番茄的轮次账。两条判据：
 *  - **round 在开始时写**（不是结束时）：面板一抬头就要知道"这是第几个"，
 *    而且中途崩溃/断电时那条 interrupted 行也得带着轮次，否则账断了；
 *  - **统计只算 completed**：放弃的番茄保留 round 但不进计数（PRD 第 1 条），
 *    所以同一轮号可能出现两行，消费方按 status 过滤。
 */
class PomodoroRepositoryTest {

  private DataSource ds;
  private EntryRepository entries;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path dir) throws Exception {
    ds = new DataSource(dir.resolve("t.db"));
    Migrations.migrate(ds.conn());
    entries = new EntryRepository(ds, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  @Test
  void roundIsWrittenAtStartAndKeptWhenAborted() throws Exception {
    Entry e = entries.startPomodoro("写周报", null, 25 * 60_000L, 1, false);
    assertEquals(EntryStatus.RUNNING, e.status());
    assertEquals(1, roundOf(e.id()));
    assertEquals("pomodoro_work", kindOf(e.id()));

    entries.abort(e.id(), "中途去开会", 60_000L);
    assertEquals(EntryStatus.ABORTED, entries.find(e.id()).orElseThrow().status());
    assertEquals(1, roundOf(e.id()), "放弃也要留轮次：那一行是\"第 1 轮没干完\"的证据");
    assertEquals(0, entries.completedPomodoros(today()), "但计数里不算它");
  }

  @Test
  void onlyFinishedPomodorosAreCountedAndTheyAccumulate() throws Exception {
    Entry a = entries.startPomodoro("第一段", null, 25 * 60_000L, 1, false);
    assertEquals(0, entries.completedPomodoros(today()));
    entries.finish(a.id(), 25 * 60_000L);
    assertEquals(1, entries.completedPomodoros(today()));

    Entry b = entries.startPomodoro("第二段", null, 40 * 60_000L, 2, false);
    entries.finish(b.id(), 40 * 60_000L);
    assertEquals(2, entries.completedPomodoros(today()));

    // 休息段不参与番茄计数，哪怕它也是"完成"的
    entries.start("短休", EntryKind.REST_SHORT, null, 5 * 60_000L, false);
    assertEquals(2, entries.completedPomodoros(today()));
  }

  /** 跨归属日重新计数：昨天的番茄不该让今天的第一个变成"第 5 个"。 */
  @Test
  void countingIsScopedToOneCheckinDay() throws Exception {
    Entry today = entries.startPomodoro("今天第一个", null, 25 * 60_000L, 1, false);
    entries.finish(today.id(), 25 * 60_000L);
    Entry yesterday = entries.startPomodoro("昨天做的", null, 25 * 60_000L, 3, false);
    entries.finish(yesterday.id(), 25 * 60_000L);
    stampDay(yesterday.id(), "2026-09-30");

    assertEquals(1, entries.completedPomodoros(today()), "昨天的两条不进今天的账");
    assertEquals(1, entries.completedPomodoros("2026-09-30"), "归属日单独可查");
  }

  /** 非番茄的 kind 一律不许带 round：带了就是给消费方埋坑。 */
  @Test
  void ordinarySessionsCarryNoRound() throws Exception {
    Entry e = entries.start("普通倒计时", EntryKind.COUNT_DOWN, null, 10 * 60_000L, false);
    assertEquals(0, roundOf(e.id()), "没写就是 null，读成 0");
    assertTrue(kindOf(e.id()).equals("count_down"));
  }

  private String today() {
    return desktimer.core.time.DayAttribution.checkinDate(clock.wall());
  }

  private void stampDay(String id, String day) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "UPDATE time_entry SET checkin_date=? WHERE id=?")) {
        ps.setString(1, day);
        ps.setString(2, id);
        ps.executeUpdate();
      }
    }
  }

  private int roundOf(String id) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT pomodoro_round FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    }
  }

  private String kindOf(String id) throws Exception {
    synchronized (ds.lock()) {
      try (PreparedStatement ps = ds.conn().prepareStatement(
          "SELECT kind FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getString(1);
        }
      }
    }
  }

}
