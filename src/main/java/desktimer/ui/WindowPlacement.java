package desktimer.ui;

import desktimer.db.SettingRepository;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** 窗口位置的读与存：存原样，读回来先按当前屏幕钳制（拔了副屏就不认旧坐标）。 */
public final class WindowPlacement {

  /** 至少露出这么多像素才算"还在屏幕内"；拔了副屏时靠它判断。 */
  private static final double MIN_VISIBLE = 60;

  private WindowPlacement() {
  }

  public static WinPos load(SettingRepository settings, String key, double defX, double defY,
      double width, double height) {
    WinPos fallback = new WinPos(defX, defY);
    WinPos stored;
    try {
      stored = WinPos.parse(settings.get(key, null));
    } catch (Exception e) {
      System.out.println("POS_LOAD_FAILED " + key + " " + e.getMessage());
      stored = null;
    }
    List<Rectangle2D> screens = screens();
    WinPos pos = (stored == null ? fallback : stored)
        .clampedInto(screens, width, height, MIN_VISIBLE, fallback);
    System.out.println("POS_LOADED " + key + "=" + pos.wire()
        + " src=" + (stored == null ? "default" : (pos == stored ? "db" : "db-offscreen"))
        + " screens=" + screens.size());
    return pos;
  }

  /** 存的是拖动结束时的真实位置，不钳制：用户把窗口推到屏边外是意图，不是数据坏了。 */
  public static void save(Stage stage, SettingRepository settings, String key) {
    try {
      WinPos pos = new WinPos(stage.getX(), stage.getY());
      settings.put(key, pos.wire());
      System.out.println("POS_SAVED " + key + "=" + pos.wire());
    } catch (Exception e) {
      System.out.println("POS_SAVE_FAILED " + key + " " + e.getMessage());
    }
  }

  /** 用 visualBounds 而不是 bounds：任务栏那一条不算能放窗的地方。 */
  private static List<Rectangle2D> screens() {
    List<Rectangle2D> out = new ArrayList<>();
    for (Screen s : Screen.getScreens()) {
      out.add(s.getVisualBounds());
    }
    return out;
  }
}
