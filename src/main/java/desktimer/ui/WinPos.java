package desktimer.ui;

import java.util.List;
import javafx.geometry.Rectangle2D;

/**
 * 窗口左上角位置。存成 "x,y"，恢复时按当前屏幕做钳制。
 *
 * 钳制规则刻意保守：JavaFX 的 Screen 拿不到显示器序列号，
 * 拔了副屏之后光靠 bounds+dpi 可能误判"屏幕还在"，
 * 所以只要判不出可信位置就回主屏，而不是赌一把。
 */
public record WinPos(double x, double y) {

  public String wire() {
    return Math.round(x) + "," + Math.round(y);
  }

  /** 解析失败返回 null：位置丢了不该阻止程序启动。 */
  public static WinPos parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String[] parts = raw.split(",");
    if (parts.length != 2) {
      return null;
    }
    try {
      return new WinPos(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 保证窗口至少露出 {@code minVisible} 像素在某块屏幕的可视区内；
   * 做不到就回退到 {@code fallback}（调用方给的默认落点）。
   */
  public WinPos clampedInto(List<Rectangle2D> screens, double width, double height,
      double minVisible, WinPos fallback) {
    if (screens.isEmpty()) {
      return fallback;
    }
    if (visibleOnAny(screens, width, height, minVisible)) {
      return this;
    }
    return fallback;
  }

  public boolean visibleOnAny(List<Rectangle2D> screens, double width, double height,
      double minVisible) {
    for (Rectangle2D s : screens) {
      if (visibleOn(s, width, height, minVisible)) {
        return true;
      }
    }
    return false;
  }

  private boolean visibleOn(Rectangle2D s, double width, double height, double minVisible) {
    return x + minVisible > s.getMinX() && x + width - minVisible < s.getMaxX()
        && y + minVisible > s.getMinY() && y + height - minVisible < s.getMaxY();
  }
}
