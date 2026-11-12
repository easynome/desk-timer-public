package desktimer.core.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.EntryKind;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureParserTest {

  private final CaptureParser parser = new CaptureParser();
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-06T14:00:00+08:00");

  @Test
  void durationForms() {
    assertCase("25m 写周报 #工作", EntryKind.COUNT_DOWN, 1_500_000L, "写周报", List.of("工作"), null);
    assertCase("40m", EntryKind.COUNT_DOWN, 2_400_000L, CaptureParser.BLANK_TASK, List.of(), null);
    assertCase("1h30m 复盘", EntryKind.COUNT_DOWN, 5_400_000L, "复盘", List.of(), null);
    assertCase("90分钟 方案评审 @Portal", EntryKind.COUNT_DOWN, 5_400_000L, "方案评审", List.of(),
        "Portal");
    assertCase("2小时30分 深度工作 #深度", EntryKind.COUNT_DOWN, 9_000_000L, "深度工作",
        List.of("深度"), null);
    assertCase("45分 晨读", EntryKind.COUNT_DOWN, 2_700_000L, "晨读", List.of(), null);
    assertCase("30s 冲刺", EntryKind.COUNT_DOWN, 30_000L, "冲刺", List.of(), null);
    assertCase("1.5h 看文档", EntryKind.COUNT_DOWN, 5_400_000L, "看文档", List.of(), null);
  }

  @Test
  void bareTextStartsAStopwatch() {
    assertCase("写周报", EntryKind.COUNT_UP, null, "写周报", List.of(), null);
    assertCase("", EntryKind.COUNT_UP, null, CaptureParser.BLANK_TASK, List.of(), null);
    assertCase("   ", EntryKind.COUNT_UP, null, CaptureParser.BLANK_TASK, List.of(), null);
  }

  @Test
  void clockTimeCountsDownToItAndRollsToTomorrow() {
    // 14:00 → 15:30 是 1 小时 30 分
    assertCase("15:30 站会", EntryKind.COUNT_DOWN, 5_400_000L, "站会", List.of(), null);
    CaptureParser.Plan late = parser.parse("15:30 站会", now.withHour(16));
    assertEquals(23 * 3_600_000L + 30 * 60_000L, late.targetMs(),
        "时刻已过要滚到明天同一时刻：16:00 → 次日 15:30 是 23 小时 30 分");
  }

  @Test
  void tagsAndProjectCanAppearAnywhereAndDedupe() {
    CaptureParser.Plan p = parser.parse("#临时 25m 写周报 #工作 #临时", now);
    assertEquals(1_500_000L, p.targetMs());
    assertEquals("写周报", p.task());
    assertEquals(List.of("临时", "工作"), p.tags());
    assertFalse(p.task().contains("#"), "标签不许留在任务名里");
  }

  @Test
  void unknownUnitFallsBackToDefaultAndFlagsReview() {
    CaptureParser.Plan p = parser.parse("25x 写周报", now);
    assertTrue(p.needsReview(), "看不懂单位必须留下待审标记，不能静默当成任务名");
    assertEquals(1_500_000L, p.targetMs());
    assertEquals("写周报", p.task());
    assertTrue(p.reason().contains("25x"), p.reason());
  }

  @Test
  void numberAfterUnitIsNotATimeSoItBecomesTaskName() {
    // "m25" 这种写法我们不猜：整串当任务名走正计时，信息不丢
    CaptureParser.Plan p = parser.parse("m25 写周报", now);
    assertEquals(EntryKind.COUNT_UP, p.kind());
    assertNull(p.targetMs());
    assertEquals("m25 写周报", p.task());
    assertFalse(p.needsReview());
  }

  @Test
  void sameInputAlwaysYieldsSamePlan() {
    String input = "25m 写周报 #工作 @Portal";
    CaptureParser.Plan a = parser.parse(input, now);
    CaptureParser.Plan b = parser.parse(input, now);
    assertEquals(a, b, "确定性解析器不该有第二种结果");
  }

  @Test
  void neverThrowsOnAwkwardInput() {
    for (String s : List.of("#", "@", "25", "m", ":30", "15:3", "00:00 x", "# # #", "@@")) {
      CaptureParser.Plan p = parser.parse(s, now);
      assertFalse(p.task().isEmpty(), "空任务名不该出现，输入=" + s);
    }
  }

  private void assertCase(String input, EntryKind kind, Long targetMs, String task,
      List<String> tags, String project) {
    CaptureParser.Plan p = parser.parse(input, now);
    String where = "输入「" + input + "」";
    assertEquals(kind, p.kind(), where + " kind");
    assertEquals(targetMs, p.targetMs(), where + " targetMs");
    assertEquals(task, p.task(), where + " task");
    assertEquals(tags, p.tags(), where + " tags");
    assertEquals(project, p.project(), where + " project");
    assertFalse(p.needsReview(), where + " 不该被标待审");
  }
}
