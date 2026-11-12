package desktimer.ui;

import desktimer.platform.KeySpec;
import java.util.Locale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * 按键匹配：字母键的 code **或** 事件里的字母都算命中。
 *
 * 为什么需要：中文输入法处于中文模式时，字母键会被拼音合成吃掉，JavaFX 要么收不到
 * 事件、要么收到的是 {@link KeyCode#UNDEFINED}（用户实测）。所以这里做两件事：
 *  1. code 不匹配时回头看事件带的字母；
 *  2. 每个动作配一个**窗口内才解释**的替代键（Enter / Esc / Space / 方向键）。
 *     不要用 Insert / Delete / F2 —— 它们在用户的联想本上压根不产生事件（实测"无响应"），
 *     而且它们是系统级通用键，抢别处的语义。
 *
 * 一条方法论：合成 KeyEvent 的自检只能证明"处理器会响应这个键"，**证明不了按键真的到达**
 * —— 上面那句"无响应"就是用户实测出来的，自检当时全绿。
 *
 * API 事实（实测，别再猜第二遍）：{@link KeyEvent#getCharacter()} 是**派生值**，
 * code=UNDEFINED 时它并不是那个字母，即使 text 里带着 ⇒ 兜底只能读 {@link KeyEvent#getText()}。
 */
public final class Keys {

  private Keys() {
  }

  public static boolean any(KeyEvent e, KeyCode... wanted) {
    for (KeyCode code : wanted) {
      if (e.getCode() == code) {
        return true;
      }
      if (code.isLetterKey() && letterMatches(code, e.getText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean letterMatches(KeyCode code, String text) {
    if (text == null || text.length() != 1) {
      return false;
    }
    return Character.toUpperCase(text.charAt(0)) == code.name().charAt(0);
  }

  /**
   * 把"按下的一次键"翻成可绑的全局快捷键；不可绑返回 null。
   *
   * 哪些键算"可绑"不是审美问题，是下游能力边界：{@link KeySpec} 的 Win32 探测表与
   * JNativeHook 编号表只覆盖主键盘 a-z / 0-9 / f1-f12 / space / enter / tab / escape。
   * 放行一个翻不出去的组合，就会留下"界面显示改好了、按了却没反应"这种最难查的坏状态，
   * 所以宁可在这里就拒绝，并让设置窗把原因写出来。
   */
  public static KeySpec toSpec(KeyEvent e) {
    String name = bindableName(e.getCode());
    if (name == null) {
      return null;
    }
    // 必须带 Ctrl 或 Alt。只按 Shift/Win 的组合是能占住的（实测 shift+a 的 RegisterHotKey
    // ok=True），于是"大写 A"这种日常打字每次都会唤起界面；也至少一个修饰键，
    // 与 KeySpec.parse 同口径 —— 裸键留给正在打字的输入框
    if (!e.isControlDown() && !e.isAltDown()) {
      return null;
    }
    return new KeySpec(e.isControlDown(), e.isAltDown(), e.isShiftDown(), e.isMetaDown(), name);
  }

  /** KeyCode 的命名是有规律的（A-Z、DIGIT0-9、F1-F24、其余是单词），按名字取比列 48 个 case 短。 */
  private static String bindableName(KeyCode code) {
    if (code == null) {
      return null;
    }
    String n = code.name();
    if (n.length() == 1 && n.charAt(0) >= 'A' && n.charAt(0) <= 'Z') {
      return n.toLowerCase(Locale.ROOT);
    }
    if (n.startsWith("DIGIT") && n.length() == 6) {
      return n.substring(5);   // NUMPAD9 是另一个键，RegisterHotKey 的 0x30 段不含它
    }
    if (n.length() > 1 && n.charAt(0) == 'F'
        && n.substring(1).chars().allMatch(Character::isDigit)) {
      int no = Integer.parseInt(n.substring(1));
      return no >= 1 && no <= 12 ? n.toLowerCase(Locale.ROOT) : null;   // F13+ 两张表都没有
    }
    return switch (code) {
      case SPACE -> "space";
      case ENTER -> "enter";
      case TAB -> "tab";
      case ESCAPE -> "escape";
      default -> null;
    };
  }
}
