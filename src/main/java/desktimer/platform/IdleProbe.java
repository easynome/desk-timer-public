package desktimer.platform;

import com.sun.jna.*;
import java.util.List;

/** 仅读当前 Windows 会话的末次输入时间，不记录输入内容。 */
public final class IdleProbe {
  public static class LastInput extends Structure {
    public int cbSize;
    public int dwTime;
    @Override protected List<String> getFieldOrder() { return List.of("cbSize", "dwTime"); }
  }
  private interface User32 extends Library { int GetLastInputInfo(LastInput info); }
  private interface Kernel32 extends Library { int GetTickCount(); }
  private User32 user;
  private Kernel32 kernel;

  public Long idleMs() {
    if (!Platform.isWindows()) return null;
    try {
      if (user == null) user = Native.load("user32", User32.class);
      if (kernel == null) kernel = Native.load("kernel32", Kernel32.class);
      LastInput info = new LastInput();
      info.cbSize = info.size();
      if (user.GetLastInputInfo(info) == 0) return null;
      long idle = (Integer.toUnsignedLong(kernel.GetTickCount()) - Integer.toUnsignedLong(info.dwTime)) & 0xffffffffL;
      // 错误/合成输入可能给出未来 tick，不能把它当成几十天离席来清零。
      return idle > 7 * 24 * 3600_000L ? null : idle;
    } catch (RuntimeException | LinkageError e) { return null; }
  }
}
