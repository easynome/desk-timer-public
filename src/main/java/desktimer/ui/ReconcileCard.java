package desktimer.ui;

import desktimer.core.parse.TimeText;
import desktimer.core.time.Clock;
import desktimer.db.ReviewItem;
import desktimer.db.SettingRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 对账卡：把"程序替用户决定过的结束时间"摊开让他改口。
 *
 * 三条硬规则：
 *  - 不启动即弹（打扰阶梯），只在唤起时出现，且一次只出现一个界面；
 *  - 动作只改状态或清标记，绝不删行 —— 证据要留在库里；
 *  - 键盘能做完全部事，无边框所以自己也接了拖动手柄。
 */
public final class ReconcileCard {

  private static final String POS_KEY = "ui.card.bounds";
  private static final double W = 520;
  private static final double H = 210;
  private static final int MAX_ROWS = 5;

  private final Stage stage = new Stage();
  private final VBox root = new VBox(4);
  private final VBox rows = new VBox(2);
  private final Label title = new Label("待确认");
  private final Label hint = new Label("");
  private final TextField edit = new TextField();
  private final SettingRepository settings;
  private UiTheme.Palette theme;

  private final Consumer<ReviewItem> onConfirm;
  private final BiConsumer<ReviewItem, String> onReschedule;
  private final Consumer<ReviewItem> onDiscard;
  private final Runnable onNewEntry;

  private List<ReviewItem> items = List.of();
  private int index;
  private boolean editing;

  public ReconcileCard(Consumer<ReviewItem> onConfirm, BiConsumer<ReviewItem, String> onReschedule,
      Consumer<ReviewItem> onDiscard, Runnable onNewEntry, SettingRepository settings,
      UiTheme.Palette theme) {
    this.onConfirm = onConfirm;
    this.onReschedule = onReschedule;
    this.onDiscard = onDiscard;
    this.onNewEntry = onNewEntry;
    this.settings = settings;
    this.theme = theme;

    title.setStyle(theme.label(14, "title"));
    rows.setStyle("-fx-padding: 2 0 2 8;");
    hint.setStyle(theme.label(11, "muted"));
    hint.setWrapText(true);
    edit.setPromptText("改成 HH:MM");
    edit.setStyle("-fx-font-size: 14px;");
    edit.setVisible(false);
    edit.setManaged(false);

    root.setPadding(new Insets(12));
    root.setStyle(theme.window("warn"));
    root.getChildren().addAll(title, rows, edit, hint);

    // 高度按内容算：重叠那条提示比原来长，写死高度会裁掉最后一行
    Scene scene = new Scene(root, W, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
    scene.setOnKeyPressed(this::onKey);
    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setAlwaysOnTop(true);
    stage.setScene(scene);
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 120, 360, W, H);
    stage.setX(pos.x());
    stage.setY(pos.y());
    WindowDrag.attach(root, stage, edit, this::persistPosition);
    render();
  }

  /** 键盘处理只挂在这一处：输入框里的回车也冒到这里，自检才能用同一个入口。 */
  private void onKey(KeyEvent e) {
    if (editing) {
      if (Keys.any(e, KeyCode.ENTER)) {
        commitEdit();
      } else if (Keys.any(e, KeyCode.ESCAPE)) {
        stopEditing();
      }
      e.consume();
      return;
    }
    // 每个动作都配了不依赖字母的备选键：中文输入法会把字母键吞掉（见 desktimer.ui.Keys）
    if (Keys.any(e, KeyCode.UP)) {
      move(-1);
    } else if (Keys.any(e, KeyCode.DOWN)) {
      move(1);
    } else if (Keys.any(e, KeyCode.Y, KeyCode.ENTER)) {
      act(onConfirm);
    } else if (Keys.any(e, KeyCode.T)) {
      startEditing();
    } else if (Keys.any(e, KeyCode.X)) {
      act(onDiscard);
    } else if (Keys.any(e, KeyCode.N, KeyCode.LEFT)) {
      hide();
      onNewEntry.run();
    } else if (Keys.any(e, KeyCode.ESCAPE)) {
      hide();
    } else {
      return;
    }
    e.consume();
  }

  private void act(Consumer<ReviewItem> action) {
    ReviewItem selected = selected();
    if (selected != null) {
      action.accept(selected);
    }
  }

  private void move(int delta) {
    if (items.isEmpty()) {
      return;
    }
    index = (index + delta + items.size()) % items.size();
    render();
  }

  private ReviewItem selected() {
    return items.isEmpty() || index >= items.size() ? null : items.get(index);
  }

  private void startEditing() {
    ReviewItem e = selected();
    if (e == null || e.endedAt() == null || e.startedAt() == null) {
      return;
    }
    editing = true;
    edit.setText(e.endedAt().substring(11, 16));
    edit.setVisible(true);
    edit.setManaged(true);
    hint.setText("输入新的结束时刻（HH:MM），回车生效 · Esc 取消");
    edit.selectAll();
    Platform.runLater(edit::requestFocus);
  }

  private void commitEdit() {
    ReviewItem e = selected();
    if (e == null || e.startedAt() == null) {
      stopEditing();
      return;
    }
    OffsetDateTime start = OffsetDateTime.parse(e.startedAt());
    OffsetDateTime end = TimeText.after(edit.getText(), start);
    if (end == null) {
      hint.setText("⚠ 「" + edit.getText() + "」不行：要么不是 HH:MM，要么会让这一段超过 12 小时");
      return;
    }
    String iso = end.format(Clock.WIRE);
    stopEditing();
    onReschedule.accept(e, iso);
  }

  private void stopEditing() {
    editing = false;
    edit.setVisible(false);
    edit.setManaged(false);
    render();
  }

  /** 队列变了就重画；空队列由上层负责收窗。 */
  public void render(List<ReviewItem> newItems) {
    items = newItems;
    if (index >= items.size()) {
      index = 0;
    }
    runOnFx(this::render);
  }

  private void render() {
    title.setText("待确认 " + items.size() + " 条");
    rows.getChildren().clear();
    int shown = Math.min(items.size(), MAX_ROWS);
    for (int i = 0; i < shown; i++) {
      Label row = new Label(line(items.get(i)));
      row.setStyle(theme.label(13, i == index ? "current" : "item"));
      rows.getChildren().add(row);
    }
    if (items.size() > shown) {
      rows.getChildren().add(new Label("…另有 " + (items.size() - shown) + " 条"));
    }
    if (!editing) {
      hint.setText(selected() != null && selected().overlapPending()
          ? "⚠ 这一段与另一段相交而你还没认过：Enter 两段都算（放行并导出） · X 这段作废（不导出）"
              + " · ↑↓ 换一条 · Esc 稍后"
          : "↑↓ 选择 · Enter 确认 · T 改时间 · X 丢弃 · ←/N 记新的 · Esc 稍后");
    }
  }

  private static String line(ReviewItem e) {
    StringBuilder sb = new StringBuilder();
    sb.append(e.task() == null || e.task().isBlank() ? "未命名" : e.task());
    sb.append("  ").append(hhmm(e.startedAt())).append("→").append(hhmm(e.endedAt()));
    if (crossesMidnight(e)) {
      sb.append("(次日)");
    }
    sb.append("  ").append(human(e.durationMs()));
    if (e.overlapPending()) {
      sb.append(" · ⚠ 与另一段重叠");
    }
    if (e.guessedEnd()) {
      sb.append(" · 按最后心跳");
    }
    if (e.missed()) {
      sb.append(" · 到点没提示到你");
    }
    if (e.status().wire().equals("interrupted")) {
      sb.append(" · ").append(e.cause() == null ? "中断" : e.cause());
    }
    return sb.toString();
  }

  private static boolean crossesMidnight(ReviewItem e) {
    if (e.endedAt() == null || e.startedAt() == null) {
      return false;
    }
    return TimeText.isNextDay(OffsetDateTime.parse(e.endedAt()),
        OffsetDateTime.parse(e.startedAt()));
  }

  private static String hhmm(String iso) {
    return iso == null || iso.length() < 16 ? "—" : iso.substring(11, 16);
  }

  /** 崩溃后一眼要看的是"这段多长"，不足一小时就别写成 0时30分。 */
  private static String human(Long ms) {
    if (ms == null) {
      return "—";
    }
    long total = ms / 1000;
    long hours = total / 3600;
    long minutes = (total % 3600) / 60;
    return hours == 0 ? minutes + "分" : hours + "时" + minutes + "分";
  }

  public void show() {
    runOnFx(() -> {
      if (!stage.isShowing()) {
        stage.show();
      }
      stage.toFront();
      stage.requestFocus();
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

  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
  }

  public Node root() {
    return root;
  }

  /** 自检用：现在选中那条的任务名（没选中就空串）—— 键盘走到目标条目要有个可对账的读数。 */
  public String selectedTask() {
    ReviewItem s = selected();
    return s == null ? "" : s.task();
  }

  /** 自检用：这张卡现在用的是哪套配色。 */
  public String themeName() {
    return theme.name();
  }

  /** 换主题：重贴样式并重画（行是渲染时新建的，重画一次就跟上）。 */
  public void applyTheme(UiTheme.Palette theme) {
    this.theme = theme;
    title.setStyle(theme.label(14, "title"));
    hint.setStyle(theme.label(11, "muted"));
    root.setStyle(theme.window("warn"));
    runOnFx(this::render);
  }

  /** 自检用：等同于在输入框里敲一行（不改焦点，只改内容）。 */
  public void typeEdit(String text) {
    runOnFx(() -> edit.setText(text));
  }

  /** 自检用：等同于按下某个键（走 scene 的按键处理器）。 */
  public void press(KeyCode code) {
    runOnFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code,
        false, false, false, false)));
  }

  private static void runOnFx(Runnable body) {
    if (Platform.isFxApplicationThread()) {
      body.run();
    } else {
      Platform.runLater(body);
    }
  }
}
