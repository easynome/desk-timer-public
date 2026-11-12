package desktimer.db;

import desktimer.core.time.Clock;
import desktimer.core.time.DayAttribution;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 随手记的读写入口。和 {@link EntryRepository} 一样：**所有公开方法都在连接锁内**，
 * 心跳/导出跑在池线程，写笔记跑在 JavaFX 线程，共用同一条 sqlite 连接。
 */
public final class NoteRepository {

  private static final String SELECT =
      "SELECT id, body_md, created_at, checkin_date, project_id, entry_id, pinned FROM note";

  /**
   * 搜索一次的返回上限：卡是边打字边查的交互，全库倒进 FX 线程会真的卡住界面。
   * 公开它是因为界面上要写"已到上限，继续打几个字"—— 截断必须让用户看得见，
   * 悄悄少给几行是最坏的一种"看起来一切正常"。
   */
  public static final int SEARCH_LIMIT = 200;

  private final Connection c;
  private final Clock clock;
  private final Object lock;

  public NoteRepository(DataSource ds, Clock clock) {
    this.c = ds.conn();
    this.clock = clock;
    this.lock = ds.lock();
  }

  /** 空正文在边界上就拒：时间轴里出现一条空白比拒掉更难解释。 */
  public Note add(String body, String projectId, String entryId, List<String> tags)
      throws SQLException {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("笔记正文为空");
    }
    synchronized (lock) {
      String id = UUID.randomUUID().toString();
      String at = clock.wall();
      String day = DayAttribution.checkinDate(OffsetDateTime.parse(at));
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO note (id, body_md, created_at, checkin_date, entry_id, project_id,"
              + " pinned, updated_at) VALUES (?,?,?,?,?,?,0,?)")) {
        ps.setString(1, id);
        ps.setString(2, body.trim());
        ps.setString(3, at);
        ps.setString(4, day);
        ps.setString(5, entryId);
        ps.setString(6, projectId);
        ps.setString(7, at);
        ps.executeUpdate();
      }
      Tags.link(c, clock, "note_tag", "note_id", id, tags);
      return require(id);
    }
  }

  public Optional<Note> find(String id) throws SQLException {
    synchronized (lock) {
      List<Note> rows = query(SELECT + " WHERE id=?", id);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
  }

  /** 按归属日取（08:00 边界），时间轴用它。 */
  public List<Note> forDate(String checkinDate) throws SQLException {
    synchronized (lock) {
      return query(SELECT + " WHERE checkin_date=? ORDER BY created_at, id", checkinDate);
    }
  }

  /** 导出用：全量、稳定排序（created_at, id），同一份库两次导出必须字节相同。 */
  public List<Note> all() throws SQLException {
    synchronized (lock) {
      return query(SELECT + " ORDER BY created_at, id");
    }
  }

  /** 变参：默认视图那条 SQL 要绑两次同一个归属日（WHERE 与排序各一次）。 */
  private List<Note> query(String sql, String... args) throws SQLException {
    List<Note> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setString(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    // 标签单独查：一条笔记的标签是个位数，为它写一遍 GROUP_CONCAT 反而难读
    return withTags(out);
  }

  private List<Note> withTags(List<Note> rows) throws SQLException {
    List<Note> out = new ArrayList<>();
    for (Note n : rows) {
      out.add(new Note(n.id(), n.body(), n.createdAt(), n.checkinDate(), n.projectId(),
          n.entryId(), n.pinned(), Tags.tagsOf(c, "note_tag", "note_id", n.id())));
    }
    return out;
  }

  /**
   * 笔记卡的默认视图：今天 ∪ 所有置顶。置顶排在最前（它的全部意义就是"跨归属日也常驻"），
   * 今天的按写入倒序 —— 刚记的那条离手最近，不该沉到底下。
   *
   * 同一天同一秒记两条是常态（`created_at` 只到秒），所以平局用 {@code rowid} 破：
   * 它是插入序，`id` 却是随机 UUID —— 拿 id 破平局的话"新的在上面"根本立不住。
   */
  public List<Note> pinnedFirst(String checkinDate) throws SQLException {
    synchronized (lock) {
      return query(SELECT + " WHERE pinned=1 OR checkin_date=?"
          + " ORDER BY pinned DESC,"
          + " CASE WHEN checkin_date=? THEN 0 ELSE 1 END,"
          + " created_at DESC, rowid DESC", checkinDate, checkinDate);
    }
  }

  /**
   * 全库搜索。界面上写着"搜正文 / #标签 / @项目"，所以这三个前缀就是范围：
   * 打 {@code #} 只按标签名找、打 {@code @} 只按项目名找、不加符号三处都搜（宽松）。
   * 全角 {@code ＃}/{@code ＠} 一样认 —— 这台机器上输入法吞半角符号是老问题。
   *
   * 空白、null、以及"只打了个符号"一律回空表 —— 不许把整库倒进卡里。
   *
   * 转义 {@code %}/{@code _} 是因为查询词是用户输入的：他打百分号想要的是那个字符，
   * 不是通配符（边界上的输入不许当成语法）。
   *
   * 排序同样用 {@code rowid} 破平局，而且这里更要紧：结果有 {@value #SEARCH_LIMIT} 条的截断，
   * 平局若由随机 id 决定，同一个词两次搜索可能带进带出不同的行。
   */
  public List<Note> search(String q) throws SQLException {
    if (q == null || q.isBlank()) {
      return List.of();
    }
    String term = q.trim();
    char sigil = term.charAt(0);
    boolean tagOnly = sigil == '#' || sigil == '＃';
    boolean projectOnly = sigil == '@' || sigil == '＠';
    String word = (tagOnly || projectOnly) ? term.substring(1).trim() : term;
    if (word.isEmpty()) {
      return List.of();
    }
    String like = "%" + escapeLike(word) + "%";
    String byTag = "id IN (SELECT nt.note_id FROM note_tag nt JOIN tag t ON t.id = nt.tag_id"
        + " WHERE t.name LIKE ? ESCAPE '\\')";
    String byProject = "project_id IN (SELECT id FROM project WHERE name LIKE ? ESCAPE '\\')";
    String where;
    List<String> args;
    if (tagOnly) {
      where = byTag;
      args = List.of(like);
    } else if (projectOnly) {
      where = byProject;
      args = List.of(like);
    } else {
      where = "body_md LIKE ? ESCAPE '\\' OR " + byProject + " OR " + byTag;
      args = List.of(like, like, like);
    }
    String sql = SELECT + " WHERE " + where
        + " ORDER BY checkin_date DESC, created_at DESC, rowid DESC LIMIT " + SEARCH_LIMIT;
    synchronized (lock) {
      return query(sql, args.toArray(new String[0]));
    }
  }

  private static String escapeLike(String word) {
    return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 置顶/取消置顶。只动 pinned 与 updated_at，正文一个字节都不改。 */
  public void setPinned(String id, boolean pinned) throws SQLException {
    synchronized (lock) {
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE note SET pinned=?, updated_at=? WHERE id=?")) {
        ps.setInt(1, pinned ? 1 : 0);
        ps.setString(2, clock.wall());
        ps.setString(3, id);
        if (ps.executeUpdate() == 0) {
          throw new IllegalArgumentException("没有这条笔记：" + id);
        }
      }
    }
  }

  private Note require(String id) throws SQLException {
    List<Note> rows = query(SELECT + " WHERE id=?", id);
    if (rows.isEmpty()) {
      throw new IllegalStateException("找不到刚写入的笔记 " + id);
    }
    return rows.get(0);
  }

  private static Note map(ResultSet rs) throws SQLException {
    return new Note(rs.getString("id"), rs.getString("body_md"), rs.getString("created_at"),
        rs.getString("checkin_date"), rs.getString("project_id"), rs.getString("entry_id"),
        rs.getInt("pinned") != 0, List.of());
  }
}
