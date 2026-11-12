package desktimer.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
  private record ForcedControls(Button exit, Label guidance) {}
  private final List<ForcedControls> forcedControls = new ArrayList<>();
  private boolean forced;
  private boolean canFinish;
  private boolean saveFailed;
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
      Label guidance = new Label();
      guidance.setStyle(theme.label(15, "muted"));
      if (!forced) {
        Button later = new Button("延后 5 分钟"); later.setDisable(!canPostpone);
        later.setOnAction(e -> postpone.run());
        Button next = new Button("跳过休息，进入下一番茄"); next.setOnAction(e -> skip.run());
        later.setStyle(theme.buttonStyle()); next.setStyle(theme.buttonStyle());
        actions.getChildren().addAll(later, next);
      } else {
        Button exit = new Button("紧急结束（保留使用累计）");
        exit.setStyle(theme.buttonStyle()); exit.setOnAction(e -> emergency.run()); actions.getChildren().add(exit);
        forcedControls.add(new ForcedControls(exit, guidance));
      }
      VBox root = new VBox(24, eyebrow, title, clock, advice, actions);
      if (forced) root.getChildren().add(guidance);
      root.setPadding(new Insets(40)); root.setAlignment(Pos.CENTER); root.setStyle(theme.window("plain"));
      var bounds = screen.getBounds();
      Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight()); UiTheme.styleScene(scene);
      stage.setScene(scene); stage.setX(bounds.getMinX()); stage.setY(bounds.getMinY());
      stage.setWidth(bounds.getWidth()); stage.setHeight(bounds.getHeight()); stage.setAlwaysOnTop(true);
      if (forced) scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
        if (e.getCode() == KeyCode.ESCAPE && canFinish) { e.consume(); emergency.run(); }
      });
      stage.setOnCloseRequest(e -> {
        e.consume();
        if (!this.forced) skip.run();
        else if (canFinish) emergency.run();
      });
      windows.add(stage); stage.show();
    }
  }

  public void render(long leftMs, boolean qualified) {
    canFinish = qualified;
    long seconds = Math.max(0, (leftMs + 999) / 1000);
    String text = String.format("%02d:%02d", seconds / 60, seconds % 60);
    for (Label label : clocks) if (!text.equals(label.getText())) label.setText(text);
    for (ForcedControls controls : forcedControls) {
      controls.exit().setText(qualified ? "结束休息（清零使用累计）" : "紧急结束（保留使用累计）");
      controls.guidance().setText(saveFailed ? "休息记录未能保存，请点击按钮重试。"
          : qualified ? "可点击按钮或按 Esc 退出；倒计时结束也会自动退出。"
          : "休满 5 分钟即可正常退出，也可等待倒计时自动结束。");
    }
  }
  public void showSaveFailure() { saveFailed = true; }
  public void hide() {
    for (Stage window : windows) window.hide();
    windows.clear(); clocks.clear(); forcedControls.clear(); canFinish = false; saveFailed = false;
  }
  public boolean showing() { return !windows.isEmpty(); }
  public int screenCount() { return windows.size(); }
  public void raise() { for (Stage window : windows) window.toFront(); }
  public javafx.scene.Parent root() { return windows.get(0).getScene().getRoot(); }
}
