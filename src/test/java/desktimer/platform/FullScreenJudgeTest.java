package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 全屏判据的分类器。数字不是编的：全是这台机器实测到的窗口几何。
 *
 * 为什么不能只看"有没有盖住整块屏"：这台机器上**最大化**的窗口是
 * {@code -7,-7,1714,1074} 对监视器 {@code 0,0,1707,1017} —— 它比屏幕还大一圈
 * （Win10 最大化窗口把不可见的调整边框留在屏幕外），单看几何会把最大化误判成全屏。
 * 真正分开两者的是 WS_CAPTION：最大化窗口有标题栏，无边框全屏没有。
 */
class FullScreenJudgeTest {

  /** 本机实测：最大化（Chrome / 豆包 / Qoder 都是这一组数）。 */
  private static final int[] MON = {0, 0, 1707, 1017};

  private static final int[] MAXIMIZED = {-7, -7, 1714, 1074};

  @Test
  void maximizedIsNotFullscreenEvenThoughItCoversEverything() {
    assertFalse(FullScreenJudge.isFullscreen(MAXIMIZED, MON, true),
        "最大化比屏幕还大一圈，只看几何会误判");
  }

  @Test
  void borderlessWindowCoveringTheMonitorIsFullscreen() {
    assertTrue(FullScreenJudge.isFullscreen(new int[] {0, 0, 1707, 1017}, MON, false));
    assertTrue(FullScreenJudge.isFullscreen(new int[] {-1, -1, 1708, 1018}, MON, false),
        "多盖出 1px（某些播放器的无边框全屏）仍算全屏");
  }

  /** 副屏上的全屏：比的是**自己那块**监视器，不是主屏。 */
  @Test
  void fullscreenIsJudgedAgainstTheMonitorsOwnRect() {
    int[] second = {1707, 0, 3414, 1017};
    assertTrue(FullScreenJudge.isFullscreen(new int[] {1707, 0, 3414, 1017}, second, false));
    assertFalse(FullScreenJudge.isFullscreen(new int[] {1707, 0, 3414, 1017}, MON, false),
        "拿主屏去比副屏的窗口会判错");
  }

  @Test
  void smallOrDegenerateWindowsAreNeverFullscreen() {
    assertFalse(FullScreenJudge.isFullscreen(new int[] {120, 240, 480, 408}, MON, false),
        "我们自己的无边框小窗不是全屏");
    assertFalse(FullScreenJudge.isFullscreen(new int[] {0, 0, 0, 0}, MON, false), "零面积");
    assertFalse(FullScreenJudge.isFullscreen(null, MON, false));
    assertFalse(FullScreenJudge.isFullscreen(MAXIMIZED, null, false));
  }

  /** 任务栏挡着的那一格：盖住工作区但不盖满监视器 ⇒ 不算全屏（视频窗口常见）。 */
  @Test
  void coveringOnlyTheWorkAreaIsNotFullscreen() {
    assertFalse(FullScreenJudge.isFullscreen(new int[] {0, 0, 1707, 987}, MON, false));
  }
}
