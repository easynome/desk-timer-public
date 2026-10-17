package desktimer.core.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 待办行的语法：`+` 或全角 `＋` 开头。
 *
 * sigil 规则**复用随手记那一套**（只从末尾向前扫连续段），不是计时行那套全局抽取 ——
 * 冻结在 freeze.md 里，理由是一条待办标题是短语，`#` 可能长在词里（"修 C# 编译"）。
 */
class TodoParseTest {

  private final CaptureParser parser = new CaptureParser();

  @Test
  void plusPrefixMarksATodoLine() {
    assertTrue(parser.isTodo("+ 整理发布清单"));
    assertTrue(parser.isTodo("＋整理发布清单"));
    assertTrue(parser.isTodo("   +  前面有空格"));
    assertFalse(parser.isTodo("25m 整理发布清单"), "计时行不受影响");
    assertFalse(parser.isTodo("! 刚才想到一件事"), "笔记行也不受影响");
    assertFalse(parser.isTodo("1 + 2 = 3"), "中间的加号不算");
  }

  @Test
  void plainTitleKeepsItsOwnPoundSign() {
    CaptureParser.TodoPlan t = parser.parseTodo("+ 修 C# 编译 #bug");
    assertEquals("修 C# 编译", t.title(), "词里的 # 不许抽走：那会改坏标题");
    assertEquals(List.of("bug"), t.tags());
  }

  @Test
  void trailingSigilsAreStrippedFromTheTitle() {
    CaptureParser.TodoPlan t = parser.parseTodo("+ 写周报 #工作 #紧急 @Portal");
    assertEquals("写周报", t.title());
    assertEquals(List.of("工作", "紧急"), t.tags(), "顺序按书写顺序，不是倒着扫出来那样");
    assertEquals("Portal", t.project());
    assertFalse(t.isBlank());
  }

  /** 全角 ＃＠＋ 与半角同义：中文输入法下 Shift+数字出来的就是全角。 */
  @Test
  void fullWidthSigilsWorkLikeAsciiOnes() {
    CaptureParser.TodoPlan t = parser.parseTodo("＋评审复盘 ＃复盘 ＠Portal");
    assertEquals("评审复盘", t.title());
    assertEquals(List.of("复盘"), t.tags());
    assertEquals("Portal", t.project());
  }

  @Test
  void onlyOneProjectSurvivesAndTagsDedupe() {
    CaptureParser.TodoPlan t = parser.parseTodo("+ 标题 @甲 @乙 #a #a");
    assertEquals("标题", t.title());
    assertEquals(List.of("a"), t.tags());
    assertEquals("乙", t.project(), "写两个项目时留最右那个：末尾向前扫，先扫到就定死 —— 与随手记同一实现");
  }

  @Test
  void whitespaceAndNewlinesFoldIntoOneSpace() {
    CaptureParser.TodoPlan t = parser.parseTodo("+  刷   LeetCode\n\n第 15 题  #算法");
    assertEquals("刷 LeetCode 第 15 题", t.title());
    assertEquals(List.of("算法"), t.tags());
  }

  @Test
  void emptyTodoIsBlankAndNotATitle() {
    assertTrue(parser.parseTodo("+").isBlank());
    // 光一个井号不构成标签 —— 而且这里不另立规矩：随手记的 "! #" 正文同样是 "#"
    assertEquals("#", parser.parseNote("! #").body(), "两边同一实现，改一边就得改另一边");
    assertEquals("#", parser.parseTodo("+   # ").title());
    assertEquals("＃", parser.parseTodo("+ ＃").title());
    assertEquals("", parser.parseTodo(null).title());
  }

  /**
   * 补录时长只认"整行是一个时长"。时刻写法必须拒：`14:20` 在 {@code parse} 里是
   * "从现在到 14:20 还有多久"，拿它往回补就变成一段未来的时间。
   */
  @Test
  void durationOnlyAcceptsWholeLineDurations() {
    assertMs(25 * 60_000L, CaptureParser.durationOnly("25m"));
    assertMs(25 * 60_000L, CaptureParser.durationOnly(" 25分钟 "));
    assertMs(90 * 60_000L, CaptureParser.durationOnly("1h30m"));
    assertMs(90 * 60_000L, CaptureParser.durationOnly("90分钟"));
    assertMs(3_600_000L, CaptureParser.durationOnly("1h"));
    assertMs(12 * 3_600_000L, CaptureParser.durationOnly("12h"));
  }

  @Test
  void durationOnlyRefusesClockTasksZeroAndOverlong() {
    assertNull(CaptureParser.durationOnly("14:20"), "时刻写法会算出未来的一段");
    assertNull(CaptureParser.durationOnly("25m 写周报"), "补录框里不填任务名，整行必须只是时长");
    assertNull(CaptureParser.durationOnly("0m"), "零长区间不是时长");
    assertNull(CaptureParser.durationOnly(""), "空行不算");
    assertNull(CaptureParser.durationOnly("2d"), "单位超出天");
    assertNull(CaptureParser.durationOnly("13h"), "超过 12 小时判成敲错");
    assertNull(CaptureParser.durationOnly("abc"));
    assertNull(CaptureParser.durationOnly(null));
  }

  /** 单独起个名字：私有 assertEquals(long, Long) 会把 JUnit 的字符串重载遮掉。 */
  private static void assertMs(long expected, Long actual) {
    assertEquals(Long.valueOf(expected), actual, "读到 " + actual);
  }
}
