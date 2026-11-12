package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.kwhat.jnativehook.NativeInputEvent;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;

/**
 * 三套键码编号各测各的。
 * 这里曾经把 Windows VK 当成 JNativeHook 的编号用，结果热键注册"成功"却永远不触发。
 */
class KeySpecTest {

  @Test
  void parsesAndNormalisesCaseAndAliases() {
    KeySpec s = KeySpec.parse("CTRL+Option+F12");
    assertTrue(s.ctrl());
    assertTrue(s.alt());
    assertFalse(s.shift());
    assertFalse(s.win());
    assertEquals("ctrl+alt+f12", s.canonical(), "canonical 要能安全存进 setting 表");
  }

  @Test
  void threeNamespacesForTheSameKey() {
    KeySpec f12 = KeySpec.parse("ctrl+alt+f12");
    assertEquals(88, f12.nativeHookCode(), "JNativeHook 的 VC_F12");
    assertEquals(0x7B, f12.win32Vk(), "Win32 VK_F12");
    assertEquals(KeyEvent.VK_F12, f12.awtKeyCode());

    KeySpec f11 = KeySpec.parse("ctrl+f11");
    assertEquals(87, f11.nativeHookCode(), "F11 不在 F1..F10 之后连续，是跳到 87");
    assertNotEquals(69, f11.nativeHookCode(), "59+10 这种算术是错的");
  }

  @Test
  void digitsAreReversedInNativeHookNumbering() {
    assertEquals(11, KeySpec.parse("ctrl+0").nativeHookCode(), "VC_0 不是序列末尾");
    assertEquals(10, KeySpec.parse("ctrl+9").nativeHookCode(), "VC_9 比 VC_0 小");
    assertEquals(0x30, KeySpec.parse("ctrl+0").win32Vk());
    assertEquals(0x39, KeySpec.parse("ctrl+9").win32Vk());
  }

  @Test
  void lettersHaveTheirOwnTableBecauseTheyAreNotContiguous() {
    assertEquals(30, KeySpec.parse("ctrl+a").nativeHookCode());
    assertEquals(44, KeySpec.parse("ctrl+z").nativeHookCode(), "Z 不是 A+25");
    assertNotEquals(30 + 25, KeySpec.parse("ctrl+z").nativeHookCode(),
        "按字母表做算术会得到 55，而真值是 44 —— 这就是热键收不到回调的原因之一");
    assertEquals(0x41, KeySpec.parse("ctrl+a").win32Vk(), "Win32 那侧才是连续的");
    assertEquals(KeyEvent.VK_Z, KeySpec.parse("ctrl+z").awtKeyCode());
  }

  @Test
  void rejectsNonsenseInsteadOfGuessing() {
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("f12"));
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("ctrl+hyper+x"));
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("ctrl+qq"));
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("ctrl+f13"));
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse(""));
    assertThrows(IllegalArgumentException.class, () -> KeySpec.parse(null));
  }

  @Test
  void win32AndNativeHookMasksAreDifferentNamespaces() {
    KeySpec s = KeySpec.parse("ctrl+alt+f12");
    assertEquals(0x0003, s.win32Modifiers(), "MOD_CONTROL|MOD_ALT");
    assertEquals(NativeInputEvent.CTRL_MASK | NativeInputEvent.ALT_MASK, s.nativeHookMask());
    assertNotEquals(s.win32Modifiers(), s.nativeHookMask(),
        "两套掩码值不同，谁把它们当成一个数就会让钩子永远不触发");
  }

  @Test
  void matchingUsesNativeHookCodesNotWin32() {
    KeySpec s = KeySpec.parse("ctrl+alt+f12");
    int both = NativeInputEvent.CTRL_MASK | NativeInputEvent.ALT_MASK;
    assertTrue(s.matches(88, both));
    assertFalse(s.matches(0x7B, both), "拿 Windows VK 来比必须判不命中，这正是之前踩的坑");
    assertTrue(s.matches(88, both | NativeInputEvent.SHIFT_MASK), "多按一个键不该让热键失灵");
    assertFalse(s.matches(88, NativeInputEvent.ALT_MASK), "少按 ctrl 就不算命中");
    assertFalse(s.matches(87, both), "F11 不是 F12");
  }
}
