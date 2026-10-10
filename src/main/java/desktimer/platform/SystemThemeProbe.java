package desktimer.platform;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * 只读探测：Windows 现在要亮色还是暗色（{@code HKCU\...\Themes\Personalize\AppsUseLightTheme}）。
 *
 * 三条判据都是"外部世界的输入"教的：
 *  - 只认 DWORD 的 0/1。类型不对、值不是 0/1、键不存在 —— 一律回 {@code null}，
 *    由上层退到 shipped 的暗色；猜成任何一种主题都是替用户做决定。
 *  - <b>永不抛</b>。这个探测发生在窗口构造之前，抛异常等于整个应用起不来；
 *    探测失败最坏的后果只是"主题没跟上系统"。
 *  - 名字参数走 {@link WString}（JNA 默认按 ANSI 送，宽字符 API 会读成另一个值名，
 *    而调用本身不报错 —— 同注册表自启那条教训）。
 *
 * 这一层故意没有写路径：程序不改用户的系统主题，只跟着它走。
 */
public final class SystemThemeProbe {

  public static final String PERSONALIZE_SUBKEY =
      "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
  public static final String LIGHT_VALUE = "AppsUseLightTheme";

  private static final int KEY_READ = 0x20019;
  private static final int REG_DWORD = 4;
  private static final Pointer HKCU = new Pointer(0x80000001L);

  private interface Advapi32 extends com.sun.jna.Library {
    int RegOpenKeyExW(Pointer root, WString sub, int options, int access, PointerByReference out);

    int RegQueryValueExW(Pointer key, WString name, int reserved, IntByReference type,
        byte[] data, IntByReference len);

    int RegCloseKey(Pointer key);
  }

  private final String subKey;
  private final String valueName;

  SystemThemeProbe(String subKey, String valueName) {
    this.subKey = subKey;
    this.valueName = valueName;
  }

  /** 正式路径上的只读探测器。JNA 装载失败也不抛：那时 {@link #read()} 直接回 null。 */
  public static SystemThemeProbe fromRegistry() {
    return new SystemThemeProbe(PERSONALIZE_SUBKEY, LIGHT_VALUE);
  }

  public String subKey() {
    return subKey;
  }

  public String valueName() {
    return valueName;
  }

  private static volatile Advapi32 advapi;

  /** 装载失败回 null —— 调用方把它当成"读不到"，而不是"暗色"。 */
  private static Advapi32 advapi() {
    if (advapi == null) {
      try {
        advapi = Native.load("advapi32", Advapi32.class);
      } catch (RuntimeException | UnsatisfiedLinkError e) {
        return null;
      }
    }
    return advapi;
  }

  /** {@code TRUE}=亮色，{@code FALSE}=暗色，{@code null}=读不到（非 Windows、键不在、类型不对）。 */
  public Boolean read() {
    if (!Platform.isWindows()) {
      return null;
    }
    Advapi32 adv = advapi();
    if (adv == null) {
      return null;
    }
    PointerByReference phk = new PointerByReference();
    if (adv.RegOpenKeyExW(HKCU, new WString(subKey), 0, KEY_READ, phk) != 0) {
      return null;
    }
    Pointer key = phk.getValue();
    try {
      IntByReference type = new IntByReference();
      byte[] buf = new byte[4];
      IntByReference len = new IntByReference(buf.length);
      if (adv.RegQueryValueExW(key, new WString(valueName), 0, type, buf, len) != 0) {
        return null;
      }
      if (type.getValue() != REG_DWORD || len.getValue() < 4) {
        return null;
      }
      // DWORD 是小端 4 字节；低字节之外的任何非零位都说明这不是 0/1 那个意思
      int low = buf[0] & 0xFF;
      boolean upperZero = (buf[1] | buf[2] | buf[3]) == 0;
      return upperZero ? fromDword(low) : null;
    } catch (RuntimeException | UnsatisfiedLinkError e) {
      return null;
    } finally {
      try {
        adv.RegCloseKey(key);
      } catch (RuntimeException | UnsatisfiedLinkError ignored) {
        // 关不掉是系统的事，探测已经结束了
      }
    }
  }

  /** 只有 0 与 1 有解释；其余是"我不认识这个值"。 */
  static Boolean fromDword(int raw) {
    if (raw == 1) {
      return Boolean.TRUE;
    }
    return raw == 0 ? Boolean.FALSE : null;
  }
}
