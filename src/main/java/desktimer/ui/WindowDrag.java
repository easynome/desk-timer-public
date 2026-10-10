package desktimer.ui;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 无边框窗口的拖动。TRANSPARENT 样式没有标题栏，不自己实现就拖不动。
 *
 * 用事件过滤器而不是 setOnMousePressed：过滤器在向下传播阶段执行，
 * 于是按住把手里的子节点也能带动窗口。{@code exclude} 例外：
 * 输入框里的按下是"选文字/放光标"，不能同时变成拖窗。
 */
public final class WindowDrag {

  private WindowDrag() {
  }

  public static void attach(Node handle, Stage stage, Node exclude, Runnable onMoved) {
    double[] off = new double[2];
    boolean[] dragging = new boolean[1];
    handle.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      dragging[0] = !isWithin((Node) e.getTarget(), exclude);
      if (dragging[0]) {
        off[0] = e.getScreenX() - stage.getX();
        off[1] = e.getScreenY() - stage.getY();
      }
    });
    // 起手在把手上就一路拖到底：中途掠过输入框不该把窗口甩到光标上
    handle.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
      if (dragging[0]) {
        stage.setX(e.getScreenX() - off[0]);
        stage.setY(e.getScreenY() - off[1]);
      }
    });
    handle.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
      if (dragging[0]) {
        dragging[0] = false;
        onMoved.run();
      }
    });
  }

  private static boolean isWithin(Node node, Node container) {
    if (container == null) {
      return false;
    }
    for (Node n = node; n != null; n = n.getParent()) {
      if (n == container) {
        return true;
      }
    }
    return false;
  }
}
