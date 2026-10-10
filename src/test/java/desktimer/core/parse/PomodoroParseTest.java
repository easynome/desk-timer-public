package desktimer.core.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.EntryKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 番茄行的语法：`p` / `P` / `ｐ` 开头，时长可省（省略就是出厂的 25 分钟）；
 * 前缀后的裸数字按分钟算（`p40` = 40 分钟），这是 PRD 那两档预设（25 / 40）的第二档入口。
 *
 * 与 `!`（随手记）、`+`（待办）同一条分叉规则：前缀决定这一行进哪条路，
 * 而三种前缀都**不会**顺手开始一段正计时 —— 时长语义全部写在行里。
 */
class PomodoroParseTest {

  private final CaptureParser parser = new CaptureParser();

  @Test
  void pPrefixMarksAPomodoroLine() {
    assertTrue(parser.isPomodoro("p 25m 写周报"));
    assertTrue(parser.isPomodoro("P 整理发布清单"));
    assertTrue(parser.isPomodoro("ｐ 40m 整理笔记"));
    assertTrue(parser.isPomodoro("p"), "光一个前缀也算番茄行：时长用出厂预设");
    assertFalse(parser.isPomodoro("25m 写周报"), "没有前缀还是普通倒计时");
    assertFalse(parser.isPomodoro("Portal 上线"), "p 后面紧跟字母是单词，不是前缀");
    assertFalse(parser.isPomodoro("p是中文连着写"), "前缀后必须有空格或数字，不然误伤任务名");
  }

  @Test
  void omittedDurationFallsBackToThePreset() {
    CaptureParser.Plan p = parser.parsePomodoro("p 写周报 #工作");
    assertEquals(EntryKind.POMODORO_WORK.wire(), p.kind().wire());
    assertEquals(25 * 60_000L, p.targetMs().longValue(), "省时长就是 25 分钟，不是正计时");
    assertEquals("写周报", p.task());
    assertEquals(List.of("工作"), p.tags());
    assertTrue(p.isCountDown(), "番茄必须有目标时长，否则到点判据无从谈起");
  }

  @Test
  void explicitDurationWinsAndFullWidthUnitsStillWork() {
    assertEquals(40 * 60_000L, parser.parsePomodoro("p 40m 整理发布清单").targetMs().longValue());
    assertEquals(90 * 60_000L, parser.parsePomodoro("p 1h30m 方案评审").targetMs().longValue());
    assertEquals(15 * 60_000L, parser.parsePomodoro("ｐ 15分钟 刷 Q49").targetMs().longValue());
  }

  /** 项目与标签沿用全局抽取（计时行那套），不是笔记那套末尾扫描 —— 因为它本来就是计时行。 */
  @Test
  void sigilsFollowTheTimingLineRules() {
    CaptureParser.Plan p = parser.parsePomodoro("p 25m 写周报 #复盘 @Portal");
    assertEquals("Portal", p.project());
    assertEquals(List.of("复盘"), p.tags());
    assertEquals("写周报", p.task());
  }

  @Test
  void barePWithNothingElseIsADefaultPomodoroWithNoTask() {
    CaptureParser.Plan p = parser.parsePomodoro("p");
    assertEquals(EntryKind.POMODORO_WORK.wire(), p.kind().wire());
    assertEquals(25 * 60_000L, p.targetMs().longValue());
    assertEquals(CaptureParser.BLANK_TASK, p.task());
  }

  /** PRD 的第二档要一键到位：`p40` / `p 40` 里的裸数字按分钟算，不必打单位。 */
  @Test
  void bareNumberAfterTheSigilIsMinutes() {
    assertEquals(40 * 60_000L, parser.parsePomodoro("p40 整理发布清单").targetMs().longValue());
    assertEquals(40 * 60_000L, parser.parsePomodoro("p 40 整理发布清单").targetMs().longValue());
    assertEquals("整理发布清单", parser.parsePomodoro("p40 整理发布清单").task(), "数字吃掉之后任务文字要干净");
    assertEquals(25 * 60_000L, parser.parsePomodoro("p 整理发布清单").targetMs().longValue(),
        "没数字仍然回落 25，不是正计时");
  }

  /** 这条规则只活在番茄分支里：普通行缺单位仍走"疑似漏单位 ⇒ 默认 25 分 + needs_review"。 */
  @Test
  void bareNumberRuleStaysInsideThePomodoroBranch() {
    CaptureParser.Plan plain = parser.parse("40 写周报");
    assertEquals(25 * 60_000L, plain.targetMs().longValue(), "普通行的裸数字含义不许被番茄带偏");
    assertTrue(plain.needsReview(), "并且照样标 needs_review");
    assertEquals(40 * 60_000L, parser.parsePomodoro("p 40m 整理发布清单").targetMs().longValue());
    assertEquals(90 * 60_000L, parser.parsePomodoro("p 1.5 小时 方案评审").targetMs().longValue(),
        "带单位的写法不能被裸数字改写打断");
  }
}
