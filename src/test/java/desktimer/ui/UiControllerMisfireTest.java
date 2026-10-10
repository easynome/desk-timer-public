package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 「结束」与「手滑」的界线（用户实测反馈：倒计时阶段按一次回车，面板直接消失了）。
 *
 * 三条判据：
 *  - 显式放弃（X）永远不是误触 —— 那本来就要写 aborted，理由必须是"用户放弃"，
 *    把两条路合成一条会丢掉"他到底想不想作废这段"的信息；
 *  - 未到点才可能是误触：到点之后那一段已经响过了，他按 E 是"确认结束"，
 *    哪怕时长刚好跨过门限也不能反过来判成手滑；
 *  - 门限是**严格小于**：正好 5 秒说明他是看着表按的，放它过去。
 */
final class UiControllerMisfireTest {

  @Test
  void explicitAbortIsNeverAMisfire() {
    assertFalse(UiController.isMisfire(true, false, 10L));
    assertFalse(UiController.isMisfire(true, true, 10L));
  }

  @Test
  void shortAndUnfiredIsAMisfire() {
    assertTrue(UiController.isMisfire(false, false, 0L));
    assertTrue(UiController.isMisfire(false, false, UiController.MISFIRE_MS - 1L));
  }

  @Test
  void atOrOverTheThresholdIsARealFinish() {
    assertFalse(UiController.isMisfire(false, false, UiController.MISFIRE_MS));
    assertFalse(UiController.isMisfire(false, false, 25L * 60_000L));
  }

  @Test
  void aSegmentThatAlreadyRangIsNeverAMisfire() {
    assertFalse(UiController.isMisfire(false, true, 10L));
  }

  /** 理由文案是给他看的：门限数字写进句子，改门限不改文案就会说谎。 */
  @Test
  void reasonTextCarriesTheThresholdItEnforces() {
    assertEquals("疑似误触（不足 5 秒）", UiController.MISFIRE_REASON);
    assertEquals(5_000L, UiController.MISFIRE_MS);
  }
}
