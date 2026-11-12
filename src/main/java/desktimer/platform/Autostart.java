package desktimer.platform;

/**
 * 开机自启的应用侧视图：界面只问"能不能设 / 现在开着吗 / 开或关"，不知道注册表的存在。
 *
 * 状态一律现读，不在 setting 表里存副本 —— 注册表才是事实源，用户可以在任务管理器里
 * 把它改掉，程序里的副本只会说谎（自检里 `enabled_reflects_registry` 那条钉住这点）。
 */
public interface Autostart {

  /** 知不知道自己的启动路径（只有 jpackage 出来的版本知道）。 */
  boolean available();

  boolean enabled();

  /** @return null 表示成功，否则是要显示给用户的原因。 */
  String set(boolean on);

  /** 从当前进程取 exe 路径；非打包版（IDE / run-app.ps1）返回 null。 */
  static String exePath() {
    String p = System.getProperty("jpackage.app-path");
    return (p == null || p.isBlank()) ? null : p;
  }
}
