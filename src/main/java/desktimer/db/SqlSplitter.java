package desktimer.db;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 脚本切分器。
 * 存在的理由：CREATE TRIGGER 的函数体里有分号，按 ';' 裸切会把触发器切成碎块。
 *
 * 触发器体的边界不是"BEGIN 后面那个分号"（语法里 BEGIN 后面直接是语句体，没有分号），
 * 而是"BEGIN 之后第一个以 END 收尾的分号"。
 */
public final class SqlSplitter {

  private SqlSplitter() {
  }

  public static List<String> split(String script) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inString = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    boolean inTriggerBody = false;

    for (int i = 0; i < script.length(); i++) {
      char ch = script.charAt(i);
      char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';

      if (inLineComment) {
        cur.append(ch);
        if (ch == '\n') {
          inLineComment = false;
        }
        continue;
      }
      if (inBlockComment) {
        cur.append(ch);
        if (ch == '*' && next == '/') {
          cur.append(next);
          i++;
          inBlockComment = false;
        }
        continue;
      }
      if (inString) {
        cur.append(ch);
        if (ch == '\'') {
          if (next == '\'') { // SQL 里 '' 表示一个引号
            cur.append(next);
            i++;
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (ch == '-' && next == '-') {
        inLineComment = true;
        cur.append(ch);
        continue;
      }
      if (ch == '/' && next == '*') {
        inBlockComment = true;
        cur.append(ch);
        continue;
      }
      if (ch == '\'') {
        inString = true;
        cur.append(ch);
        continue;
      }

      if (ch == ';') {
        String stmt = cur.toString().trim();
        if (inTriggerBody) {
          if (endsWithKeyword(stmt, "END")) {
            inTriggerBody = false;
            out.add(trimLeadingComments(stmt));
            cur.setLength(0);
          } else {
            cur.append(';'); // 触发器体内的分号，原样保留
          }
        } else if (!stmt.isEmpty()) {
          out.add(trimLeadingComments(stmt));
          cur.setLength(0);
        } else {
          cur.setLength(0);
        }
        continue;
      }

      cur.append(ch);

      // BEGIN 这个词一出现就进入触发器体（只在词尾字符上判，省得每字符扫一遍）
      if (!inTriggerBody && (ch == 'N' || ch == 'n')
          && endsWithKeyword(cur.toString(), "BEGIN") && startsWithTrigger(cur)) {
        inTriggerBody = true;
      }
    }

    String tail = trimLeadingComments(cur.toString().trim());
    if (!tail.isEmpty()) {
      out.add(tail);
    }
    return out;
  }

  private static boolean startsWithTrigger(StringBuilder cur) {
    String head = trimLeadingComments(cur.toString().trim()).toUpperCase();
    return head.startsWith("CREATE TRIGGER") || head.startsWith("CREATE  TRIGGER");
  }

  /**
   * 去掉语句开头的注释行。
   * 注释本身无害，但留在开头会让"这条语句是建表还是建触发器"的判断变脆弱。
   */
  static String trimLeadingComments(String stmt) {
    String[] lines = stmt.split("\n", -1);
    int first = 0;
    while (first < lines.length) {
      String t = lines[first].trim();
      if (t.isEmpty() || t.startsWith("--") || t.startsWith("/*")) {
        first++;
      } else {
        break;
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = first; i < lines.length; i++) {
      sb.append(lines[i]).append('\n');
    }
    return sb.toString().trim();
  }

  private static boolean endsWithKeyword(String stmt, String keyword) {
    String t = stmt.trim();
    int end = t.length();
    int start = end - keyword.length();
    if (start < 0) {
      return false;
    }
    if (!t.substring(start, end).equalsIgnoreCase(keyword)) {
      return false;
    }
    return start == 0 || !Character.isLetterOrDigit(t.charAt(start - 1));
  }
}
