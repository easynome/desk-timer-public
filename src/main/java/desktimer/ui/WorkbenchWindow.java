package desktimer.ui;

import desktimer.core.parse.CaptureParser;
import desktimer.core.time.DayAttribution;
import desktimer.core.time.Clock;
import desktimer.db.*;
import desktimer.platform.SoundPlayer;
import desktimer.diagnostics.RuntimeDiagnostics;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** 按需打开的任务、时间线和常用设置；不在后台持续刷新列表。 */
public final class WorkbenchWindow {
  private final Stage stage = new Stage();
  private final VBox root = new VBox(14);
  private final TabPane tabs = new TabPane();
  private final ListView<Todo> tasks = new ListView<>();
  private final ListView<EntryRepository.TimelineItem> timeline = new ListView<>();
  private final DatePicker day = new DatePicker(LocalDate.parse(DayAttribution.checkinDate(new Clock().wall())));
  private final Label message = new Label();
  private final Label title = new Label("学习工作台");
  private final Label subtitle = new Label("任务决定做什么，时间线记录实际发生了什么。");
  private final TodoRepository todos;
  private final EntryRepository entries;
  private final SettingRepository settings;
  private final Consumer<Todo> startTask;
  private final Consumer<Todo> finishTask;
  private final java.util.function.BiFunction<Todo, Long, String> backfill;
  private final Runnable applyPreferences;
  private UiTheme.Palette theme;

  public WorkbenchWindow(TodoRepository todos, EntryRepository entries, SettingRepository settings,
      Consumer<Todo> startTask, Consumer<Todo> finishTask,
      java.util.function.BiFunction<Todo, Long, String> backfill, Runnable applyPreferences,
      Runnable resetPositions, UiTheme.Palette theme) {
    this.todos = todos; this.entries = entries; this.settings = settings;
    this.startTask = startTask; this.finishTask = finishTask; this.backfill = backfill;
    this.applyPreferences = applyPreferences; this.theme = theme;
    root.setPadding(new Insets(22));
    title.setStyle(theme.label(22, "title") + " -fx-font-weight:bold;");
    subtitle.setStyle(theme.label(12, "muted"));
    message.setWrapText(true);
    tasks.setId("tasks-list"); timeline.setId("timeline-list");
    tasks.setCellFactory(v -> new ListCell<>() {
      @Override protected void updateItem(Todo item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? null : item.title() + (item.tags().isEmpty() ? "" : "   #" + String.join(" #", item.tags())));
      }
    });
    TextField quick = new TextField(); quick.setPromptText("添加任务，Enter 保存；支持末尾 #标签 @项目");
    Runnable add = () -> act(() -> {
      var plan = new CaptureParser().parseTodo("+ " + quick.getText());
      todos.add(plan.title(), entries.ensureProject(plan.project()), plan.tags());
      quick.clear(); refresh(); message.setText("任务已保存");
    });
    quick.setOnAction(e -> add.run());
    Button addBtn = button("添加", add);
    Button start = button("开始 25 分番茄", () -> selected(startTask));
    Button finish = button("标记完成", () -> selected(finishTask));
    Button fill = button("完成并补录", () -> selected(todo -> {
      TextInputDialog dialog = new TextInputDialog("25m");
      dialog.initOwner(stage); dialog.setTitle("补录实际用时"); dialog.setHeaderText(todo.title());
      dialog.getDialogPane().getStylesheets().add(UiTheme.class.getResource("/ui/desk-timer.css").toExternalForm());
      dialog.getDialogPane().setStyle(theme.window("plain"));
      dialog.setContentText("例如 25m、1h30m；与已有记录相交时拒绝补录");
      dialog.showAndWait().ifPresent(value -> act(() -> {
        Long ms = new CaptureParser().durationOnly(value);
        if (ms == null || ms <= 0) throw new IllegalArgumentException("请输入有效时长");
        String error = backfill.apply(todo, ms);
        if (error != null) throw new IllegalStateException(error);
        refresh(); message.setText("完成与补录已一起保存");
      }));
    }));
    HBox quickRow = new HBox(8, quick, addBtn); HBox.setHgrow(quick, Priority.ALWAYS);
    VBox taskPane = new VBox(12, quickRow, tasks, new FlowPane(8, 8, start, finish, fill),
        new Label("最多显示最近 200 条未完成任务；开始计时不会自动标记完成。"));
    VBox.setVgrow(tasks, Priority.ALWAYS);
    timeline.setCellFactory(v -> new ListCell<>() {
      @Override protected void updateItem(EntryRepository.TimelineItem item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) { setText(null); return; }
        String time = item.started().substring(11, 19) + " → " + (item.ended() == null ? "进行中" : item.ended().substring(11, 19));
        String duration = item.durationMs() == null ? "尚未结算" : String.format("%.1f 分钟", item.durationMs() / 60000d);
        setText(time + "   " + item.task() + (item.project() == null ? "" : "  @" + item.project())
            + "\n" + duration + " · " + kindName(item.kind()) + " · " + statusName(item.status())
            + ("manual_backfill".equals(item.origin()) ? " · 补录" : " · 实测") + (item.review() ? " · 待确认" : ""));
      }
    });
    day.setOnAction(e -> refreshTimeline());
    Button previous = button("前一天", () -> day.setValue(day.getValue().minusDays(1)));
    Button next = button("后一天", () -> day.setValue(day.getValue().plusDays(1)));
    VBox timePane = new VBox(12, new HBox(8, previous, day, next), timeline,
        new Label("按 08:00 归属日显示最近 500 条；并行段分别展示，休息与工作区分。"));
    VBox.setVgrow(timeline, Priority.ALWAYS);
    tabs.getTabs().addAll(tab("任务", taskPane), tab("时间线", timePane), tab("偏好", preferences(resetPositions)));
    root.getChildren().addAll(title, subtitle, tabs, message);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    Scene scene = new Scene(root, 760, 600); UiTheme.styleScene(scene);
    scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.hide(); });
    stage.setTitle("desk-timer · 工作台"); stage.setScene(scene);
    stage.setMinWidth(660); stage.setMinHeight(480);
    applyTheme(theme);
  }

  private BorderPane preferences(Runnable resetPositions) {
    CheckBox compact = check("紧凑计时面板", "ui.compact", false);
    CheckBox animation = check("进度条平滑动画（关闭可减少绘制）", "ui.animations", false);
    CheckBox fullscreen = check("普通休息使用全屏模式", "rest.fullscreen", true);
    CheckBox soundFullscreen = check("前台全屏时仍播放提醒音", "sound.fullscreen", true);
    CheckBox hide = check("未钉住的计时面板失焦隐藏", "ui.panel.auto_hide", true);
    CheckBox guard = check("连续使用保护：到达阈值强制休息", "guard.enabled", true);
    Spinner<Integer> threshold = new Spinner<>(30, 180, Math.max(30, Math.min(180, number("guard.limit_min", 90))));
    Spinner<Integer> length = new Spinner<>(5, 30, Math.max(5, Math.min(30, number("guard.rest_min", 10))));
    threshold.setPrefWidth(110); length.setPrefWidth(110);
    ComboBox<String> sound = new ComboBox<>();
    sound.setPrefWidth(180);
    sound.getItems().setAll("系统新短信", "风铃", "滴答", "静音");
    List<String> choices = List.of("system", "wind-chime", "tic-toc", "off");
    sound.getSelectionModel().select(Math.max(0, choices.indexOf(read("sound.choice", "system"))));
    Button preview = button("试听", () -> SoundPlayer.play(choices.get(sound.getSelectionModel().getSelectedIndex())));
    Button save = button("保存并应用", () -> act(() -> {
      settings.put("ui.compact", "" + compact.isSelected());
      settings.put("ui.animations", "" + animation.isSelected());
      settings.put("rest.fullscreen", "" + fullscreen.isSelected());
      settings.put("sound.fullscreen", "" + soundFullscreen.isSelected());
      settings.put("ui.panel.auto_hide", "" + hide.isSelected());
      settings.put("guard.enabled", "" + guard.isSelected());
      settings.put("guard.limit_min", "" + threshold.getValue());
      settings.put("guard.rest_min", "" + length.getValue());
      settings.put("sound.choice", choices.get(sound.getSelectionModel().getSelectedIndex()));
      applyPreferences.run(); message.setText("偏好已应用；改变阈值不会清除已经累计的使用时间。");
    }));
    save.getStyleClass().add("primary-button"); save.setId("preferences-save");
    Label details = secondary("连续离席或实际休息满 5 分钟才重置累计；暂停计时与重启不会清零。\n强制休息不提供延后/跳过，紧急结束保留累计。");
    details.setWrapText(true);
    Label diagnostics = secondary(RuntimeDiagnostics.status());
    Button refreshDiagnostics = button("刷新诊断", () -> diagnostics.setText(RuntimeDiagnostics.status()));
    Button openLogs = button("打开日志目录", () -> act(() -> {
      var directory = RuntimeDiagnostics.directory();
      if (directory == null) throw new IllegalStateException("日志目录尚未初始化");
      java.awt.Desktop.getDesktop().open(directory.toFile());
    }));
    FlowPane durations = new FlowPane(16, 10,
        new VBox(6, secondary("累计使用阈值 · 分钟"), threshold),
        new VBox(6, secondary("强制休息时长 · 分钟"), length));
    VBox pane = new VBox(14,
        section("外观与交互", compact, animation, hide, secondary("捕获输入窗保持失焦隐藏；动画默认关闭。")),
        section("休息保护", guard, durations, fullscreen, details),
        section("提醒声音", new FlowPane(10, 8, sound, preview), soundFullscreen),
        section("运行诊断", diagnostics, new FlowPane(10, 8, refreshDiagnostics, openLogs)));
    pane.setPadding(new Insets(0, 12, 8, 0));
    ScrollPane scroll = new ScrollPane(pane); scroll.setId("preferences-scroll");
    scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    FlowPane footer = new FlowPane(10, 8, save, button("重置窗口位置", resetPositions));
    footer.getStyleClass().add("settings-footer");
    BorderPane layout = new BorderPane(scroll); layout.setBottom(footer);
    return layout;
  }

  private static Label secondary(String text) {
    Label label = new Label(text); label.getStyleClass().add("secondary"); label.setWrapText(true); return label;
  }
  private static VBox section(String heading, javafx.scene.Node... nodes) {
    Label label = new Label(heading); label.getStyleClass().add("section-title");
    VBox box = new VBox(12, label); box.getChildren().addAll(nodes); box.getStyleClass().add("settings-card"); return box;
  }

  public static String kindName(String kind) {
    return switch(kind) { case "pomodoro_work" -> "番茄工作"; case "rest_short", "rest_long" -> "休息";
      case "count_down" -> "倒计时"; default -> "正计时"; };
  }
  public static String statusName(String status) {
    return switch(status) { case "finished" -> "已完成"; case "paused" -> "暂停"; case "aborted" -> "已放弃";
      case "interrupted" -> "中断"; default -> "进行中"; };
  }
  private CheckBox check(String title, String key, boolean fallback) {
    CheckBox box = new CheckBox(title); box.setSelected(Boolean.parseBoolean(read(key, "" + fallback))); return box;
  }
  private String read(String key, String fallback) { try { return settings.get(key, fallback); } catch(Exception e) { return fallback; } }
  private int number(String key, int fallback) { try { return Integer.parseInt(read(key, "" + fallback)); } catch(Exception e) { return fallback; } }
  private Tab tab(String title, javafx.scene.Node content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
  private Button button(String title, Runnable action) { Button b = new Button(title); b.setOnAction(e -> action.run()); return b; }
  private void selected(Consumer<Todo> action) {
    Todo todo = tasks.getSelectionModel().getSelectedItem();
    if (todo == null) { message.setText("先选择一条任务"); return; }
    act(() -> { action.accept(todo); refresh(); });
  }
  private interface Action { void run() throws Exception; }
  private void act(Action action) { try { action.run(); } catch(Exception e) {
    RuntimeDiagnostics.failure(RuntimeDiagnostics.Operation.UI, e); message.setText("未完成：" + e.getMessage()); } }
  private void refreshTimeline() { act(() -> {
    if (day.getValue() != null) timeline.getItems().setAll(entries.timeline(day.getValue().toString(), 500));
  }); }
  public void refresh() { act(() -> tasks.getItems().setAll(todos.recentPending())); refreshTimeline(); }
  public void show() { if (!Platform.isFxApplicationThread()) { Platform.runLater(this::show); return; }
    refresh(); stage.show(); stage.toFront(); }
  public void applyTheme(UiTheme.Palette value) {
    theme = value; root.setStyle(theme.window("plain")); message.setStyle(theme.label(12, "muted"));
    title.setStyle(theme.label(22, "title") + " -fx-font-weight:bold;");
    subtitle.setStyle(theme.label(12, "muted"));
  }
  public javafx.scene.Parent root() { return root; }
  public void hide() { stage.hide(); }
}
