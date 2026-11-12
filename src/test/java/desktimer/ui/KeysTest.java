package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.platform.KeySpec;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

/**
 * 中文输入法把字母键吞掉时，JavaFX 送来的事件 code=UNDEFINED、只剩 text 里的字母，
 * 所以匹配要兼看 text；而真正的补救是给每个动作配非字母的备选键（见 Keys 的注释）。
 */
class KeysTest {

  private static KeyEvent key(KeyCode code, String text) {
    return new KeyEvent(KeyEvent.KEY_PRESSED, text, text, code, false, false, false, false);
  }

  @Test
  void physicalCodeMatchesRegardlessOfCase() {
    assertTrue(Keys.any(key(KeyCode.E, "e"), KeyCode.E));
    assertTrue(Keys.any(key(KeyCode.E, "E"), KeyCode.E));
    assertTrue(Keys.any(key(KeyCode.ENTER, "\r"), KeyCode.E, KeyCode.ENTER));
  }

  /** 把 API 事实钉住：getCharacter() 是派生值，code=UNDEFINED 时它不是那个字母 ⇒ 兜底只能读 text。 */
  @Test
  void characterIsUnusableWhenTheCodeIsGone() {
    KeyEvent k = key(KeyCode.UNDEFINED, "x");
    assertNotEquals("x", k.getCharacter(), "getCharacter() 在 code=UNDEFINED 时不可信");
    assertEquals("x", k.getText(), "text 才是兜底依据");
  }

  @Test
  void textStillMatchesWhenTheCodeIsGone() {
    assertTrue(Keys.any(key(KeyCode.UNDEFINED, "x"), KeyCode.X, KeyCode.DELETE));
    assertFalse(Keys.any(key(KeyCode.UNDEFINED, "拼"), KeyCode.X), "拼音正文不算按了 X");
    assertFalse(Keys.any(key(KeyCode.UNDEFINED, "1"), KeyCode.X));
  }

  @Test
  void nonLetterKeysMatchByCodeOnly() {
    assertTrue(Keys.any(key(KeyCode.DELETE, "?"), KeyCode.DELETE));
    assertFalse(Keys.any(key(KeyCode.BACK_SPACE, "?"), KeyCode.DELETE),
        "退格不是删除键，备选键不能撞车");
    assertFalse(Keys.any(key(KeyCode.SPACE, " "), KeyCode.ENTER));
  }

  private static KeyEvent combo(KeyCode code, boolean ctrl, boolean alt, boolean shift,
      boolean meta) {
    return new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code, shift, ctrl, alt, meta);
  }

  /**
   * 设置窗里"按下什么就是什么"的前提：JavaFX 事件翻出来的组合，必须能被 KeySpec 原样读回 ——
   * 否则会出现"界面显示绑上了，钩子却永远收不到"这种最难查的坏状态。
   */
  @Test
  void bindableComboRoundTripsThroughKeySpec() {
    KeySpec spec = Keys.toSpec(combo(KeyCode.F12, true, true, false, false));
    assertEquals("ctrl+alt+f12", spec.canonical());
    assertEquals(spec, KeySpec.parse(spec.canonical()));
  }

  /**
   * 裸键一律不认，只按 Shift 或只按 Win 的组合同样不认：
   * shift+a 在 RegisterHotKey 那儿是**能占住的**（实测 ok=True），于是"打一个大写 A"
   * 每次都会唤起界面 —— 全局快捷键必须钉在 Ctrl 或 Alt 上，这是唯一的判据。
   */
  @Test
  void bareKeyIsNotAHotkey() {
    assertNull(Keys.toSpec(combo(KeyCode.A, false, false, false, false)));
    assertNull(Keys.toSpec(combo(KeyCode.F12, false, false, false, false)));
    assertNull(Keys.toSpec(combo(KeyCode.A, false, false, true, false)), "shift+a 就是大写 A");
    assertNull(Keys.toSpec(combo(KeyCode.A, false, false, false, true)), "win+a 归系统");
    // 只按修饰键本身（Ctrl 按下的那一帧）也不算组合
    assertNull(Keys.toSpec(combo(KeyCode.CONTROL, true, false, false, false)));
    assertEquals("ctrl+a", Keys.toSpec(combo(KeyCode.A, true, false, false, false)).canonical());
    assertEquals("alt+a", Keys.toSpec(combo(KeyCode.A, false, true, false, false)).canonical());
  }

  @Test
  void lettersDigitsAndNamedKeysHaveNames() {
    assertEquals("ctrl+shift+s",
        Keys.toSpec(combo(KeyCode.S, true, false, true, false)).canonical());
    assertEquals("ctrl+alt+3",
        Keys.toSpec(combo(KeyCode.DIGIT3, true, true, false, false)).canonical());
    assertEquals("ctrl+alt+0",
        Keys.toSpec(combo(KeyCode.DIGIT0, true, true, false, false)).canonical());
    assertEquals("ctrl+alt+space",
        Keys.toSpec(combo(KeyCode.SPACE, true, true, false, false)).canonical());
    assertEquals("ctrl+alt+enter",
        Keys.toSpec(combo(KeyCode.ENTER, true, true, false, false)).canonical());
    assertEquals("alt+shift+tab",
        Keys.toSpec(combo(KeyCode.TAB, false, true, true, false)).canonical());
    assertEquals("ctrl+alt+escape",
        Keys.toSpec(combo(KeyCode.ESCAPE, true, true, false, false)).canonical());
  }

  /**
   * KeySpec 只认 f1..f12：它的 Win32 探测与 JNativeHook 编号表都到 F12 为止，
   * 绑出 F13 会"看着装上了、其实探测不了也收不到"，所以在入口就判不可绑。
   */
  @Test
  void keysBeyondTheMappingAreRejected() {
    for (KeyCode code : new KeyCode[] {KeyCode.F13, KeyCode.F19, KeyCode.NUM_LOCK,
        KeyCode.MULTIPLY, KeyCode.PAUSE, KeyCode.UNDEFINED, KeyCode.OPEN_BRACKET,
        KeyCode.NUMPAD9, KeyCode.WINDOWS, KeyCode.SEMICOLON}) {
      assertNull(Keys.toSpec(combo(code, true, true, false, false)), code + " 不能作全局快捷键");
    }
    // 纯修饰键自己（Win 按下的那一帧）也不算组合
    assertNull(Keys.toSpec(combo(KeyCode.WINDOWS, false, false, false, true)));
  }
}
