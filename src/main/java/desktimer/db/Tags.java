package desktimer.db;

import desktimer.core.time.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 标签的"按名字幂等创建 + 挂到某张关联表"。计时条目与随手记共用同一套语义，
 * 免得两处对"同名标签算不算同一个"给出不同答案。
 */
final class Tags {

  private Tags() {
  }

  /**
   * {@code linkTable} 与 {@code idColumn} 只允许传代码里写死的常量
   * （entry_tag/entry_id、note_tag/note_id）—— 表名列名不能走绑定参数，
   * 所以这里假定它们是可信的字面量，用户输入一律走 {@code ?}。
   */
  static void link(Connection c, Clock clock, String linkTable, String idColumn, String ownerId,
      List<String> names) throws SQLException {
    if (names == null) {
      return;
    }
    for (String raw : names) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String name = raw.trim();
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT OR IGNORE INTO tag (id,name,created_at) VALUES (?,?,?)")) {
        ps.setString(1, UUID.randomUUID().toString());
        ps.setString(2, name);
        ps.setString(3, clock.wall());
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT OR IGNORE INTO " + linkTable + " (" + idColumn + ", tag_id)"
              + " SELECT ?, id FROM tag WHERE name=?")) {
        ps.setString(1, ownerId);
        ps.setString(2, name);
        ps.executeUpdate();
      }
    }
  }

  static List<String> tagsOf(Connection c, String linkTable, String idColumn, String ownerId)
      throws SQLException {
    List<String> out = new java.util.ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT t.name FROM " + linkTable + " l JOIN tag t ON t.id = l.tag_id"
            + " WHERE l." + idColumn + "=? ORDER BY t.name")) {
      ps.setString(1, ownerId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }
}
