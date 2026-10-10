package desktimer.ui;

import desktimer.db.SettingRepository;
import desktimer.platform.Autostart;
import desktimer.platform.KeySpec;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 设置窗：改唤起用的全局快捷键（范围 1），改面板的动作键（范围 2）。
 *
 * 打开就处于"等按键"状态：
 *  - 带 Ctrl/Alt 的组合 ⇒ 当作**全局唤起键**，当场探测占用并换监听，装上了才落盘；
 *  - 裸键 ⇒ 绑定到当前选中的**面板动作**；
 *  - 两者不冲突，是因为"裸键"与"带修饰键"本身就是两条互斥的判据。
 * 出口：Esc 关窗；Ctrl+D 重置选中那项；Ctrl+R 全部恢复出厂。
 * 后两个命令刻意用**带修饰键**的组合 —— 在这个窗里带修饰键的按键已经归"改唤起键"，
 * 所以先把命令拦下来，剩下的修饰组合才给唤起键。
 *
 * 显示的一定是实际生效的那份：全局键读 {@code HotkeyAdapter.current()}，动作键读 KeyMap 本体。
 */
public final class SettingsWindow {

  private static final String POS_KEY = "ui.settings.bounds";
  private static final double W = 520;
  /** 按钮分两排（五个控件挤在一排会在 520px 上被裁掉），提示条第三行还会绕一次，所以高一档。 */
  private static final double H = 424;

  private final Stage stage = new Stage();
  private final VBox root = new VBox(4);
  private final VBox rows = new VBox(1);
  private final Label title = new Label("设置");
  private final Label current = new Label();
  private final Label message = new Label();
  private final Label hint = new Label();
  private final javafx.scene.control.Button backupBtn = new javafx.scene.control.Button("立即备份 (Ctrl+B)");
  private final javafx.scene.control.Button wakeBtn = new javafx.scene.control.Button("唤起界面 (Ctrl+W)");
  private final javafx.scene.control.Button quitBtn =
      new javafx.scene.control.Button("退出应用 (Ctrl+Q)");
  private final javafx.scene.control.CheckBox autostartBox =
      new javafx.scene.control.CheckBox("开机自启");
  private final javafx.scene.control.Button themeBtn =
      new javafx.scene.control.Button("主题");
  private final Supplier<String> themePref;
  private final Consumer<String> onThemeChanged;
  private UiTheme.Palette theme;
  private final Supplier<KeySpec> currentSpec;
  private final Function<KeySpec, String> onRebind;
  private final Supplier<KeyMap> keysSource;
  private final Consumer<KeyMap> onKeysChanged;
  private final Runnable onBackup;
  private final Runnable onWake;
  private final Runnable onExit;
  private final SettingRepository settings;
  private final Autostart autostart;

  private KeyMap keys;
  private int selected;

  public SettingsWindow(Supplier<KeySpec> currentSpec, Function<KeySpec, String> onRebind,
      Supplier<KeyMap> keysSource, Consumer<KeyMap> onKeysChanged, Runnable onBackup,
      Runnable onWake, Runnable onExit, SettingRepository settings, Autostart autostart,
      UiTheme.Palette theme, Supplier<String> themePref, Consumer<String> onThemeChanged) {
    this.currentSpec = currentSpec;
    this.onRebind = onRebind;
    this.keysSource = keysSource;
    this.onKeysChanged = onKeysChanged;
    this.onBackup = onBackup;
    this.onWake = onWake;
    this.onExit = onExit;
    this.settings = settings;
    this.autostart = autostart;
    this.theme = theme;
    this.themePref = themePref;
    this.onThemeChanged = onThemeChanged;
    this.keys = keysSource.get();

    title.setStyle(theme.label(14, "title"));
    current.setStyle(theme.label(14, "current"));
    message.setStyle(theme.label(12, "muted"));
    message.setWrapText(true);
    hint.setStyle(theme.label(11, "dim"));
    hint.setWrapText(true);
    hint.setText("带 Ctrl/Alt 的组合＝改唤起键（当场生效并存库）· 裸键＝绑给选中的动作 · Tab 换选中"
        + "\nCtrl+D 重置选中项 · Ctrl+R 全部恢复出厂 · Ctrl+B 立即备份 · Ctrl+W 唤起界面 · Ctrl+Q 退出 · Esc 关闭"
        + "\n中文输入法可能吞掉字母键：绑不上字母就改用数字或 F 键；空白处可拖动");

    // 三项命令各给一个鼠标可点的按钮：按键那条路依赖焦点，而这扇窗是从托盘（后台进程）叫出来的，
    // Windows 的前台锁可能让它拿不到焦点 —— 那时按键会落到别的窗口上，用户看到的就是"按了没反应"。
    // 按钮不参与 Tab 焦点：Tab 在这个窗里是"换选中的动作键"，Space/Enter 是"绑键"，
    // 让按钮可聚焦会把这两个键变成"点击按钮"。
    for (javafx.scene.control.Button b : List.of(backupBtn, wakeBtn, quitBtn)) {
      b.setStyle(theme.buttonStyle());
      b.setFocusTraversable(false);
    }
    themeBtn.setStyle(theme.buttonStyle());
    themeBtn.setFocusTraversable(false);
    themeBtn.setOnAction(e -> {
      String next = UiTheme.nextPreference(themePref.get());
      onThemeChanged.accept(next);
      themeBtn.setText(themeLabel(next));
      say("主题：" + themeLabel(next) + "（当场生效，下次开机也是这个）");
    });
    themeBtn.setText(themeLabel(themePref.get()));
    backupBtn.setOnAction(e -> onBackup.run());    wakeBtn.setOnAction(e -> {
      hide();
      onWake.run();
    });
    quitBtn.setOnAction(e -> {
      say("正在退出…");
      onExit.run();
    });

    // 开机自启：勾一下写 HKCU 的 Run 值，取消勾就删掉自己那一个值。
    // 状态每次开窗现读（注册表才是事实源），写失败就把勾退回去并把原因显示出来。
    autostartBox.setStyle(theme.label(12, "title"));
    autostartBox.setFocusTraversable(false);
    autostartBox.setVisible(autostart.available());
    autostartBox.setOnAction(e -> {
      boolean want = autostartBox.isSelected();
      String err = autostart.set(want);
      if (err != null) {
        autostartBox.setSelected(!want);
        say(err);
      } else {
        say(want ? "已设为开机自启（下次登录生效）" : "已取消开机自启");
      }
    });

    root.setPadding(new Insets(12));
    root.setStyle(theme.window("plain"));
    root.getChildren().addAll(title, current, rows, message,
        new VBox(4,
            new javafx.scene.layout.HBox(8, backupBtn, wakeBtn, quitBtn),
            new javafx.scene.layout.HBox(8, themeBtn, autostartBox), hint));

    // 高度按内容算：提示条会绕行、按钮分了两排，写死高度就会把最后一行裁掉（实测就是这样）
    Scene scene = new Scene(root, W, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
    scene.setOnKeyPressed(this::onKey);
    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setAlwaysOnTop(true);
    stage.setScene(scene);
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 120, 420, W, H);
    stage.setX(pos.x());
    stage.setY(pos.y());
    WindowDrag.attach(root, stage, null, this::persistPosition);
    refresh();
  }

  /** 这里决定一次按键算"改唤起键"、算"绑动作键"、还是窗口命令 —— 顺序不能随意换。 */
  private void onKey(KeyEvent e) {
    KeyCode code = e.getCode();
    boolean ctrl = e.isControlDown();
    boolean alt = e.isAltDown();
    boolean modified = ctrl || alt;
    // 取证用：合成事件的自检证明不了按键真的到达这扇窗（见 Keys 的教训），
    // 用户反馈"按了没反应"时，这一行能分清是没送到还是送到了没动作
    System.out.println("KEY_SEEN code=" + code + " ctrl=" + ctrl + " alt=" + alt
        + " shift=" + e.isShiftDown() + " showing=" + stage.isShowing()
        + " focused=" + stage.isFocused());

    if (code == KeyCode.ESCAPE && !modified) {
      hide();
      e.consume();
      return;
    }
    if (ctrl && code == KeyCode.D) {
      resetSelected();
      e.consume();
      return;
    }
    if (ctrl && code == KeyCode.R) {
      resetAll();
      e.consume();
      return;
    }
    // 托盘那三项在 JavaFX 里各有一个对应物：原生菜单的文字在他机器上画不出中文（实测豆腐块），
    // 所以功能不能只活在 AWT 菜单里。命令用带修饰的组合，避免和"裸键绑动作"撞车。
    if (ctrl && code == KeyCode.B) {
      onBackup.run();
      say("已备份一次（详情看托盘菜单 Backup，或本机 D:\\timer-data\\backup）");
      e.consume();
      return;
    }
    if (ctrl && code == KeyCode.W) {
      hide();
      onWake.run();
      e.consume();
      return;
    }
    if (ctrl && code == KeyCode.Q) {
      say("正在退出…");
      onExit.run();
      e.consume();
      return;
    }
    if (code == KeyCode.TAB) {
      move(e.isShiftDown() ? -1 : 1);
      e.consume();
      return;
    }
    if (modified) {
      rebindWakeKey(e, code);
      e.consume();
      return;
    }
    bindActionKey(e, code);
    e.consume();
  }

  private void rebindWakeKey(KeyEvent e, KeyCode code) {
    if (isModifier(code)) {
      say("只按到了「" + display(code) + "」：按住 Ctrl 或 Alt，再按一个字母、数字或 F 键");
      return;
    }
    KeySpec spec = Keys.toSpec(e);
    if (spec == null) {
      say("⚠ 「" + display(code) + "」不能当唤起键：能绑的只有主键盘字母、数字、F1-F12 和空格/回车/Tab/Esc"
          + "，而且必须带 Ctrl 或 Alt（只按 Shift 的 shift+a 就是大写 A）");
      return;
    }
    KeySpec live = currentSpec.get();
    if (spec.equals(live)) {
      // 按下的就是当前那个键：这一记同时被全局钩子收走（唤起界面），这里再摘挂一次监听
      // 正好可能把它自己吃掉，所以认作"没变"
      say("「" + spec.canonical() + "」已经是当前唤起键，不用换");
      return;
    }
    String reason = onRebind.apply(spec);
    if (reason == null) {
      say("唤起键已换成 " + spec.canonical() + "，立刻生效");
      System.out.println("HOTKEY_REBOUND " + spec.canonical());
    } else {
      say("⚠ " + reason);
      System.out.println("HOTKEY_REBIND_REJECTED " + spec.canonical() + " " + reason);
    }
    refresh();
  }

  private void bindActionKey(KeyEvent e, KeyCode code) {
    String action = selected();
    if (action == null) {
      say("没有选中的动作");
      return;
    }
    KeyCode old = keys.primary(action);
    String reason = keys.set(action, code);
    if (reason != null) {
      say("⚠ " + reason);
      System.out.println("KEYBIND_REJECTED " + action + " " + code + " " + reason);
      return;
    }
    commit();
    say(KeyMap.label(action) + " 已绑到「" + TimerPanel.keyName(code) + "」"
        + (old == code ? "" : "（原 " + TimerPanel.keyName(old) + " 失效）"));
    System.out.println("KEYBOUND " + action + "=" + code.getName());
    refresh();
  }

  private void resetSelected() {
    String action = selected();
    if (action == null) {
      return;
    }
    keys.reset(action);
    commit();
    say(KeyMap.label(action) + " 已恢复出厂键 " + TimerPanel.keyName(keys.primary(action)));
    refresh();
  }

  private void resetAll() {
    keys.resetAll();
    commit();
    say("全部动作键已恢复出厂设置");
    System.out.println("KEYBIND_RESET_ALL");
    refresh();
  }

  /** 落盘 + 让正在显示的面板当场用新键（不重建面板，免得在跑的那段闪一下）。 */
  private void commit() {
    onKeysChanged.accept(keys);
  }

  private String selected() {
    List<String> actions = keys.actions();
    return actions.isEmpty() || selected >= actions.size() ? null : actions.get(selected);
  }

  private void move(int delta) {
    int n = keys.actions().size();
    if (n == 0) {
      return;
    }
    selected = (selected + delta + n) % n;
    refresh();
  }

  private void refresh() {
    KeySpec spec = currentSpec.get();
    current.setText("唤起快捷键：" + (spec == null ? "（没装上，托盘左键也能唤起）" : spec.canonical()));
    rows.getChildren().clear();
    List<String> actions = keys.actions();
    for (int i = 0; i < actions.size(); i++) {
      String action = actions.get(i);
      Label row = new Label(actionLine(action));
      row.setStyle(theme.label(13, i == selected ? "current" : "item"));
      rows.getChildren().add(row);
    }
  }

  private String actionLine(String action) {
    StringBuilder sb = new StringBuilder(selected == keys.actions().indexOf(action) ? "▶ " : "  ");
    sb.append(pad(KeyMap.label(action), 7)).append("＝ ").append(TimerPanel.keyName(keys.primary(action)));
    return sb.toString();
  }

  /** 自检用：这扇窗在屏幕上的矩形（控件一排排排得下与否，只有看图才知道）。 */
  public java.awt.Rectangle screenRect() {
    javafx.geometry.Bounds b = root.localToScreen(root.getBoundsInLocal());
    return new java.awt.Rectangle((int) b.getMinX(), (int) b.getMinY(),
        (int) Math.ceil(b.getWidth()), (int) Math.ceil(b.getHeight()));
  }

  /** 换主题：把这扇窗自己的样式重贴一遍（列表行是 render() 里新建的，下一拍自然跟上）。 */
  public void applyTheme(UiTheme.Palette theme) {
    this.theme = theme;
    title.setStyle(theme.label(14, "title"));
    current.setStyle(theme.label(14, "current"));
    message.setStyle(theme.label(12, "muted"));
    hint.setStyle(theme.label(11, "dim"));
    root.setStyle(theme.window("plain"));
    autostartBox.setStyle(theme.label(12, "title"));
    for (javafx.scene.control.Button b : List.of(backupBtn, wakeBtn, quitBtn, themeBtn)) {
      b.setStyle(theme.buttonStyle());
    }
    themeBtn.setText(themeLabel(themePref.get()));
  }

  static String themeLabel(String pref) {
    String p = pref == null ? "dark" : pref.trim().toLowerCase();
    switch (p) {
      case "light":
        return "主题：亮色";
      case "system":
        return "主题：跟随系统";
      default:
        return "主题：暗色";
    }
  }

  private static String pad(String s, int width) {
    StringBuilder sb = new StringBuilder(s);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  private static boolean isModifier(KeyCode code) {
    return switch (code) {
      case CONTROL, SHIFT, ALT, META, WINDOWS, ALT_GRAPH -> true;
      default -> false;
    };
  }

  private void say(String text) {
    message.setText(text);
  }

  private static String display(KeyCode code) {
    String name = code == null ? null : code.getName();
    return name == null || name.isEmpty() ? String.valueOf(code) : name;
  }

  public void show() {
    runOnFx(() -> {
      this.keys = keysSource.get();
      // 每次开窗现读：可能在别处（任务管理器/启动项工具）被改过
      autostartBox.setVisible(autostart.available());
      autostartBox.setSelected(autostart.enabled());
      if (!stage.isShowing()) {
        stage.show();
      }
      refresh();
      message.setText("");
      stage.toFront();
      stage.requestFocus();
      Platform.runLater(() -> {
        if (!stage.isFocused()) {
          stage.toFront();
          stage.requestFocus();
        }
        // 取证：从托盘（后台进程）叫出来的窗不一定拿得到前台焦点，
        // 拿不到时按键会落到别的窗口 —— 用户看到的正是"按了没反应"，按钮就是给这种情况留的路
        System.out.println("SETTINGS_SHOWN focused=" + stage.isFocused()
            + " x=" + Math.round(stage.getX()) + " y=" + Math.round(stage.getY()));
      });
    });
  }

  public void hide() {
    runOnFx(() -> {
      if (stage.isShowing()) {
        stage.hide();
      }
    });
  }

  public boolean showing() {
    return stage.isShowing();
  }

  public boolean focused() {
    return stage.isFocused();
  }

  public double x() {
    return stage.getX();
  }

  public double y() {
    return stage.getY();
  }

  public Node root() {
    return root;
  }

  public String messageLine() {
    return message.getText();
  }

  /** 当前选中（第 0 项起）：自检要按它来确认"Tab 真的换了选中项"。 */
  public int selectedIndex() {
    return selected;
  }

  public void select(int index) {
    this.selected = index;
    runOnFx(this::refresh);
  }

  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
  }

  /** 自检用：等同于按住修饰键再按某个键（走 scene 的按键处理器）。 */
  public void press(KeyCode code, boolean ctrl, boolean alt, boolean shift, boolean meta) {
    runOnFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code,
        shift, ctrl, alt, meta)));
  }

  private static void runOnFx(Runnable body) {
    if (Platform.isFxApplicationThread()) {
      body.run();
    } else {
      Platform.runLater(body);
    }
  }
}
