package desktimer.platform;

/**
 * 全屏判据的纯计算部分（不碰 Win32，所以能在任何机器上单测）。
 * 输入是实测的窗口矩形、该窗口所在监视器矩形与 WS_CAPTION 位。
 */
public final class FullScreenJudge {

  private FullScreenJudge() {
  }

  /**
   * 判据两条：盖满**自己那块**监视器 + 没有标题栏。
   * 少了第二条就会把最大化当成全屏 —— 这台机器上最大化窗口是 {@code -7,-7,1714,1074}，
   * 比监视器 {@code 0,0,1707,1017} 还大一圈（调整边框留在屏外）。
   */
  public static boolean isFullscreen(int[] winRect, int[] monitorRect, boolean hasCaption) {
    if (winRect == null || monitorRect == null || winRect.length < 4 || monitorRect.length < 4) {
      return false;
    }
    if (winRect[2] <= winRect[0] || winRect[3] <= winRect[1]) {
      return false;   // 零面积或反向矩形：拿不到有效几何时一律说"不是全屏"
    }
    if (hasCaption) {
      return false;
    }
    return winRect[0] <= monitorRect[0] && winRect[1] <= monitorRect[1]
        && winRect[2] >= monitorRect[2] && winRect[3] >= monitorRect[3];
  }
}
