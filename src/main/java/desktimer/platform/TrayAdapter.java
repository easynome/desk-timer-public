package desktimer.platform;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 托盘适配层：状态换色与提示音承担提醒；按当前需求不做闪烁。
 * 平台相关代码只允许出现在 desktimer.platform 包里，换栈时整包重写，核心不动。
 *
 * **这一层的文字一律只用 ASCII**（用户截图实测）：AWT 的托盘菜单与提示条是操作系统画的，
 * 在他的机器上中文渲染成豆腐块，而同一批中文在 JavaFX 窗口里一直正常 ——
 * 字符串本身是完好的 UTF-8（class 常量池扫过），坏的是系统画原生菜单时的字体回退。
 * 所以：能看懂的 ASCII 留在 AWT，中文界面全部走 JavaFX（设置窗里也有这三项）。
 */
public final class TrayAdapter {

  public enum State { NORMAL, RUNNING, ATTENTION }

  private final String baseTooltip;
  private final Runnable onAction;
  private final Runnable onExit;
  private final Runnable onBackup;
  private final Runnable onSettings;
  private TrayIcon icon;
  private Runnable workbench = () -> {};

  public void onWorkbench(Runnable action) { workbench = action; }

  public TrayAdapter(String baseTooltip, Runnable onAction, Runnable onExit, Runnable onBackup,
      Runnable onSettings) {
    this.baseTooltip = baseTooltip;
    this.onAction = onAction;
    this.onExit = onExit;
    this.onBackup = onBackup;
    this.onSettings = onSettings;
  }

  public void install() {
    if (!SystemTray.isSupported()) {
      throw new IllegalStateException("SystemTray 不可用，无法作为常驻程序运行");
    }
    icon = new TrayIcon(paint(State.NORMAL), baseTooltip, menu());
    icon.setImageAutoSize(true);
    icon.addActionListener(e -> onAction.run());
    try {
      SystemTray.getSystemTray().add(icon);
    } catch (Exception e) {
      throw new IllegalStateException("托盘图标添加失败：" + e.getMessage(), e);
    }
  }

  /**
   * 三项都走 JavaFX 的对应物：这里的标签只保留 ASCII（原因见类注释），
   * 中文说明在设置窗里 —— 用户按 Settings 打开的就是那扇窗。
   */
  private PopupMenu menu() {
    PopupMenu m = new PopupMenu();
    MenuItem workspace = new MenuItem("Tasks / Timeline");
    workspace.addActionListener(e -> workbench.run()); m.add(workspace);
    MenuItem backup = new MenuItem("Backup");
    backup.addActionListener(e -> onBackup.run());
    m.add(backup);
    MenuItem settings = new MenuItem("Settings");
    settings.addActionListener(e -> onSettings.run());
    m.add(settings);
    MenuItem exit = new MenuItem("Quit");
    exit.addActionListener(e -> onExit.run());
    m.add(exit);
    return m;
  }

  public void setState(State state) {
    if (icon == null) {
      return;
    }
    icon.setImage(paint(state));
    icon.setToolTip(baseTooltip + switch (state) {
      case NORMAL -> "";
      case RUNNING -> " | running";
      // 橙色有两种成因：有待确认，或快捷键没装上。两种都靠 Settings 自救，所以把入口写进提示里
      case ATTENTION -> " | needs review - right-click > Settings";
    });
  }

  public void remove() {
    if (icon != null) {
      SystemTray.getSystemTray().remove(icon);
      icon = null;
    }
  }

  /** 没有美术资源之前用色块区分状态：正常=蓝、计时中=绿、待确认=橙。 */
  static BufferedImage paint(State state) {
    int rgb = switch (state) {
      case NORMAL -> 0xFF4FC3F7;
      case RUNNING -> 0xFF66BB6A;
      case ATTENTION -> 0xFFFFA726;
    };
    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        boolean border = x == 0 || y == 0 || x == 15 || y == 15;
        img.setRGB(x, y, border ? 0xFF202020 : rgb);
      }
    }
    return img;
  }
}
