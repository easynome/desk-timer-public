package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Run 键读写删的四条判据，全部跑在一个**临时键**上（Software\desk-timer-autostart-test），
 * 正式的 CurrentVersion\Run 一根手指都不碰 —— 那台机器上自启开没开，只有他自己能决定。
 *
 * 为什么测真注册表而不是假的：这条链子上出错的地方（WString、句柄权限位、UTF-16 长度）
 * 全是 Win32 的行为，mock 一层就等于什么都没测。
 */
class RegistryRunKeyTest {

  private static final String SCRATCH = "Software\\desk-timer-autostart-test";
  private RegistryRunKey key;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(isWindows(), "非 Windows 上跳过（这台工程只在 Windows 上验收）");
    key = new RegistryRunKey(SCRATCH, "desk-timer-test");
    key.deleteWholeKey();
  }

  @AfterEach
  void tearDown() {
    if (key != null) {
      key.deleteWholeKey();
    }
  }

  @Test
  void absentValueReadsAsNull() {
    assertNull(key.current(), "还没挂自启就是 null，不是空串");
    assertFalse(key.pointsTo("D:\\timer-app\\desk-timer\\desk-timer.exe"));
  }

  @Test
  void enableWritesQuotedCommandAndReadsBack() {
    String exe = "D:\\timer-app\\desk-timer\\desk-timer.exe";
    key.enable(exe);
    assertEquals("\"" + exe + "\"", key.current(), "Run 值带引号，路径里有空格也不会被拆成参数");
    assertTrue(key.pointsTo(exe));
  }

  @Test
  void pointsToIgnoresQuotesAndCase() {
    key.enable("d:\\timer-app\\desk-timer\\desk-timer.exe");
    assertTrue(key.pointsTo("D:\\timer-app\\desk-timer\\desk-timer.exe"), "路径大小写不同算同一个");
    assertFalse(key.pointsTo("D:\\other\\desk-timer.exe"), "别的 exe 不能谎报成已启用");
  }

  @Test
  void disableRemovesOnlyItsOwnValueAndIsIdempotent() {
    key.enable("D:\\timer-app\\desk-timer\\desk-timer.exe");
    key.disable();
    assertNull(key.current());
    key.disable();
    assertNull(key.current(), "关两次不许炸，也不许留下半个值");
    key.enable("D:\\timer-app\\desk-timer\\desk-timer.exe");
    assertTrue(key.pointsTo("D:\\timer-app\\desk-timer\\desk-timer.exe"), "关完还能再开");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
