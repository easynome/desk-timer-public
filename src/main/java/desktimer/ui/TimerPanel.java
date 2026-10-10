package desktimer.ui;

import desktimer.db.SettingRepository;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.util.List;

/**
 * 计时面板：显示在跑的那一段，键盘可完成全部动作（不许出现只有鼠标能做的事）。
 * 默认失焦即隐，钉住后失焦不隐且保持置顶；钉住状态与窗口位置都存 setting 表。
 *
 * 无边框（TRANSPARENT）是为了那块圆角深色底，代价是没有标题栏可拖 ——
 * 所以这里自己把整块面板接成拖动手柄。
 */
public final class TimerPanel {

  private static final String POS_KEY = "ui.panel.bounds";
  private static final String PIN_KEY = "ui.panel.pinned";
  private static final double W = 360;
  private static final double H = 168;

  private final Stage stage = new Stage();
  private final VBox root;
  /**
   * 在跑的会话列表（M2-b）：一行一段，选中的那行高亮，最多 6 行，超出显示"+N 段"。
   * 它取代了以前那一行"任务名"—— 单会话时它就是一行，读法不变。
   */
  private final VBox roster = new VBox(2);
  private final Label readout = new Label("--:--");
  private final Label state = new Label("");
  private final ProgressBar bar = new ProgressBar(0);
  private final Label hint = new Label("");
  /** 列表高度变化时才 sizeToScene：每 250ms 抖一次会把在跑的那段从屏幕顶上推下去。 */
  private int rosterRows;

  private final Runnable onPauseResume;
  private final Runnable onFinish;
  private final Runnable onAbort;
  private final Runnable onPrev;
  private final Runnable onNext;
  private final Runnable onRest;
  private final Runnable onNewInput;
  private final Runnable onDnd;
  private final Runnable onNotes;
  private final SettingRepository settings;
  /** 当前面板配色。换主题由 {@link #applyTheme} 就地重画，不重建窗口（重建会丢位置与钉住状态）。 */
  private UiTheme.Palette theme;
  /** 提示条要按状态增减：只有一段时不该教他按方向键，没有待开始休息时不该教他按 R。 */
  private boolean showNavKeys;
  private boolean showRestKey;

  /** 动作键的绑定表（M1-d2）：出厂那套是默认值，用户可以在设置窗里换掉主键。 */
  private KeyMap keys = KeyMap.from("");

  private boolean pinned;
  private boolean autoHideOnFocusLost = true;
  private boolean hadFocus;
  /** 番茄自动钉住期间为 true：P 只改状态不写库，段结束后由上层恢复偏好。 */
  private boolean temporaryPin;
  private Runnable onHidden = () -> {
  };

  public TimerPanel(Runnable onPauseResume, Runnable onFinish, Runnable onAbort,
      Runnable onPrev, Runnable onNext, Runnable onRest, Runnable onNewInput, Runnable onDnd,
      Runnable onNotes, SettingRepository settings, UiTheme.Palette theme) {
    this.onPauseResume = onPauseResume;
    this.onFinish = onFinish;
    this.onAbort = onAbort;
    this.onPrev = onPrev;
    this.onNext = onNext;
    this.onRest = onRest;
    this.onNewInput = onNewInput;
    this.onDnd = onDnd;
    this.onNotes = onNotes;
    this.settings = settings;
    this.theme = theme;
    this.keys = KeyMap.from(readSetting("key.panel", ""));

    readout.setStyle(theme.label(28, "title", true));
    state.setStyle(theme.label(12, "muted"));
    hint.setStyle(theme.label(11, "dim"));
    // 键位表长成十个动作了，提示条必须能折行，否则后半截永远看不见
    hint.setWrapText(true);
    renderHint();
    bar.setMaxWidth(Double.MAX_VALUE);

    root = new VBox(6, roster, readout, bar, state, hint);
    root.setPadding(new Insets(12));
    // 高度跟着行数走（单段时和以前一样高），所以场景不锁死高度；宽度锁住，拖动区才稳定
    Scene scene = new Scene(root, W, Region.USE_COMPUTED_SIZE);
    scene.setOnKeyPressed(this::onKey);

    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setScene(scene);
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 120, 240, W, H);
    stage.setX(pos.x());
    stage.setY(pos.y());
    WindowDrag.attach(root, stage, null, this::persistPosition);
    // 边框色与置顶都由钉住状态决定，所以初始化必须走一次 setPinned
    setPinned(readSetting(PIN_KEY, "false").equals("true"));
    stage.focusedProperty().addListener((o, was, is) -> {
      if (is) {
        hadFocus = true;
      } else if (hadFocus && autoHideOnFocusLost && !pinned) {
        hide();
      }
    });
  }

  /**
   * 动作全部查绑定表 —— 提示条也从同一张表生成，
   * 这样改过键之后"面板上写着什么"和"按下去真的有效"不可能对不上（规则见 KeyMap）。
   */
  private void onKey(KeyEvent e) {
    if (keys.matches(e, "pause")) {
      onPauseResume.run();
      e.consume();
    } else if (keys.matches(e, "finish")) {
      onFinish.run();
      e.consume();
    } else if (keys.matches(e, "abort")) {
      onAbort.run();
      e.consume();
    } else if (keys.matches(e, "prev")) {
      onPrev.run();
      e.consume();
    } else if (keys.matches(e, "next")) {
      onNext.run();
      e.consume();
    } else if (keys.matches(e, "rest")) {
      // 有没有"待开始的休息"由上层判：没有就当这一键没按，不许自己造一段休息
      onRest.run();
      e.consume();
    } else if (keys.matches(e, "hide")) {
      // 收起不是结束：计时继续在跑，再按快捷键就回来。无边框窗没有最小化按钮，这是它的替代。
      // 通知上层是因为"待开始的休息"活在这块面板上：面板被关掉，那个提示就该一起没掉。
      onHidden.run();
      hide();
      e.consume();
    } else if (keys.matches(e, "pin")) {
      setPinned(!pinned);
      // 番茄期间这个 P 只改当前状态、不写库：自动钉住是临时的，写下去就把用户自己的偏好覆盖了。
      // 要永久改偏好去设置里改（见 UiController 的番茄状态机注释）。
      if (!temporaryPin) {
        writeSetting(PIN_KEY, String.valueOf(pinned));
      }
      e.consume();
    } else if (keys.matches(e, "note")) {
      // 计时进行中唯一的输入入口：全局快捷键只会抬这个面板，不记一笔就没法记。
      // 备选键默认只用方向键这类"窗口内才解释"的键 —— Insert/Delete/F2 在用户机器上不产生事件
      onNewInput.run();
      e.consume();
    } else if (keys.matches(e, "dnd")) {
      onDnd.run();
      e.consume();
    } else if (keys.matches(e, "notes")) {
      onNotes.run();
      e.consume();
    }
  }

  /**
   * 提示条按当前绑定生成：写死的提示在改键之后会变成假说明。
   *
   * 方向键与休息键只在**用得上**的时候出现（规则见 {@link #setKeysShown}）：只有一段在跑时
   * 教他按 ↑↓ 是噪声，没有待开始休息时教他按 R 会让他以为按下就能凭空休一段。
   */
  /**
   * 提示条。备选键必须一起写出来：`E` 之外还有 `Enter`、`H` 之外还有 `Esc`、`N` 之外还有 `→`，
   * 但它们以前只存在于键位表里 —— 用户按到了却不知道那是"结束"，看起来就像面板莫名其妙收起了
   * （2026-10-10 用户就是这么问的）。看不见的键等于没有这个键。
   */
  private void renderHint() {
    StringBuilder sb = new StringBuilder(keyName(keys.primary("pause")) + " 暂停 · "
        + withAlternates("finish") + " 结束 · "
        + keyName(keys.primary("abort")) + " 放弃 · "
        + withAlternates("hide") + " 收起 · "
        + keyName(keys.primary("pin")) + " 钉住置顶 · "
        + withAlternates("note") + " 记一笔或另开 · "
        + keyName(keys.primary("dnd")) + " 勿扰一小时 · "
        + keyName(keys.primary("notes")) + " 翻笔记");
    if (showNavKeys) {
      sb.append(" · ").append(keyName(keys.primary("prev"))).append("/")
          .append(keyName(keys.primary("next"))).append(" 换选中的段");
    }
    if (showRestKey) {
      sb.append(" · ").append(keyName(keys.primary("rest"))).append(" 开始休息");
    }
    hint.setText(sb.toString());
  }

  /** 主键 + 它的备选键（如 {@code E/↵}）；备选键跟着设置窗的改键走，不写死。 */
  private String withAlternates(String action) {
    StringBuilder sb = new StringBuilder(keyName(keys.primary(action)));
    for (KeyCode alt : keys.alternates(action)) {
      sb.append('/').append(keyName(alt));
    }
    return sb.toString();
  }

  /** 上层每次刷列表时告诉提示条这两个键该不该出现（键位变了 applyKeys 会重画）。 */
  public void setKeysShown(boolean nav, boolean rest) {
    if (this.showNavKeys == nav && this.showRestKey == rest) {
      return;
    }
    this.showNavKeys = nav;
    this.showRestKey = rest;
    renderHint();
  }

  /** 设置窗改完键直接调这里，不重建面板（重建会把在跑的那段显示丢一帧）。 */
  public void applyKeys(KeyMap next) {
    this.keys = next;
    renderHint();
  }

  public KeyMap keys() {
    return keys;
  }

  public static String keyName(KeyCode code) {
    if (code == null) {
      return "—";
    }
    return switch (code) {
      case SPACE -> "空格";
      case ENTER -> "Enter";
      case ESCAPE -> "Esc";
      case RIGHT -> "→";
      case LEFT -> "←";
      case UP -> "↑";
      case DOWN -> "↓";
      case PAGE_UP -> "PgUp";
      case PAGE_DOWN -> "PgDn";
      case HOME -> "Home";
      case END -> "End";
      default -> code.getName();
    };
  }

  /**
   * 选中那一行的详情：大数字、状态行、进度条。列表本身走 {@link #renderRoster}。
   * 拆开是因为并发下面板要同时回答"还有几段在跑"和"我瞄一眼还剩多久"，
   * 而后者靠的就是这块大字，不能为了列表把它缩掉（用户在双屏远处扫状态就是这么看的）。
   */
  public void render(String time, String stateText, double ratio) {
    runOnFx(() -> {
      readout.setText(time);
      state.setText(stateText);
      bar.setProgress(Math.max(0d, Math.min(1d, ratio)));
    });
  }

  /** 列表里的一行：文本已经由上层排好（含选中记号与"+N 段"），面板只管画。 */
  public record Line(String text, boolean selected) {
  }

  /**
   * 会话列表。行数变了才 sizeToScene：每 250ms 重算一次尺寸会把在跑的那段从屏幕上沿推下去，
   * 看起来就像面板在抖。
   */
  public void renderRoster(List<Line> lines) {
    runOnFx(() -> {
      roster.getChildren().setAll(lines.stream().map(this::rowLabel).toList());
      if (lines.size() != rosterRows) {
        rosterRows = lines.size();
        stage.sizeToScene();
      }
    });
  }

  private Label rowLabel(Line line) {
    Label l = new Label(line.text());
    l.setStyle(theme.label(13, line.selected() ? "current" : "row", true));
    return l;
  }

  /** 换主题：重贴持久节点的样式；列表行是每次渲染新建的，下一拍（250ms）自动用新配色重画。 */
  public void applyTheme(UiTheme.Palette theme) {
    this.theme = theme;
    readout.setStyle(theme.label(28, "title", true));
    state.setStyle(theme.label(12, "muted"));
    hint.setStyle(theme.label(11, "dim"));
    setPinned(pinned);
  }

  /** 自检用：面板在屏幕上的矩形。全桌面截图看不出这扇窗的效果，按它裁一张。 */
  public java.awt.Rectangle screenRect() {
    javafx.geometry.Bounds b = root.localToScreen(root.getBoundsInLocal());
    return new java.awt.Rectangle((int) b.getMinX(), (int) b.getMinY(),
        (int) Math.ceil(b.getWidth()), (int) Math.ceil(b.getHeight()));
  }

  /** 自检用：现在这套配色的名字（dark/light），换主题有没有真生效只能读它。 */
  public String themeName() {
    return theme.name();
  }

  /** 自检用：窗底那一条样式的原文 —— 只读名字证不了样式真发出去了。 */
  public String windowStyle() {
    return root.getStyle();
  }

  /** 自检用：大数字那行的样式（换主题最该跟着变的就是它）。 */
  public String readoutStyle() {
    return readout.getStyle();
  }

  /**
   * 钉住 = 失焦不隐藏 + 压在别的窗口之上；不钉住 = 点别处就收起、也不抢顶层。
   * 置顶以前是无条件开的，于是 P 看起来"没起作用"——现在它同时管着这两件事。
   * 只改状态；落盘的是用户按 P 那条路，程序强制钉住不该盖掉用户的偏好。
   *
   * 这里刻意不往 state 那行写字：那一行归计时（剩余时间/冲突/待确认条数），
   * 250ms 刷一次；钉住状态由提示条带出来，否则两者会互相盖掉。
   */
  public void setPinned(boolean value) {
    this.pinned = value;
    stage.setAlwaysOnTop(value);
    root.setStyle(theme.window(value ? "warn" : "plain"));
  }

  public boolean pinned() {
    return pinned;
  }

  /** 番茄自动钉住：临时状态，不写库；结束后由上层把偏好恢复回来。 */
  public void setTemporaryPin(boolean value) {
    this.temporaryPin = value;
  }

  public boolean temporaryPin() {
    return temporaryPin;
  }

  /** 用户按收起键时的回调（区别于程序自己 hide：只有前者该清掉"待开始的休息"）。 */
  public void onHidden(Runnable r) {
    this.onHidden = r == null ? () -> {
    } : r;
  }

  public double x() {
    return stage.getX();
  }

  public double y() {
    return stage.getY();
  }

  /** 列表现在写着什么（含"+N 段"那行）：折叠判据的取证口，自检靠它数行。 */
  public java.util.List<String> rosterLines() {
    return roster.getChildren().stream().map(n -> ((Label) n).getText()).toList();
  }

  /** 提示条现在写着什么：冲突数与待确认条数都在这里，自检要读它取证。 */
  public String stateLine() {
    return state.getText();
  }

  /** 键位提示写着什么：改键之后它必须跟着变，否则面板在说谎。 */
  public String hintLine() {
    return hint.getText();
  }

  /** 拖动手柄就是面板本体；自检要往它上面发合成鼠标事件。 */
  public Node root() {
    return root;
  }

  /** 拖完松手时落盘；自检走的是同一个方法。 */
  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
  }

  /** 自检用：等同于按下某个键（走 scene 的按键处理器）。 */
  public void press(KeyCode code) {
    runOnFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code,
        false, false, false, false)));
  }

  public void setAutoHideOnFocusLost(boolean value) {
    this.autoHideOnFocusLost = value;
  }

  public void show() {
    runOnFx(() -> {
      if (!stage.isShowing()) {
        stage.show();
      }
      hadFocus = false;
      stage.toFront();
      stage.requestFocus();
      Platform.runLater(() -> {
        if (!stage.isFocused()) {
          stage.toFront();
          stage.requestFocus();
        }
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

  private String readSetting(String key, String fallback) {
    try {
      return settings.get(key, fallback);
    } catch (Exception e) {
      System.out.println("SETTING_READ_FAILED " + key + " " + e.getMessage());
      return fallback;
    }
  }

  private void writeSetting(String key, String value) {
    try {
      settings.put(key, value);
      System.out.println("SETTING_WRITTEN " + key + "=" + value);
    } catch (Exception e) {
      System.out.println("SETTING_WRITE_FAILED " + key + " " + e.getMessage());
    }
  }

  private static void runOnFx(Runnable body) {
    if (Platform.isFxApplicationThread()) {
      body.run();
    } else {
      Platform.runLater(body);
    }
  }
}
