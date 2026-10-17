package desktimer.platform;

/** {@link Autostart} 的 Windows 实现：HKCU 的 Run 键，值名 {@code desk-timer}。 */
public final class RunKeyAutostart implements Autostart {

  public static final String VALUE_NAME = "desk-timer";

  private final RegistryRunKey key;
  private final String exe;

  public RunKeyAutostart(String exe) {
    this(new RegistryRunKey(VALUE_NAME), exe);
  }

  RunKeyAutostart(RegistryRunKey key, String exe) {
    this.key = key;
    this.exe = exe;
  }

  @Override
  public boolean available() {
    return exe != null && !exe.isBlank();
  }

  @Override
  public boolean enabled() {
    return available() && key.pointsTo(exe);
  }

  @Override
  public String set(boolean on) {    if (!available()) {
      return "认不出应用自己的启动路径（只有打包版能设开机自启）";
    }
    try {
      if (on) {
        key.enable(exe);
      } else {
        key.disable();
      }
      return null;
    } catch (RuntimeException e) {
      return "注册表没写进去：" + e.getMessage();
    }
  }

  /** 自检用的临时键：正式路径永不被写。 */
  private static final String SELFTEST_SUBKEY = "Software\\desk-timer-selftest";
  private static final String SELFTEST_EXE = "D:\\timer-app\\desk-timer\\desk-timer.exe";

  /**
   * 打包版自检：在临时键上把"开→读→外部关→再开→关"走一遍，并证明正式的 Run 值
   * 前后一模一样。判据放在平台层是因为它要用包内构造器与 {@code deleteWholeKey} ——
   * 那两个都不该公开：拿着默认的 Run 路径调删除整键，会把别的软件的自启一起清掉。
   *
   * @return 一行诊断，失败抛 {@link IllegalStateException}
   */
  public static String selfTest() {
    RegistryRunKey real = new RegistryRunKey(VALUE_NAME);
    String before = real.current();

    RegistryRunKey scratch = new RegistryRunKey(SELFTEST_SUBKEY, VALUE_NAME);
    scratch.deleteWholeKey();
    RunKeyAutostart onScratch = new RunKeyAutostart(scratch, SELFTEST_EXE);
    boolean startAbsent = !onScratch.enabled();
    String setErr = onScratch.set(true);
    boolean written = scratch.current() != null;
    boolean enabled = onScratch.enabled();
    // 等价于他在任务管理器/启动项工具里把它关掉：界面读出来必须跟着变，不许有副本
    scratch.disable();
    boolean tracksRegistry = !onScratch.enabled();
    onScratch.set(true);
    String clearErr = onScratch.set(false);
    boolean cleared = scratch.current() == null;
    scratch.deleteWholeKey();

    boolean untouched = java.util.Objects.equals(before, real.current());
    boolean exeKnown = new RunKeyAutostart(Autostart.exePath()).available();
    String report = "start_absent=" + startAbsent + " written=" + written + " enabled=" + enabled
        + " tracks_registry=" + tracksRegistry + " cleared=" + cleared
        + " set_err=" + (setErr == null && clearErr == null ? "none" : setErr + "/" + clearErr)
        + " exe_known=" + exeKnown + " real_run_untouched=" + untouched;
    if (!startAbsent || !written || !enabled || !tracksRegistry || !cleared
        || setErr != null || clearErr != null || !untouched) {
      throw new IllegalStateException("SELFTEST_AUTOSTART_FAILED " + report);
    }
    return report;
  }
}
