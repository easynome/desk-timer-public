package desktimer.ui;

import desktimer.core.parse.CaptureParser;
import desktimer.core.time.EntryKind;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 快速捕获窗：单行输入 + 实时解析回显。
 *
 * 不提供钉住（钉住就退化成常驻小窗，违背"输入即走"），所以失焦必隐。
 * 解析失败不弹窗追问：回显里带 ⚠，回车照样开始。
 * 无边框所以自己实现了拖动，位置存在 setting 表的 ui.capture.bounds。
 * 一个输入框三种用途：`25m …` 开始计时、`! …` 记一笔、`+ …` 记待办 —— 后两种都不开始计时。
 */
public final class CaptureWindow {

  private final Stage stage = new Stage();
  private final VBox root;
  private final TextField input = new TextField();
  private final Label preview = new Label("回车开始正计时 · Esc 关闭");
  private final CaptureParser parser = new CaptureParser();
  private final Consumer<CaptureParser.Plan> onSubmit;
  private final Consumer<CaptureParser.NotePlan> onNote;
  private final Consumer<CaptureParser.TodoPlan> onTodo;
  private final Runnable onDismiss;
  private boolean autoHideOnFocusLost = true;
  private boolean hadFocus;
  private final desktimer.db.SettingRepository settings;
  private UiTheme.Palette theme;

  /** 自检用：这扇窗现在用的是哪套配色。 */
  public String themeName() {
    return theme.name();
  }

  /** 换主题就地重贴样式：输入框里正在打的字不许被重建弄丢（这是用户手还没离开的那扇窗）。 */
  public void applyTheme(UiTheme.Palette theme) {
    this.theme = theme;
    preview.setStyle(theme.label(12, "muted"));
    root.setStyle(theme.window("plain"));
  }
  private static final String POS_KEY = "ui.capture.bounds";

  public CaptureWindow(Consumer<CaptureParser.Plan> onSubmit,
      Consumer<CaptureParser.NotePlan> onNote, Consumer<CaptureParser.TodoPlan> onTodo,
      Runnable onDismiss, desktimer.db.SettingRepository settings, UiTheme.Palette theme) {
    this.onSubmit = onSubmit;
    this.onNote = onNote;
    this.onTodo = onTodo;
    this.onDismiss = onDismiss;
    this.settings = settings;
    this.theme = theme;

    input.setStyle("-fx-font-size: 16px;");
    input.setPromptText("25m 写周报 #工作 @Portal　或　! 刚才被需求变更打断了"
        + "　或　+ 整理发布清单　或　p 25m / p40 番茄");
    input.textProperty().addListener((o, old, val) -> refresh(val));
    input.setOnKeyPressed(this::onKey);

    preview.setWrapText(true);
    preview.setStyle(theme.label(12, "muted"));

    root = new VBox(6, input, preview);
    root.setPadding(new Insets(10));
    root.setStyle(theme.window("plain"));

    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setAlwaysOnTop(true);
    stage.setScene(new Scene(root, 460, 96));
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 120, 120, 460, 96);
    stage.setX(pos.x());
    stage.setY(pos.y());
    // 无边框窗没有标题栏：整块当把手，只把输入框除外（否则没法在里面选字）
    WindowDrag.attach(root, stage, input, this::persistPosition);
    // 只有"曾经拿到过焦点、之后失去"才隐藏。
    // Windows 前台锁会让后台进程的第二次 show() 拿不到焦点，
    // 若把"从未获得焦点"也当失焦，窗口就会在弹出的同一帧被自己关掉。
    stage.focusedProperty().addListener((o, was, is) -> {
      if (is) {
        hadFocus = true;
      } else if (hadFocus && autoHideOnFocusLost) {
        hide();
      }
    });
    refresh("");
  }

  private void onKey(KeyEvent e) {
    if (e.getCode() == KeyCode.ENTER) {
      submitNow();
      e.consume();
    } else if (e.getCode() == KeyCode.ESCAPE) {
      hide();
      input.clear();
      onDismiss.run();
      e.consume();
    }
  }

  public void show() {
    runOnFx(() -> {
      if (!stage.isShowing()) {
        stage.show();
      }
      hadFocus = false;
      grabFocus();
      input.selectAll();
    });
  }

  /** 抢焦点：首帧常被 Windows 前台锁挡掉，下一帧补一次。 */
  private void grabFocus() {
    stage.toFront();
    stage.requestFocus();
    Platform.runLater(() -> {
      if (!stage.isFocused()) {
        stage.toFront();
        stage.requestFocus();
      }
    });
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

  /** 拖完松手时落盘；也是自检用的同一条路径。 */
  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
  }

  /** 拖动手柄：整块背景都能拖，只有输入框例外。 */
  public javafx.scene.Node root() {
    return root;
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

  /** 设置项：两个窗口的失焦行为分别可配（默认捕获窗失焦即隐）。 */
  public void setAutoHideOnFocusLost(boolean value) {
    this.autoHideOnFocusLost = value;
  }

  /** 自检用：等同于用户敲字。 */
  public void type(String text) {
    runOnFx(() -> input.setText(text));
  }

  /** 自检用：等同于按下回车。 */
  public void submit() {
    runOnFx(this::submitNow);
  }

  private void submitNow() {
    String text = input.getText() == null ? "" : input.getText().trim();
    hide();
    input.clear();
    if (text.isEmpty()) {
      onDismiss.run();
      return;
    }
    if (parser.isNote(text)) {
      CaptureParser.NotePlan note = parser.parseNote(text);
      if (!note.isBlank()) {
        onNote.accept(note);
      }
      return;
    }
    if (parser.isTodo(text)) {
      CaptureParser.TodoPlan todo = parser.parseTodo(text);
      if (!todo.isBlank()) {
        onTodo.accept(todo);
      }
      return;
    }
    onSubmit.accept(parser.isPomodoro(text) ? parser.parsePomodoro(text) : parser.parse(text));
  }

  private void refresh(String val) {
    if (val == null || val.isBlank()) {
      preview.setText("回车开始正计时 · ! 记一笔 · + 记待办 · p 番茄 · Esc 关闭");
      return;
    }
    if (parser.isNote(val)) {
      CaptureParser.NotePlan n = parser.parseNote(val);
      StringBuilder sb = new StringBuilder("笔记 · ").append(n.body().isEmpty() ? "（空）" : n.body());
      if (!n.tags().isEmpty()) {
        sb.append(" · 标签=").append(String.join("/", n.tags()));
      }
      if (n.project() != null) {
        sb.append(" · 项目=").append(n.project());
      }
      sb.append(" · 不开始计时");
      preview.setText(sb.toString());
      return;
    }
    if (parser.isTodo(val)) {
      CaptureParser.TodoPlan d = parser.parseTodo(val);
      StringBuilder sb = new StringBuilder("待办 · ")
          .append(d.title().isEmpty() ? "（空）" : d.title());
      if (!d.tags().isEmpty()) {
        sb.append(" · 标签=").append(String.join("/", d.tags()));
      }
      if (d.project() != null) {
        sb.append(" · 项目=").append(d.project());
      }
      sb.append(" · 不开始计时");
      preview.setText(sb.toString());
      return;
    }
    if (parser.isPomodoro(val)) {
      CaptureParser.Plan d = parser.parsePomodoro(val);
      StringBuilder sb = new StringBuilder("番茄 " + human(d.targetMs()));
      sb.append(" · 任务=").append(d.task());
      if (!d.tags().isEmpty()) {
        sb.append(" · 标签=").append(String.join("/", d.tags()));
      }
      if (d.project() != null) {
        sb.append(" · 项目=").append(d.project());
      }
      sb.append(" · 结束后提示休息");
      preview.setText(sb.toString());
      return;
    }
    CaptureParser.Plan p = parser.parse(val);
    StringBuilder sb = new StringBuilder();
    sb.append(p.kind() == EntryKind.COUNT_UP ? "正计时" : "倒计时 " + human(p.targetMs()));
    sb.append(" · 任务=").append(p.task());
    if (!p.tags().isEmpty()) {
      sb.append(" · 标签=").append(String.join("/", p.tags()));
    }
    if (p.project() != null) {
      sb.append(" · 项目=").append(p.project());
    }
    if (p.needsReview()) {
      sb.append(" · ⚠ ").append(p.reason());
    }
    preview.setText(sb.toString());
  }

  private static String human(Long ms) {
    if (ms == null) {
      return "—";
    }
    long total = ms / 1000;
    return (total / 60) + "分" + (total % 60) + "秒";
  }

  private static void runOnFx(Runnable body) {
    if (Platform.isFxApplicationThread()) {
      body.run();
    } else {
      Platform.runLater(body);
    }
  }
}
