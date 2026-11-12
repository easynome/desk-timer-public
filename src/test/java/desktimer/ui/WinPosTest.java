package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

/**
 * 位置钳制的判据是"至少露出 minVisible 像素"，不是"完全在屏内" ——
 * 用户把窗推到屏边是正常用法，只有拔了副屏那种整窗丢失才该回退。
 */
class WinPosTest {

  private static final double W = 360;
  private static final double H = 168;
  private static final double MIN = 60;
  private static final WinPos DEF = new WinPos(120, 240);
  private static final List<Rectangle2D> ONE =
      List.of(new Rectangle2D(0, 0, 1920, 1040));

  @Test
  void wireRoundTrips() {
    assertEquals("120,240", DEF.wire());
    WinPos back = WinPos.parse(DEF.wire());
    assertEquals(120d, back.x());
    assertEquals(240d, back.y());
  }

  @Test
  void garbageNeverBlocksStartup() {
    assertNull(WinPos.parse(null));
    assertNull(WinPos.parse(""));
    assertNull(WinPos.parse("  "));
    assertNull(WinPos.parse("120"));
    assertNull(WinPos.parse("120,240,300"));
    assertNull(WinPos.parse("x,y"));
  }

  @Test
  void keepsPositionsThatAreStillOnScreen() {
    // 左边缘推出去 40 像素、右边缘露出 320 像素：都算"还找得回来"
    assertEquals(-40d, new WinPos(-40, 100).clampedInto(ONE, W, H, MIN, DEF).x());
    assertEquals(1600d, new WinPos(1600, 100).clampedInto(ONE, W, H, MIN, DEF).x());
    assertEquals(900d, new WinPos(800, 900).clampedInto(ONE, W, H, MIN, DEF).y());
  }

  @Test
  void fallsBackWhenTheWindowIsGone() {
    // 整窗在副屏上，而副屏已经拔了
    assertEquals(DEF, new WinPos(2500, 300).clampedInto(ONE, W, H, MIN, DEF));
    // 只剩一条缝
    assertEquals(DEF, new WinPos(1870, 300).clampedInto(ONE, W, H, MIN, DEF));
    // 掉到屏幕下方
    assertEquals(DEF, new WinPos(300, 1400).clampedInto(ONE, W, H, MIN, DEF));
  }

  @Test
  void multiScreenPositionsSurviveOnTheRightMonitor() {
    // 两块屏之间留一条缝（不同 DPI 拼出来的虚拟布局真会这样），缝里放不下就是丢了
    List<Rectangle2D> two = List.of(new Rectangle2D(0, 0, 1920, 1040),
        new Rectangle2D(2100, 0, 2560, 1440));
    assertEquals(2400d, new WinPos(2400, 400).clampedInto(two, W, H, MIN, DEF).x());
    assertEquals(DEF, new WinPos(1950, 400).clampedInto(two, W, H, MIN, DEF));
  }

  @Test
  void noScreensAtAllFallsBack() {
    assertEquals(DEF, new WinPos(9999, 9999).clampedInto(List.of(), W, H, MIN, DEF));
  }
}
