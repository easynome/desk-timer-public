package desktimer.db;

import desktimer.core.time.Clock;
import desktimer.core.time.DayAttribution;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 待办的读写入口。和 {@link NoteRepository} 一样：**所有公开方法都在连接锁内**。
 *
 * 三条口径值得写下来，因为它们都是"以后容易改错"的地方：
 *  - 归日按创建时刻（08:00 边界），完成时刻另存一列，两种问法各有答案；
 *  - 完成只改状态、不删行 —— 打卡要的是"今天干成了什么"，删掉就没了；
 *  - 丢弃（{@link #remove}）才真删，但只删待办与自己那条链接：
 *    {@code todo_entry} 对 todo_id 是 CASCADE，对 time_entry 侧只是引用，
 *    所以补录出来的计时条目不会跟着消失 —— 那是证据。
 */
public final class TodoRepository {

  private static final String SELECT =
      "SELECT id, title, done, completed_at, checkin_date, project_id, created_at FROM todo";

  private final Connection c;
  private final Clock clock;
  private final Object lock;
  private final DataSource ds;

  public TodoRepository(DataSource ds, Clock clock) {
    this.ds = ds;
    this.c = ds.conn();
    this.clock = clock;
    this.lock = ds.lock();
  }

  /** 重试复用已完成的关联条目；任一中间失败全部回滚。 */
  public Entry completeWithBackfill(String todoId, EntryRepository entries,
      String started, String ended) throws SQLException {
    return ds.transaction(() -> {
      Todo todo = require(todoId);
      if (todo.done()) {
        if (todo.entryIds().isEmpty()) throw new IllegalStateException("待办已完成，未再补录时间");
        return entries.find(todo.entryIds().get(0)).orElseThrow();
      }
      Entry entry = entries.finishBackfill(todo.title(), todo.projectId(), started, ended);
      entries.attachTags(entry.id(), todo.tags());
      linkEntry(todoId, entry.id());
      complete(todoId);
      return entry;
    });
  }

  /** 空标题在边界上就拒：卡片上一行空白没法解释，也没法"完成"。 */
  public Todo add(String title, String projectId, List<String> tags) throws SQLException {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("待办标题为空");
    }
    synchronized (lock) {
      String id = UUID.randomUUID().toString();
      String at = clock.wall();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO todo (id, title, done, completed_at, checkin_date, project_id, pinned,"
              + " created_at, updated_at) VALUES (?,?,0,?,?,?,0,?,?)")) {
        ps.setString(1, id);
        ps.setString(2, title.trim());
        ps.setNull(3, java.sql.Types.VARCHAR);
        ps.setString(4, DayAttribution.checkinDate(at));
        ps.setString(5, projectId);
        ps.setString(6, at);
        ps.setString(7, at);
        ps.executeUpdate();
      }
      Tags.link(c, clock, "todo_tag", "todo_id", id, tags);
      return require(id);
    }
  }

  /** 幂等：已经完成的再按一次不重打时间戳，否则"今天几点干成的"会变成"几点又按了一下"。 */
  public Todo complete(String id) throws SQLException {
    synchronized (lock) {
      Todo t = require(id);
      if (t.done()) {
        return t;
      }
      String now = clock.wall();
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE todo SET done=1, completed_at=?, updated_at=? WHERE id=? AND done=0")) {
        ps.setString(1, now);
        ps.setString(2, now);
        ps.setString(3, id);
        ps.executeUpdate();
      }
      return require(id);
    }
  }

  /** 挂上补录出来的计时条目。重复挂不报错：链接表的主键就是 (todo_id, entry_id)。 */
  public Todo linkEntry(String todoId, String entryId) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO todo_entry (todo_id, entry_id, linked_at) VALUES (?,?,?)"
              + " ON CONFLICT(todo_id, entry_id) DO NOTHING")) {
        ps.setString(1, todoId);
        ps.setString(2, entryId);
        ps.setString(3, clock.wall());
        ps.executeUpdate();
      }
      return require(todoId);
    }
  }

  public Optional<Todo> find(String id) throws SQLException {
    synchronized (lock) {
      List<Todo> rows = query(SELECT + " WHERE id=?", id);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
  }

  /** 未完成清单，卡片用它。排序是 (created_at, id)：跨秒稳定往后排，同一秒内按 id 定序（导出要的是确定性，不是输入顺序）。 */
  public List<Todo> pending() throws SQLException {
    synchronized (lock) {
      return query(SELECT + " WHERE done=0 ORDER BY created_at, id", null);
    }
  }

  public List<Todo> recentPending() throws SQLException {
    synchronized (lock) {
      return query(SELECT + " WHERE done=0 ORDER BY created_at DESC,rowid DESC LIMIT 200", null);
    }
  }

  /** 导出用：全量、同一份库两次导出必须字节相同。 */
  public List<Todo> all() throws SQLException {
    synchronized (lock) {
      return query(SELECT + " ORDER BY created_at, id", null);
    }
  }

  /** 丢弃一条待办。计时条目不受影响（见类注释的第三条）。 */
  public void remove(String id) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM todo WHERE id=?")) {
        ps.setString(1, id);
        ps.executeUpdate();
      }
    }
  }

  private List<Todo> query(String sql, String arg) throws SQLException {
    List<Todo> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      if (arg != null) {
        ps.setString(1, arg);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new Todo(rs.getString("id"), rs.getString("title"), rs.getInt("done") == 1,
              rs.getString("completed_at"), rs.getString("checkin_date"), rs.getString("project_id"),
              rs.getString("created_at"), Tags.tagsOf(c, "todo_tag", "todo_id", rs.getString("id")),
              entryIdsOf(rs.getString("id"))));
        }
      }
    }
    return out;
  }

  /** 链接按 (linked_at, entry_id) 取：一次补录挂几条也有先后，导出要稳定。 */
  private List<String> entryIdsOf(String todoId) throws SQLException {
    List<String> ids = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT entry_id FROM todo_entry WHERE todo_id=? ORDER BY linked_at, entry_id")) {
      ps.setString(1, todoId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    }
    return ids;
  }

  private Todo require(String id) throws SQLException {
    List<Todo> rows = query(SELECT + " WHERE id=?", id);
    if (rows.isEmpty()) {
      throw new IllegalStateException("找不到刚写入的待办 " + id);
    }
    return rows.get(0);
  }
}
