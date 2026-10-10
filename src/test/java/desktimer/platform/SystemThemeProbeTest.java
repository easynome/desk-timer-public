package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "跟随系统"靠不靠得住，全看这三条：读得到 1/0、读不到要回 null（不许猜成暗色或亮色）、
 * 而且**任何情况下都不抛** —— 这个探测发生在窗口构造之前，抛了就等于应用起不来。
 *
 * 为什么测真注册表而不是假数据：JNA 那几层（W 后缀、句柄权限、字节宽度）全是 Win32 的行为，
 * mock 一层就等于什么都没测（同 {@code RegistryRunKeyTest} 的口径）。
 * 写入只在临时键上做一次，而且是**测试自己带的那份 JNA 声明** —— 产品类只读不写，
 * 一个能改主题键的构造器留在生产代码里没有任何理由。
 */
class SystemThemeProbeTest {

  private static final String SCRATCH = "Software\\desk-timer-theme-test";
  private static final int KEY_ALL = 0xF003F;
  private static final int REG_DWORD = 4;

  private interface Advapi32 extends com.sun.jna.Library {
    int RegCreateKeyExW(Pointer root, WString sub, int reserved, WString cls, int options,
        int access, Pointer sa, PointerByReference out, IntByReference disposition);

    int RegSetValueExW(Pointer key, WString name, int reserved, int type, byte[] data, int len);

    int RegCloseKey(Pointer key);

    int RegDeleteKeyW(Pointer root, WString sub);
  }

  private static final Advapi32 ADV = Native.load("advapi32", Advapi32.class);
  private static final Pointer HKCU = new Pointer(0x80000001L);

  private final SystemThemeProbe probe = new SystemThemeProbe(SCRATCH, "AppsUseLightTheme");

  @BeforeEach
  @AfterEach
  void clean() {
    Assumptions.assumeTrue(isWindows(), "非 Windows 上没有这个键");
    ADV.RegDeleteKeyW(HKCU, new WString(SCRATCH));
  }

  @Test
  void missingValueReadsAsNullRatherThanAGuess() {
    assertNull(probe.read(), "读不到就是读不到：回 null 让上层退到 shipped 的暗色，不许猜成亮/暗");
  }

  @Test
  void dwordOneIsLightZeroIsDark() {
    write(1);
    assertEquals(Boolean.TRUE, probe.read(), "1 = 系统现在是亮色");
    write(0);
    assertEquals(Boolean.FALSE, probe.read(), "0 = 系统现在是暗色");
  }

  /** 注册表的类型是外部世界的输入：不是 DWORD 就不认，而不是"当成 0"。 */
  @Test
  void weirdDwordIsNotInterpreted() {
    write(7);
    assertNull(probe.read(), "只认 0/1，别把 7 解释成任何一种主题");
  }

  @Test
  void realMachineAnswerNeverThrowsAndIsKnown() {
    Assumptions.assumeTrue(isWindows());
    Boolean on = SystemThemeProbe.fromRegistry().read();
    assertTrue(on == null || on.equals(Boolean.TRUE) || on.equals(Boolean.FALSE));
    assertNotNull(SystemThemeProbe.fromRegistry().subKey(), "正式路径要有名字，日志里指得出来");
  }

  private void write(int value) {
    PointerByReference phk = new PointerByReference();
    int rc = ADV.RegCreateKeyExW(HKCU, new WString(SCRATCH), 0, null, 0, KEY_ALL, null, phk,
        new IntByReference());
    assertEquals(0, rc, "临时键建不出来 rc=" + rc);
    Pointer key = phk.getValue();
    try {
      byte[] data = new byte[4];
      data[0] = (byte) value;
      int set = ADV.RegSetValueExW(key, new WString("AppsUseLightTheme"), 0, REG_DWORD, data, 4);
      assertEquals(0, set, "写 DWORD 失败 rc=" + set);
    } finally {
      ADV.RegCloseKey(key);
    }
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("win");
  }
}
