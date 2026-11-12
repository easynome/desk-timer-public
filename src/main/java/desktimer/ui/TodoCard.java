package desktimer.ui;

import desktimer.core.parse.CaptureParser;
import desktimer.db.SettingRepository;
import desktimer.db.Todo;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 * 待办卡：把"想起过但没干"的东西摊在眼前，两个动作分清两种收法。
 *
 *  - Enter = 标记完成（只改状态，不造计时条目 —— "不自动生成计时条目"是他定的口径）；
 *  - 空格 = 完成并补录时间：先输入时长（25m / 1h30m），回车才落一条 finished 条目并挂上；
 *  - X = 丢弃这条待办（唯一会真删行的动作，所以刻意不给它备选键：中文输入法下反而难误触）；
 *  - Esc = 稍后：按归属日抑制，今天不再自动抬这张卡。
 *
 * 无边框所以自己接了拖动手柄；位置与"稍后到哪一天"都存在 setting 表。
 */
public final class TodoCard {

  private static final String POS_KEY = "ui.todo.bounds";
  private static final String SUPPRESS_KEY = "todo.card.suppress_until";
  private static final double W = 520;
  private static final double H = 200;
  private static final int MAX_ROWS = 6;

  private final Stage stage = new Stage();
  private final VBox root = new VBox(4);
  private final VBox rows = new VBox(2);
  private final Label title = new Label("待办");
  private final Label hint = new Label("");
  private final TextField edit = new TextField();
  private final SettingRepository settings;
  private UiTheme.Palette theme;

  private final Consumer<Todo> onComplete;
  private final Backfill onBackfill;
  private final Runnable onNewInput;
  private final Consumer<Todo> onDiscard;
  private final Supplier<String> currentDay;

  private List<Todo> items = List.of();
  private int index;
  private boolean editing;

  /**
   * 补录：给这条待办补一段时长为 ms 的计时（由 UiController 落到仓储）。
   * 返回 null 表示成了；返回一句话就是失败原因，必须显示出来 ——
   * 否则用户按下回车后卡片看起来什么都没发生，只能瞎猜再按一次。
   */
  public interface Backfill {
    String accept(Todo item, long ms);
  }

  public TodoCard(Consumer<Todo> onComplete, Backfill onBackfill, Consumer<Todo> onDiscard,
      Runnable onNewInput, Supplier<String> currentDay, SettingRepository settings,
      UiTheme.Palette theme) {
    this.onComplete = onComplete;
    this.onBackfill = onBackfill;
    this.onDiscard = onDiscard;
    this.onNewInput = onNewInput;
    this.currentDay = currentDay;
    this.settings = settings;
    this.theme = theme;

    title.setStyle(theme.label(14, "title"));
    rows.setStyle("-fx-padding: 2 0 2 8;");
    hint.setStyle(theme.label(11, "muted"));
    hint.setWrapText(true);
    edit.setPromptText("补多久？如 25m 或 1h30m");
    edit.setStyle("-fx-font-size: 14px;");
    edit.setVisible(false);
    edit.setManaged(false);

    root.setPadding(new Insets(16));
    root.setSpacing(10);
    root.setStyle(theme.window("todo"));
    root.getChildren().addAll(title, rows, edit, hint);

    // 高度按内容算：提示条会绕行走，写死高度就会裁掉最后一行
    Scene scene = new Scene(root, W, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
    UiTheme.styleScene(scene);
    scene.setOnKeyPressed(this::onKey);
    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setAlwaysOnTop(true);
    stage.setScene(scene);
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 120, 300, W, H);
    stage.setX(pos.x());
    stage.setY(pos.y());
    WindowDrag.attach(root, stage, edit, this::persistPosition);
    render();
  }

  /** 键盘处理只挂在这一处：输入框里的回车也冒到这里，自检才能用同一个入口。 */
  private void onKey(KeyEvent e) {
    if (editing) {
      if (Keys.any(e, KeyCode.ENTER)) {
        commitBackfill();
      } else if (Keys.any(e, KeyCode.ESCAPE)) {
        stopEditing();
      }
      e.consume();
      return;
    }
    if (Keys.any(e, KeyCode.UP)) {
      move(-1);
    } else if (Keys.any(e, KeyCode.DOWN)) {
      move(1);
    } else if (Keys.any(e, KeyCode.ENTER)) {
      act(onComplete);
    } else if (Keys.any(e, KeyCode.SPACE, KeyCode.D)) {
      startEditing();
    } else if (Keys.any(e, KeyCode.X)) {
      act(onDiscard);
    } else if (Keys.any(e, KeyCode.N, KeyCode.LEFT)) {
      hide();
      onNewInput.run();
    } else if (Keys.any(e, KeyCode.ESCAPE)) {
      suppressToday();
      hide();
    } else {
      return;
    }
    e.consume();
  }

  private void act(Consumer<Todo> action) {
    Todo selected = selected();
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

  private Todo selected() {
    return items.isEmpty() || index >= items.size() ? null : items.get(index);
  }

  private void startEditing() {
    Todo t = selected();
    if (t == null) {
      return;
    }
    editing = true;
    edit.setText("");
    edit.setPromptText("给「" + shorten(t.title()) + "」补多久？如 25m / 1h30m / 90分钟");
    edit.setVisible(true);
    edit.setManaged(true);
    hint.setText("输入时长后回车落一条计时条目 · Esc 取消（只标完成不补时间请用 Enter）");
    Platform.runLater(edit::requestFocus);
  }

  private void commitBackfill() {
    Todo t = selected();
    String raw = edit.getText() == null ? "" : edit.getText().trim();
    if (t == null) {
      stopEditing();
      return;
    }
    if (raw.contains(":")) {
      hint.setText("⚠ 补录只认时长（25m / 1h30m / 90分钟），不认 14:20 这种时刻："
          + "时刻是从今往后算的，拿来往回补会算成一段未来");
      return;
    }
    Long ms = CaptureParser.durationOnly(raw);
    if (ms == null) {
      hint.setText("⚠ 「" + raw + "」读不出时长：写成 25m / 1h30m / 90分钟 这样，不超过 12 小时");
      return;
    }
    String reason = onBackfill.accept(t, ms);
    editing = false;
    edit.setVisible(false);
    edit.setManaged(false);
    render();
    if (reason != null) {
      // 补录被拒时待办必须还留在清单里，而且原因要写在卡片上：
      // 只往 stdout 打一行，用户看到的是"按了回车什么都没发生"
      hint.setText("⚠ " + reason);
    }
  }

  /** "稍后"不是"再也不看"：抑制到当前归属日结束，今天不再自动抬这张卡。 */
  private void suppressToday() {
    String day = currentDay.get();
    if (day == null || day.isEmpty()) {
      return;
    }
    write(SUPPRESS_KEY, day);
    System.out.println("TODO_CARD_SUPPRESSED checkin_date=" + day);
  }

  /** 归属日翻页了才恢复自动抬卡 —— 判据由调用方给，卡片自己不猜今天几号（08:00 边界在上层）。 */
  public boolean suppressedFor(String checkinDate) {
    return checkinDate != null && checkinDate.equals(read(SUPPRESS_KEY, ""));
  }

  private void stopEditing() {
    editing = false;
    edit.setVisible(false);
    edit.setManaged(false);
    render();
  }

  public void render(List<Todo> newItems) {
    items = newItems;
    if (index >= items.size()) {
      index = 0;
    }
    runOnFx(this::render);
  }

  private void render() {
    long open = items.stream().filter(t -> !t.done()).count();
    title.setText("待办 " + open + " 件未完成");
    rows.getChildren().clear();
    int shown = Math.min(items.size(), MAX_ROWS);
    for (int i = 0; i < shown; i++) {
      Label row = new Label(line(items.get(i)));
      row.setStyle(theme.label(13, i == index ? "current" : "item"));
      rows.getChildren().add(row);
    }
    if (items.size() > shown) {
      rows.getChildren().add(new Label("…另有 " + (items.size() - shown) + " 件"));
    }
    if (!editing) {
      hint.setText("↑↓ 选择 · Enter 标记完成 · 空格 补录时间 · X 丢弃 · ←/N 记新的 · Esc 稍后");
    }
  }

  private static String line(Todo t) {
    StringBuilder sb = new StringBuilder(t.done() ? "✓ " : "＋ ");
    sb.append(shorten(t.title()));
    if (t.done()) {
      sb.append(" 已完成");
    }
    if (!t.tags().isEmpty()) {
      sb.append(" · ").append(String.join("/", t.tags()));
    }
    if (!t.entryIds().isEmpty()) {
      sb.append(" · 已挂 ").append(t.entryIds().size()).append(" 段");
    }
    return sb.toString();
  }

  private static String shorten(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 26 ? s : s.substring(0, 25) + "…";
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

  public Node root() {
    return root;
  }

  public String hintLine() {
    return hint.getText();
  }

  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
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
    root.setStyle(theme.window("todo"));
    runOnFx(this::render);
  }

  /** 自检用：等同于在补录框里敲一行。 */
  public void typeEdit(String text) {
    runOnFx(() -> edit.setText(text));
  }

  /** 自检用：等同于按下某个键（走 scene 的按键处理器）。 */
  public void press(KeyCode code) {
    runOnFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code,
        false, false, false, false)));
  }

  private String read(String key, String fallback) {
    try {
      return settings.get(key, fallback);
    } catch (Exception e) {
      System.out.println("SETTING_READ_FAILED " + key + " " + e.getMessage());
      return fallback;
    }
  }

  private void write(String key, String value) {
    try {
      settings.put(key, value);
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
