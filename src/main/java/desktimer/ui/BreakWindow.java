package desktimer.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** 应用内全屏休息覆盖；不锁定系统，不安装全局输入拦截。 */
public final class BreakWindow {
  private final List<Stage> windows = new ArrayList<>();
  private final List<Label> clocks = new ArrayList<>();
  private final Runnable postpone;
  private final Runnable skip;
  private final Runnable emergency;
  private boolean forced;
  public BreakWindow(Runnable postpone, Runnable skip, Runnable emergency) {
    this.postpone = postpone; this.skip = skip; this.emergency = emergency;
  }

  public void show(boolean forced, boolean canPostpone, UiTheme.Palette theme) {
    hide(); this.forced = forced;
    for (Screen screen : Screen.getScreens()) {
      Stage stage = new Stage(StageStyle.UNDECORATED);
      Label eyebrow = new Label(forced ? "连续使用保护" : "专注之后，给自己一点空白");
      Label title = new Label(forced ? "现在休息一下" : "休息时间");
      Label clock = new Label("--:--"); clocks.add(clock);
      Label advice = new Label("离开屏幕，活动身体，看看远处。\n休息段会单独记录，工作计时保持暂停。");
      eyebrow.setStyle(theme.label(15, "muted"));
      title.setStyle(theme.label(36, "title") + " -fx-font-weight:bold;");
      clock.setStyle(theme.label(76, "title", true));
      advice.setStyle(theme.label(16, "muted")); advice.setAlignment(Pos.CENTER);
      HBox actions = new HBox(16); actions.setAlignment(Pos.CENTER);
      if (!forced) {
        Button later = new Button("延后 5 分钟"); later.setDisable(!canPostpone);
        later.setOnAction(e -> postpone.run());
        Button next = new Button("跳过休息，进入下一番茄"); next.setOnAction(e -> skip.run());
        later.setStyle(theme.buttonStyle()); next.setStyle(theme.buttonStyle());
        actions.getChildren().addAll(later, next);
      } else {
        Button exit = new Button("紧急结束本次休息（保留连续使用累计）");
        exit.setStyle(theme.buttonStyle()); exit.setOnAction(e -> emergency.run()); actions.getChildren().add(exit);
      }
      VBox root = new VBox(24, eyebrow, title, clock, advice, actions);
      root.setPadding(new Insets(40)); root.setAlignment(Pos.CENTER); root.setStyle(theme.window("plain"));
      var bounds = screen.getBounds();
      Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight()); UiTheme.styleScene(scene);
      stage.setScene(scene); stage.setX(bounds.getMinX()); stage.setY(bounds.getMinY());
      stage.setWidth(bounds.getWidth()); stage.setHeight(bounds.getHeight()); stage.setAlwaysOnTop(true);
      stage.setOnCloseRequest(e -> { e.consume(); if (!this.forced) skip.run(); });
      windows.add(stage); stage.show();
    }
  }

  public void render(long leftMs) {
    long seconds = Math.max(0, (leftMs + 999) / 1000);
    String text = String.format("%02d:%02d", seconds / 60, seconds % 60);
    for (Label label : clocks) if (!text.equals(label.getText())) label.setText(text);
  }
  public void hide() { for (Stage window : windows) window.hide(); windows.clear(); clocks.clear(); }
  public boolean showing() { return !windows.isEmpty(); }
  public int screenCount() { return windows.size(); }
  public void raise() { for (Stage window : windows) window.toFront(); }
  public javafx.scene.Parent root() { return windows.get(0).getScene().getRoot(); }
}
