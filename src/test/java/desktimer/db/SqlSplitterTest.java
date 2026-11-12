package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlSplitterTest {

  @Test
  void triggerBodyWithInnerSemicolonsStaysOneStatement() throws Exception {
    String script = """
        CREATE TABLE a (id TEXT PRIMARY KEY);
        -- 注释里有分号 ; 不算结束
        CREATE TRIGGER trg BEFORE INSERT ON a
        WHEN NEW.id IS NOT NULL
        BEGIN
          SELECT RAISE(ABORT, 'no') FROM a WHERE id = 'x;';
        END;
        CREATE TABLE b (id TEXT PRIMARY KEY);
        """;
    List<String> out = SqlSplitter.split(script);
    assertEquals(3, out.size(), "应当是 建表 / 触发器 / 建表 三条");
    String trigger = out.get(1);
    assertTrue(trigger.startsWith("CREATE TRIGGER"), trigger);
    assertTrue(trigger.endsWith("END"), trigger);
    assertTrue(trigger.contains("'x;'"), "字符串里的分号不能被当成语句结束");
  }

  @Test
  void realInitScriptSplitsWithoutEmptyStatements() throws Exception {
    List<String> out = SqlSplitter.split(Migrations.readResource("/schema/001_init.sql"));
    assertTrue(out.stream().noneMatch(String::isBlank), "不该出现空语句");
    long triggers = out.stream().filter(s -> s.toUpperCase().startsWith("CREATE TRIGGER")).count();
    assertEquals(1, triggers);
    long tables = out.stream().filter(s -> s.toUpperCase().startsWith("CREATE TABLE")).count();
    assertEquals(11, tables);
  }
}
