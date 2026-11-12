package desktimer.db;

import static org.junit.jupiter.api.Assertions.*;
import desktimer.core.time.Clock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BusinessTransactionTest {
  private static final String START = "2026-10-16T09:00:00+08:00";
  private static final String END = "2026-10-16T09:25:00+08:00";

  @Test void timelineKeepsBackfillOrigin(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      var entries = new EntryRepository(ds, new Clock());
      var imported = entries.importBackfill("origin-1", "hash", "task", null, List.of(), START, END);
      var item = entries.timeline("2026-10-16", 500).stream().filter(t -> t.id().equals(imported.entry().id())).findFirst().orElseThrow();
      assertEquals("manual_backfill", item.origin());
    }
  }

  @Test
  void failedTagWriteRollsBackEntryProjectAndReceipt(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      EntryRepository entries = new EntryRepository(ds, new Clock());
      try (var s = ds.conn().createStatement()) {
        s.execute("CREATE TRIGGER fail_tag BEFORE INSERT ON entry_tag BEGIN SELECT RAISE(ABORT,'injected'); END");
      }
      assertThrows(SQLException.class, () -> entries.importBackfill("id-1", "hash", "task",
          "project", List.of("tag"), START, END));
      assertEquals(0, count(ds, "time_entry"));
      assertEquals(0, count(ds, "project"));
      assertEquals(0, count(ds, "import_receipt"));
      assertTrue(ds.conn().getAutoCommit());
    }
  }

  @Test
  void receiptReplaysSameEntryAndRejectsDifferentPayload(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      EntryRepository entries = new EntryRepository(ds, new Clock());
      var first = entries.importBackfill("id-1", "hash", "task", null, List.of(), START, END);
      var retry = entries.importBackfill("id-1", "hash", "task", null, List.of(), START, END);
      assertFalse(first.replayed());
      assertTrue(retry.replayed());
      assertEquals(first.entry().id(), retry.entry().id());
      assertThrows(IllegalArgumentException.class, () -> entries.importBackfill("id-1", "other",
          "different", null, List.of(), START, END));
      assertEquals(1, count(ds, "time_entry"));
      assertEquals(1, count(ds, "import_receipt"));
    }
  }

  @Test
  void todoLinkFailureRollsBackAndRetryCommitsOnlyOnce(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      Clock clock = new Clock();
      EntryRepository entries = new EntryRepository(ds, clock);
      TodoRepository todos = new TodoRepository(ds, clock);
      Todo todo = todos.add("task", null, List.of("tag"));
      try (var s = ds.conn().createStatement()) {
        s.execute("CREATE TRIGGER fail_link BEFORE INSERT ON todo_entry BEGIN SELECT RAISE(ABORT,'injected'); END");
      }
      assertThrows(SQLException.class, () -> todos.completeWithBackfill(todo.id(), entries, START, END));
      assertEquals(0, count(ds, "time_entry"));
      assertEquals(0, count(ds, "todo_entry"));
      assertFalse(todos.find(todo.id()).orElseThrow().done());
      try (var s = ds.conn().createStatement()) { s.execute("DROP TRIGGER fail_link"); }
      Entry first = todos.completeWithBackfill(todo.id(), entries, START, END);
      Entry retry = todos.completeWithBackfill(todo.id(), entries, START, END);
      assertEquals(first.id(), retry.id());
      assertEquals(1, count(ds, "time_entry"));
      assertTrue(todos.find(todo.id()).orElseThrow().done());
      assertEquals(1, count(ds, "entry_tag"));
    }
  }

  private int count(DataSource ds, String table) throws Exception {
    try (var s = ds.conn().createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test void failedBreakCreationDoesNotPauseWork(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      EntryRepository entries = new EntryRepository(ds, new Clock());
      Entry work = entries.start("work", desktimer.core.time.EntryKind.COUNT_UP, null, false);
      try (var s = ds.conn().createStatement()) {
        s.execute("CREATE TRIGGER fail_break BEFORE INSERT ON time_entry WHEN NEW.kind='rest_long' BEGIN SELECT RAISE(ABORT,'injected'); END");
      }
      assertThrows(SQLException.class, () -> entries.startBreak("rest", desktimer.core.time.EntryKind.REST_LONG,
          600_000, java.util.Map.of(work.id(), 60_000L)));
      assertEquals(desktimer.core.time.EntryStatus.RUNNING, entries.find(work.id()).orElseThrow().status());
      assertEquals(1, count(ds, "time_entry"));
    }
  }

  @Test void pausedRecoveryKeepsMeasuredDurationRatherThanCountingTheBreak(@TempDir Path dir) throws Exception {
    try (DataSource ds = new DataSource(dir.resolve("t.db"))) {
      Migrations.migrate(ds.conn());
      EntryRepository entries = new EntryRepository(ds, new Clock());
      Entry work = entries.start("work", desktimer.core.time.EntryKind.COUNT_UP, null, false);
      entries.pauseMeasured(work.id(), 60_000);
      entries.touchMeasurements(java.util.Map.of(work.id(), 50_000L));
      entries.reconcileRunning(desktimer.core.time.Recovery.Cause.CRASH);
      assertEquals(60_000L, entries.find(work.id()).orElseThrow().durationMs());
    }
  }
}
