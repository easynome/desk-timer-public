package desktimer.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

/**
 * 到点提示音：绑到 Windows 的**"新短信通知"事件**（别名 {@code Notification.IM}，
 * 本机实测解析为 {@code C:\Windows\media\Windows Notify Messaging.wav}）。
 *
 * 为什么这样选：这样"换提示音"不需要改代码也不需要发版 —— 他在 设置 → 系统 → 声音 →
 * 应用声音 里给"新短信通知"挑哪个音（包括选"无"），我们跟着变。而 {@code Toolkit.beep()}
 * 只有一个焊死的蜂鸣，改不了也不跟随系统。
 *
 * 三级退路（本机注册表里 Names 表只剩两项，别名不一定有显示名，所以逐级试）：
 * {@code Notification.IM} → {@code SystemExclamation}（"警告"）→ {@code Toolkit.beep()}。
 * 到点了没声音是这个工具最坏的一种错，所以宁可退回老样子，且把实际走了哪级报出来。
 *
 * 两条来自本机 JNA 经验的规矩（见 docs/dev/freeze.md）：W 后缀函数的字符串参数用
 * {@link WString}；调用失败不许静默 —— {@link #lastError()} 里留原因，自检打印它。
 */
public final class NotifySound {

  /** SND_ASYNC：不阻塞；SND_SYSTEM：按"显示主题/声音"里的系统音解析。 */
  private static final int SND_ASYNC = 0x0001;
  private static final int SND_SYSTEM = 0x00200000;

  /** 首选与退路的别名，按顺序试。 */
  static final String[] ALIASES = { "Notification.IM", "SystemExclamation" };

  private interface Winmm extends com.sun.jna.Library {
    int PlaySoundW(WString sound, Pointer hInstance, int flags);
  }

  private static volatile Winmm winmm;
  private static volatile String lastError = "";
  private static volatile String lastRoute = "";

  /**
   * 响一次。返回走的是哪条路（别名本身，或 {@code beep}），自检与日志靠它取证。
   * winmm 加载不了、或两个别名都解析不到声音，一律退回蜂鸣并把原因留在 {@link #lastError()}。
   */
  public static String play() {
    StringBuilder why = new StringBuilder();
    Winmm w = winmm();
    if (w != null) {
      for (String alias : ALIASES) {
        int rc = 0;
        try {
          rc = w.PlaySoundW(new WString(alias), null, SND_ASYNC | SND_SYSTEM);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
          why.append(alias).append('=').append(e.getClass().getSimpleName()).append(' ');
          continue;
        }
        if (rc != 0) {
          lastError = "";
          lastRoute = alias;
          return alias;
        }
        why.append(alias).append("=0 ");
      }
    } else {
      why.append("winmm_unavailable ");
    }
    lastError = why.toString().trim();
    lastRoute = "beep";
    java.awt.Toolkit.getDefaultToolkit().beep();
    return "beep";
  }

  /** 最近一次实际响的路（别名或 {@code beep}）；没响过是空串。自检靠它取证，不必自己再响一声。 */
  public static String lastRoute() {
    return lastRoute;
  }

  private static Winmm winmm() {
    if (winmm == null) {
      try {
        winmm = Native.load("winmm", Winmm.class);
      } catch (RuntimeException | UnsatisfiedLinkError e) {
        return null;
      }
    }
    return winmm;
  }

  /** 上一次失败的原因；成功是空串。 */
  public static String lastError() {
    return lastError;
  }
}
