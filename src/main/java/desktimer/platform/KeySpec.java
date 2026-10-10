package desktimer.platform;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 快捷键描述，例如 {@code ctrl+alt+f12}。
 *
 * 一份 KeySpec 要同时喂三个世界，而且**三套编号互不兼容**：
 *  - JNativeHook 的 VC_*（投递用）：F1=59 但 F12=88，F11/F12 跳段；数字是反的（VC_9=10、VC_0=11）。
 *  - Win32 虚拟键码（RegisterHotKey 探测用）：0x70 段、0x30 段、0x41 段。
 *  - AWT VK_*（自检注入用）。
 * 所以这里按名字显式映射，绝不做 "基值 + 序号" 的算术 —— 那样 F11 之后全错。
 */
public record KeySpec(boolean ctrl, boolean alt, boolean shift, boolean win, String keyName) {

  private static final int MOD_ALT = 0x0001;
  private static final int MOD_CONTROL = 0x0002;
  private static final int MOD_SHIFT = 0x0004;
  private static final int MOD_WIN = 0x0008;

  /** 字母键同样不连续（A=30 但 Z=44，中间插着别的键），26 个全列。 */
  private static final int[] NH_LETTERS = {
      NativeKeyEvent.VC_A, NativeKeyEvent.VC_B, NativeKeyEvent.VC_C, NativeKeyEvent.VC_D,
      NativeKeyEvent.VC_E, NativeKeyEvent.VC_F, NativeKeyEvent.VC_G, NativeKeyEvent.VC_H,
      NativeKeyEvent.VC_I, NativeKeyEvent.VC_J, NativeKeyEvent.VC_K, NativeKeyEvent.VC_L,
      NativeKeyEvent.VC_M, NativeKeyEvent.VC_N, NativeKeyEvent.VC_O, NativeKeyEvent.VC_P,
      NativeKeyEvent.VC_Q, NativeKeyEvent.VC_R, NativeKeyEvent.VC_S, NativeKeyEvent.VC_T,
      NativeKeyEvent.VC_U, NativeKeyEvent.VC_V, NativeKeyEvent.VC_W, NativeKeyEvent.VC_X,
      NativeKeyEvent.VC_Y, NativeKeyEvent.VC_Z,
  };
  /** JNativeHook 的功能键编号不连续（F10=68 之后跳到 F11=87），逐个列。 */
  private static final int[] NH_F = {
      NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4,
      NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6, NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8,
      NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12,
  };
  /** 数字键在 JNativeHook 里是 1..9 = 2..10、0 = 11，与键盘顺序不一致。 */
  private static final int[] NH_DIGITS = {
      NativeKeyEvent.VC_0, NativeKeyEvent.VC_1, NativeKeyEvent.VC_2, NativeKeyEvent.VC_3,
      NativeKeyEvent.VC_4, NativeKeyEvent.VC_5, NativeKeyEvent.VC_6, NativeKeyEvent.VC_7,
      NativeKeyEvent.VC_8, NativeKeyEvent.VC_9,
  };

  public static KeySpec parse(String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("快捷键不能为空");
    }
    String[] parts = spec.toLowerCase(Locale.ROOT).split("\\+");
    if (parts.length < 2) {
      throw new IllegalArgumentException("至少要带一个修饰键：" + spec);
    }
    boolean c = false;
    boolean a = false;
    boolean s = false;
    boolean w = false;
    for (int i = 0; i < parts.length - 1; i++) {
      switch (parts[i].trim()) {
        case "ctrl", "control" -> c = true;
        case "alt", "option" -> a = true;
        case "shift" -> s = true;
        case "win", "meta", "cmd", "super" -> w = true;
        default -> throw new IllegalArgumentException("不认识的修饰键：" + parts[i].trim());
      }
    }
    String key = parts[parts.length - 1].trim();
    if (!isKnownKey(key)) {
      throw new IllegalArgumentException("不认识的按键：" + key);
    }
    return new KeySpec(c, a, s, w, key);
  }

  private static boolean isKnownKey(String key) {
    return key.matches("([a-z]|[0-9]|f([1-9]|1[0-2])|space|enter|tab|escape)");
  }

  public String canonical() {
    List<String> parts = new ArrayList<>();
    if (ctrl) {
      parts.add("ctrl");
    }
    if (alt) {
      parts.add("alt");
    }
    if (shift) {
      parts.add("shift");
    }
    if (win) {
      parts.add("win");
    }
    parts.add(keyName);
    return String.join("+", parts);
  }

  public int win32Modifiers() {
    int m = 0;
    if (alt) {
      m |= MOD_ALT;
    }
    if (ctrl) {
      m |= MOD_CONTROL;
    }
    if (shift) {
      m |= MOD_SHIFT;
    }
    if (win) {
      m |= MOD_WIN;
    }
    return m;
  }

  public int win32Vk() {
    char ch = keyName.charAt(0);
    if (keyName.length() == 1 && ch >= 'a' && ch <= 'z') {
      return 0x41 + (ch - 'a');
    }
    if (keyName.length() == 1 && ch >= '0' && ch <= '9') {
      return 0x30 + (ch - '0');
    }
    if (keyName.startsWith("f")) {
      return 0x70 + (Integer.parseInt(keyName.substring(1)) - 1);
    }
    return switch (keyName) {
      case "space" -> 0x20;
      case "enter" -> 0x0D;
      case "tab" -> 0x09;
      default -> 0x1B;
    };
  }

  public int nativeHookMask() {
    int m = 0;
    if (alt) {
      m |= NativeInputEvent.ALT_MASK;
    }
    if (ctrl) {
      m |= NativeInputEvent.CTRL_MASK;
    }
    if (shift) {
      m |= NativeInputEvent.SHIFT_MASK;
    }
    if (win) {
      m |= NativeInputEvent.META_MASK;
    }
    return m;
  }

  public int nativeHookCode() {
    char ch = keyName.charAt(0);
    if (keyName.length() == 1 && ch >= 'a' && ch <= 'z') {
      return NH_LETTERS[ch - 'a'];
    }
    if (keyName.length() == 1 && ch >= '0' && ch <= '9') {
      return NH_DIGITS[ch - '0'];
    }
    if (keyName.startsWith("f")) {
      return NH_F[Integer.parseInt(keyName.substring(1)) - 1];
    }
    return switch (keyName) {
      case "space" -> NativeKeyEvent.VC_SPACE;
      case "enter" -> NativeKeyEvent.VC_ENTER;
      case "tab" -> NativeKeyEvent.VC_TAB;
      default -> NativeKeyEvent.VC_ESCAPE;
    };
  }

  public int awtKeyCode() {
    char ch = keyName.charAt(0);
    if (keyName.length() == 1 && ch >= 'a' && ch <= 'z') {
      return KeyEvent.VK_A + (ch - 'a');
    }
    if (keyName.length() == 1 && ch >= '0' && ch <= '9') {
      return KeyEvent.VK_0 + (ch - '0');
    }
    if (keyName.startsWith("f")) {
      return KeyEvent.VK_F1 + (Integer.parseInt(keyName.substring(1)) - 1);
    }
    return switch (keyName) {
      case "space" -> KeyEvent.VK_SPACE;
      case "enter" -> KeyEvent.VK_ENTER;
      case "tab" -> KeyEvent.VK_TAB;
      default -> KeyEvent.VK_ESCAPE;
    };
  }

  /**
   * 判定口径：要求的修饰键必须都在，多按了别的键不算错。
   * 掩码值也不连续（CTRL=34、ALT=136），所以只能按位与，不许比相等。
   */
  public boolean matches(int reportedCode, int reportedModifiers) {
    if (reportedCode != nativeHookCode()) {
      return false;
    }
    return (!ctrl || (reportedModifiers & NativeInputEvent.CTRL_MASK) != 0)
        && (!alt || (reportedModifiers & NativeInputEvent.ALT_MASK) != 0)
        && (!shift || (reportedModifiers & NativeInputEvent.SHIFT_MASK) != 0)
        && (!win || (reportedModifiers & NativeInputEvent.META_MASK) != 0);
  }
}
