package desktimer.ui;

import desktimer.db.Note;
import desktimer.db.NoteRepository;
import desktimer.db.SettingRepository;
import java.util.List;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
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
import javafx.util.Duration;

/**
 * 笔记卡：随手记唯一的"回看"入口（M2-c）。写进去之后能在界面里翻，
 * 而不是只能去读 {@code exports/notes.jsonl}。
 *
 * 三条判据决定它长这样：
 *  - 默认视图是**今天 ∪ 全部置顶**，一旦输入查询就自动变成**全库** ——
 *    "回看今天的流水"和"找上周记过的某件事"是两件事，用打字这个动作区分，不设模式开关；
 *  - 它**不进唤起梯子**（面板 &gt; 待确认卡 &gt; 待办卡 &gt; 捕获窗）。笔记是主动查阅的东西，
 *    不是"此刻该看的那个窗"，插进梯子就会抢掉待办卡的抬升（同设置窗那条理由）；
 *  - 卡上的 {@code F} 是**数据置顶**（这条值不值得常驻），窗口置顶仍是面板那把 {@code P} ——
 *    两个语义不许挤在同一个键上，否则同一封信在两张窗里读出两个意思。
 */
public final class NoteCard {

  private static final String POS_KEY = "ui.notecard.bounds";
  private static final double W = 460;
  private static final double H = 300;

  private final Stage stage = new Stage();
  private final VBox root = new VBox(4);
  private final VBox rows = new VBox(2);
  private final Label title = new Label("随手记");
  private final Label hint = new Label("");
  private final TextField search = new TextField();

  private final NoteRepository notes;
  private final Supplier<String> currentDay;
  private final SettingRepository settings;
  private UiTheme.Palette theme;

  /** 防抖：边打字边查会让每按一个键都跑一次 LIKE 全库，卡会在手感上发涩。 */
  private final PauseTransition debounce = new PauseTransition(Duration.millis(150));

  private List<Note> shown = List.of();
  private int selected;
  private String query = "";

  public NoteCard(NoteRepository notes, Supplier<String> currentDay, SettingRepository settings,
      UiTheme.Palette theme) {
    this.notes = notes;
    this.currentDay = currentDay;
    this.settings = settings;
    this.theme = theme;

    title.setStyle(theme.label(14, "title"));
    hint.setStyle(theme.label(11, "dim"));
    hint.setWrapText(true);
    search.setPromptText("搜正文 · #只搜标签 · @只搜项目（Esc 清掉）");
    search.setStyle("-fx-font-size: 13px;");
    search.textProperty().addListener((o, was, val) -> {
      query = val == null ? "" : val.trim();
      debounce.setOnFinished(e -> reload());
      debounce.playFromStart();
    });

    root.setPadding(new Insets(16));
    root.setSpacing(10);
    // 焦点默认会落在场景里第一个可聚焦的控件上 —— 也就是搜索框，那样 ↑↓/F 全被"打字"守卫吞掉，
    // 卡一开就成了只能打字的框。让根节点可聚焦，show() 里把焦点给它，按 / 才交给输入框。
    root.setFocusTraversable(true);
    root.setStyle(theme.window("plain"));
    root.getChildren().addAll(title, search, rows, hint);

    // 高度按内容算：搜索框 + 提示条一起换行时，写死高度会裁掉最后一行
    Scene scene = new Scene(root, W, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
    UiTheme.styleScene(scene);
    scene.setOnKeyPressed(this::onKey);
    stage.initStyle(StageStyle.TRANSPARENT);
    stage.setScene(scene);
    stage.setAlwaysOnTop(true);
    WinPos pos = WindowPlacement.load(settings, POS_KEY, 160, 260, W, H);
    stage.setX(pos.x());
    stage.setY(pos.y());
    WindowDrag.attach(root, stage, search, this::persistPosition);
    stage.focusedProperty().addListener((o, was, is) -> {
      if (!is) {
        persistPosition();
      }
    });
  }

  /** ↑↓ 翻、P 置顶、/ 进搜索、Esc 两级退出（先清搜索词，再收卡）。 */
  private void onKey(KeyEvent e) {
    // 焦点在搜索框里时，键归输入框：这时候按 P 是打字母 p，不是"置顶这一条"。
    // 唯独 Esc 例外 —— 它得能把输入状态退出来。
    if (search.isFocused() && !Keys.any(e, KeyCode.ESCAPE)) {
      return;
    }
    if (Keys.any(e, KeyCode.UP)) {
      move(-1);
      e.consume();
    } else if (Keys.any(e, KeyCode.DOWN)) {
      move(1);
      e.consume();
    } else if (Keys.any(e, KeyCode.F)) {
      // 数据置顶用 F（favorite），不借 P —— P 在这套界面里只有一个意思：窗口压在最顶层
      togglePin();
      e.consume();
    } else if (Keys.any(e, KeyCode.SLASH)) {
      search.requestFocus();
      e.consume();
    } else if (Keys.any(e, KeyCode.ESCAPE)) {
      if (!query.isEmpty()) {
        runOnFx(() -> {
          search.clear();
          root.requestFocus();
        });
        query = "";
        reload();
      } else {
        hide();
      }
      e.consume();
    }
  }

  private void move(int delta) {
    if (shown.isEmpty()) {
      return;
    }
    selected = Math.floorMod(selected + delta, shown.size());
    render();
  }

  /**
   * 置顶切换后**选中留在原来那条**：整表会因 pinned 重新排序，
   * 如果跟着行内容跳，用户按一次 P 就被扔到别的笔记上，第二次按就按到别人的了。
   */
  private void togglePin() {
    if (shown.isEmpty()) {
      return;
    }
    Note n = shown.get(selected);
    try {
      notes.setPinned(n.id(), !n.pinned());
      System.out.println("NOTE_PIN " + n.id() + " to=" + !n.pinned());
    } catch (Exception e) {
      System.out.println("NOTE_PIN_FAILED " + e.getMessage());
      return;
    }
    String kept = n.id();
    reload();
    for (int i = 0; i < shown.size(); i++) {
      if (shown.get(i).id().equals(kept)) {
        selected = i;
        break;
      }
    }
    render();
  }

  /** 重新取数：有查询词就是全库，没有就是今天 ∪ 置顶。 */
  private void reload() {
    try {
      shown = query.isEmpty() ? notes.pinnedFirst(currentDay.get()) : notes.search(query);
      if (selected >= shown.size()) {
        selected = Math.max(0, shown.size() - 1);
      }
    } catch (Exception e) {
      System.out.println("NOTE_LOAD_FAILED " + e.getMessage());
      shown = List.of();
    }
    runOnFx(this::render);
  }

  /** 标题行写清现在看的是哪个集合 —— 默认视图与全库结果长得太像，不说范围就会看错。 */
  private void render() {
    rows.getChildren().setAll(java.util.stream.IntStream.range(0, shown.size())
        .mapToObj(this::rowLabel).toList());
    long pinned = shown.stream().filter(Note::pinned).count();
    title.setText("随手记 · " + (query.isEmpty()
        ? "今天 " + shown.size() + " 条（置顶 " + pinned + " 条常驻）"
        : "搜「" + query + "」· 全库命中 " + shown.size() + " 条"
            + (shown.size() >= NoteRepository.SEARCH_LIMIT ? "（已到上限，继续打几个字）" : "")));
    hint.setText("↑↓ 翻 · F 置顶这一条 · / 搜索 · Esc 清搜索或收起 · 只读：改内容就再记一条");
  }

  private Label rowLabel(int i) {
    Note n = shown.get(i);
    String clock = n.createdAt() == null || n.createdAt().length() < 16
        ? "" : n.createdAt().substring(11, 16);
    String tags = n.tags().isEmpty() ? "" : " " + n.tags().stream().map(t -> "#" + t).reduce(
        "", String::concat);
    String body = n.body().length() > 60 ? n.body().substring(0, 59) + "…" : n.body();
    Label l = new Label((i == selected ? "● " : "  ") + (n.pinned() ? "★" : " ")
        + clock + " " + body + tags);
    l.setStyle(theme.label(13, i == selected ? "current" : "item"));
    return l;
  }

  /** 自检用：这张卡现在用的是哪套配色。 */
  public String themeName() {
    return theme.name();
  }

  /** 换主题：重贴样式并重读列表（行是渲染时新建的）。 */
  public void applyTheme(UiTheme.Palette theme) {
    this.theme = theme;
    title.setStyle(theme.label(14, "title"));
    hint.setStyle(theme.label(11, "dim"));
    root.setStyle(theme.window("plain"));
    reload();
  }

  public void show() {
    reload();
    runOnFx(() -> {
      if (!stage.isShowing()) {
        stage.show();
      }
      stage.toFront();
      stage.requestFocus();
      root.requestFocus();
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

  /** 自检取证用：现在卡上写着什么行。 */
  public List<String> lineTexts() {
    return rows.getChildren().stream().map(n -> ((Label) n).getText()).toList();
  }

  public String titleLine() {
    return title.getText();
  }

  public Node root() {
    return root;
  }

  public void persistPosition() {
    WindowPlacement.save(stage, settings, POS_KEY);
  }

  /** 自检用：等同于在搜索框里敲一行（直接走 reload，不等防抖）。 */
  public void typeSearch(String text) {
    runOnFx(() -> search.setText(text));
    query = text == null ? "" : text.trim();
    reload();
  }

  /** 自检用：把键发给这张卡。 */
  public void press(KeyCode code) {
    runOnFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code,
        false, false, false, false)));
  }

  private static void runOnFx(Runnable r) {
    if (Platform.isFxApplicationThread()) {
      r.run();
    } else {
      Platform.runLater(r);
    }
  }
}