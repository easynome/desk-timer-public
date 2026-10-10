package desktimer.platform;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

/**
 * "现在前台是不是全屏应用"。用途只有一个：倒计时到点时该不该响铃抢焦点。
 *
 * 只用 `jna`（本工程没有 `jna-platform`），所以结构体按 int[] 传：
 * MONITORINFO 是 {@code cbSize + rcMonitor(4) + rcWork(4) + dwFlags} = 10 个 int / 40 字节。
 * cbSize 填错的话 GetMonitorInfo 直接返回 false —— 探针第一版就是这么把每个窗口都跳过的
 * （填了 56），这条别再犯一次。
 *
 * 判据本体在 {@link FullScreenJudge}（纯计算、可单测）；这里只负责取数。
 * 取不到数（非 Windows、句柄为空、调用失败）一律回答"不是全屏"：宁可照旧响一次铃，
 * 也不要因为探测挂了就把"到点"这件事悄悄吞掉。
 */
public final class FullScreenDetector {

  private static final int GWL_STYLE = -16;
  private static final int WS_CAPTION = 0x00C00000;
  private static final int MONITOR_DEFAULTTONEAREST = 2;

  public interface User32 extends Library {
    User32 INSTANCE = Native.load("user32", User32.class);

    Pointer GetForegroundWindow();

    boolean IsWindowVisible(Pointer hWnd);

    boolean GetWindowRect(Pointer hWnd, int[] lpRect);

    int GetWindowLongW(Pointer hWnd, int nIndex);

    Pointer MonitorFromWindow(Pointer hWnd, int flags);

    /**
     * 名字必须带 W：user32.dll 里没有 `GetMonitorInfo` 这个导出（它是 C 头的宏），
     * JNA 按名字找函数，少写 W 就是 UnsatisfiedLinkError —— 实测踩过一次。
     */
    boolean GetMonitorInfoW(Pointer hMonitor, int[] lpmi);

    int GetWindowTextW(Pointer hWnd, char[] lpString, int nMaxCount);
  }

  public record State(boolean fullscreen, String foreground, String detail) {
  }

  /** 前台窗口的标题（探测失败返回空串）。诊断与"勿扰原因"都要用它。 */
  /**
   * 当前前台窗口的句柄（不判全屏，只回答"现在在最前面的是哪一扇"）。
   *
   * 自检需要它：断言"我盖上去的那扇全屏窗已经不在前台"，只能比句柄 ——
   * 拿"整个桌面还有没有全屏窗"做判据，用户自己在放视频时就会红，
   * 反过来还可能被用户的全屏窗蒙混过关（假绿）。
   */
  public long foregroundHandle() {
    if (!Platform.isWindows()) {
      return 0L;
    }
    Pointer h = User32.INSTANCE.GetForegroundWindow();
    return h == null ? 0L : Pointer.nativeValue(h);
  }

  public String foregroundTitle() {
    if (!Platform.isWindows()) {
      return "";
    }
    try {
      Pointer h = User32.INSTANCE.GetForegroundWindow();
      if (h == null) {
        return "";
      }
      char[] buf = new char[160];
      int n = User32.INSTANCE.GetWindowTextW(h, buf, buf.length);
      return n <= 0 ? "" : new String(buf, 0, n);
    } catch (Throwable t) {
      return "";
    }
  }

  public boolean isFullscreen() {
    return probe().fullscreen();
  }

  public State probe() {
    if (!Platform.isWindows()) {
      return new State(false, "", "not-windows");
    }
    try {
      User32 u = User32.INSTANCE;
      Pointer h = u.GetForegroundWindow();
      if (h == null || !u.IsWindowVisible(h)) {
        return new State(false, "", "no-foreground-window");
      }
      int[] win = new int[4];
      if (!u.GetWindowRect(h, win)) {
        return new State(false, title(h), "GetWindowRect-failed");
      }
      boolean caption = (u.GetWindowLongW(h, GWL_STYLE) & WS_CAPTION) == WS_CAPTION;
      int[] mi = new int[10];
      mi[0] = 40;   // cbSize 必须是结构体字节数
      Pointer mon = u.MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST);
      if (mon == null || !u.GetMonitorInfoW(mon, mi)) {
        return new State(false, title(h), "GetMonitorInfo-failed");
      }
      int[] monitor = {mi[1], mi[2], mi[3], mi[4]};
      int[] work = {mi[5], mi[6], mi[7], mi[8]};
      boolean full = FullScreenJudge.isFullscreen(win, monitor, caption);
      String t = title(h);
      return new State(full, t, "win=" + join(win) + " mon=" + join(monitor) + " work="
          + join(work) + " caption=" + caption);
    } catch (Throwable t) {
      return new State(false, "", "probe-failed:" + t + "（宁可照旧响铃，不吞掉到点）");
    }
  }

  private static String title(Pointer h) {
    try {
      char[] buf = new char[160];
      int n = User32.INSTANCE.GetWindowTextW(h, buf, buf.length);
      return n <= 0 ? "" : new String(buf, 0, n);
    } catch (Throwable t) {
      return "";
    }
  }

  private static String join(int[] a) {
    return a[0] + "," + a[1] + "," + a[2] + "," + a[3];
  }
}
