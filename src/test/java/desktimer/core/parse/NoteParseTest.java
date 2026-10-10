package desktimer.core.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 随手记语法：`!` 开头（全角 `！` 同义，中文输入法下 Shift+1 出来就是它）。
 *
 * 与计时行的关键差别：笔记正文是散文，`#` 可能出现在词里（C#），所以标签/项目
 * **只从末尾向前扫连续段**，遇到普通词就停；计时行沿用已冻结的全局抽取。
 */
class NoteParseTest {

  private final CaptureParser parser = new CaptureParser();

  @Test
  void bangPrefixMarksANote() {
    assertTrue(parser.isNote("! 记一下"));
    assertTrue(parser.isNote("！记一下"), "全角叹号必须是同义词，否则中文用户用不了");
    assertTrue(parser.isNote("  ! 前后有空格"));
    assertFalse(parser.isNote("25m 写周报"), "计时行不许被误判成笔记");
    assertFalse(parser.isNote("普通文字"), "无前缀仍是正计时");
  }

  @Test
  void bodyIsEverythingAfterThePrefix() {
    CaptureParser.NotePlan p = parser.parseNote("! 写周报写到一半");
    assertEquals("写周报写到一半", p.body());
    assertEquals(List.of(), p.tags());
    assertEquals(null, p.project());
  }

  @Test
  void durationLookingTextInsideANoteStaysProse() {
    CaptureParser.NotePlan p = parser.parseNote("! 25m 之后被叫走了");
    assertEquals("25m 之后被叫走了", p.body(), "笔记里不解析时长，否则正文被吃掉一段");
  }

  @Test
  void onlyTheTrailingContiguousRunOfSigilsIsExtracted() {
    CaptureParser.NotePlan p = parser.parseNote("! 写周报 #工作 明天 @Portal");
    assertEquals("写周报 #工作 明天", p.body(), "被普通词隔开的 # 不算标签");
    assertEquals("Portal", p.project());
    assertEquals(List.of(), p.tags());

    CaptureParser.NotePlan two = parser.parseNote("! 收尾 #a #b @P");
    assertEquals("收尾", two.body());
    assertEquals(List.of("a", "b"), two.tags());
    assertEquals("P", two.project());
  }

  @Test
  void hashInsideAWordIsNotATag() {
    CaptureParser.NotePlan p = parser.parseNote("! C#代码真难读");
    assertEquals("C#代码真难读", p.body());
    assertEquals(List.of(), p.tags());
  }

  @Test
  void fullWidthSigilsAlsoCount() {
    CaptureParser.NotePlan p = parser.parseNote("！记一笔 ＃灵感 ＠Portal");
    assertEquals("记一笔", p.body());
    assertEquals(List.of("灵感"), p.tags());
    assertEquals("Portal", p.project());
  }

  @Test
  void pastedNewlinesFoldIntoSingleSpaces() {
    CaptureParser.NotePlan p = parser.parseNote("! 第一段\n第二段\r\n第三段");
    assertEquals("第一段 第二段 第三段", p.body(), "正文里不许留换行，也不许把两段粘成一段");
    assertFalse(p.body().contains("\n"));
  }

  @Test
  void bareBangIsABlankNoteTheCallerCanSkip() {
    assertTrue(parser.parseNote("!").isBlank());
    assertTrue(parser.parseNote("!   ").isBlank());
    assertEquals("", parser.parseNote("!").body());
  }

  @Test
  void timerLineKeepsTheFrozenGlobalExtraction() {
    CaptureParser.Plan plan = parser.parse("25m 写 #工作 周报 @Portal");
    assertEquals(List.of("工作"), plan.tags(), "计时行的全局抽取是已冻结行为，不随笔记规则改");
    assertEquals("Portal", plan.project());
    assertEquals("写 周报", plan.task());
  }
}
