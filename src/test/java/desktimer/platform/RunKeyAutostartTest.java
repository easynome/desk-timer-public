package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 界面拿到的三个答案必须和注册表一致，尤其"别人把它关了"这种情形。 */
class RunKeyAutostartTest {

  private static final String SCRATCH = "Software\\desk-timer-autostart-test";
  private static final String EXE = "D:\\timer-app\\desk-timer\\desk-timer.exe";

  private RegistryRunKey key;
  private RunKeyAutostart autostart;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(
        System.getProperty("os.name", "").toLowerCase().contains("win"), "非 Windows 跳过");
    key = new RegistryRunKey(SCRATCH, RunKeyAutostart.VALUE_NAME);
    key.deleteWholeKey();
    autostart = new RunKeyAutostart(key, EXE);
  }

  @AfterEach
  void tearDown() {
    key.deleteWholeKey();
  }

  @Test
  void enabledTracksTheRegistryNotAnythingElse() {
    assertFalse(autostart.enabled(), "没挂值就是关");
    assertNull(autostart.set(true));
    assertTrue(autostart.enabled());
    key.disable();
    assertFalse(autostart.enabled(), "外部（任务管理器/别的工具）把它关了，界面必须跟着变");
  }

  @Test
  void aDifferentExeRegisteredIsNotOurs() {
    key.enable("D:\\somewhere-else\\desk-timer.exe");
    assertFalse(autostart.enabled(), "别的安装位不算已启用");
    assertNull(autostart.set(true));
    assertTrue(autostart.enabled(), "开一次要把路径改成自己这份");
  }

  @Test
  void unavailableWithoutAnExePathAndRefusesToWrite() {
    RunKeyAutostart loose = new RunKeyAutostart(key, null);
    assertFalse(loose.available());
    assertFalse(loose.enabled());
    assertTrue(loose.set(true).contains("启动路径"), "认不出路径就给原因，不动注册表");
    assertNull(key.current());
  }
}
