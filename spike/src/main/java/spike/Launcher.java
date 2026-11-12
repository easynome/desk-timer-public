package spike;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * T-02 内存实测：AWT 托盘 + JavaFX 隐藏窗共存，采样自身 WorkingSet。
 * 判据：空闲驻留 ≤ 200 MB，窗口开合 cycles 次后不回弹到更高水位。
 */
public final class Launcher {

  public static void main(String[] args) throws Exception {
    int cycles = intArg(args, "--cycles", 20);
    long intervalMs = longArg(args, "--cycle-interval-ms", 400);
    long idleSec = longArg(args, "--idle-sec", 600);
    long sampleSec = longArg(args, "--sample-sec", 30);
    boolean destroyScene = boolArg(args, "--destroy-scene", true);
    boolean showWindow = boolArg(args, "--show", true);
    int staySec = intArg(args, "--stay-sec", 0);
    String captureArg = find(args, "--capture");
    String capturePath = captureArg == null ? "target/stay-capture.png" : captureArg;

    log("jvm-only", "JavaFX 与 AWT 都还没加载");
    installTray();
    log("after-awt-tray", "AWT 工具箱 + 托盘");

    startFx();
    log("after-javafx-init", "Prism/玻璃管道已起来");

    Stage stage = newStage();
    log("stage-created", "Stage 建好但没显示（常驻托盘的稳态）");

    if (staySec > 0) {
      CountDownLatch up = new CountDownLatch(1);
      Platform.runLater(() -> {
        stage.show();
        up.countDown();
      });
      up.await();
      log("stay-visible-start", "窗显示 " + staySec + " 秒，供截图与窗口枚举取证");
      Thread.sleep(3000);
      log("isShowing", String.valueOf(stage.isShowing()) + " bounds="
          + stage.getX() + "," + stage.getY() + " " + stage.getWidth() + "x" + stage.getHeight());
      capture(capturePath);
      Thread.sleep(Math.max(0, (staySec - 3) * 1000L));
      CountDownLatch down = new CountDownLatch(1);
      Platform.runLater(() -> {
        stage.hide();
        down.countDown();
      });
      down.await();
      log("stay-visible-end", "已隐藏");
    }

    if (showWindow) {
      for (int i = 1; i <= cycles; i++) {
        cycle(stage, intervalMs, destroyScene);
        if (i % 5 == 0) {
          log("after-cycle-" + i, "开合 " + i + " 次");
        }
      }
    }

    long samples = Math.max(1, idleSec / sampleSec);
    for (int i = 1; i <= samples; i++) {
      Thread.sleep(sampleSec * 1000);
      log("idle-" + (i * sampleSec) + "s", "托盘常驻，无窗口");
    }

    Platform.runLater(Platform::exit);
    Thread.sleep(500);
    System.exit(0);
  }

  private static void cycle(Stage stage, long intervalMs, boolean destroyScene) throws Exception {
    CountDownLatch shown = new CountDownLatch(1);
    Platform.runLater(() -> {
      if (stage.getScene() == null) {
        stage.setScene(newScene());
      }
      stage.show();
      shown.countDown();
    });
    shown.await();
    Thread.sleep(intervalMs);

    CountDownLatch hidden = new CountDownLatch(1);
    Platform.runLater(() -> {
      stage.hide();
      if (destroyScene) {
        stage.setScene(null);
      }
      hidden.countDown();
    });
    hidden.await();
    Thread.sleep(intervalMs);
  }

  private static Stage newStage() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    Stage[] holder = new Stage[1];
    Platform.runLater(() -> {
      Stage stage = new Stage();
      stage.initStyle(StageStyle.TRANSPARENT);
      stage.setAlwaysOnTop(true);
      stage.setX(120);
      stage.setY(120);
      stage.setScene(newScene());
      holder[0] = stage;
      ready.countDown();
    });
    ready.await();
    return holder[0];
  }

  private static Scene newScene() {
    StackPane root = new StackPane(new Rectangle(320, 120, Color.web("2b2b2b")));
    return new Scene(root, 320, 120, true);
  }

  private static void startFx() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    Platform.startup(started::countDown);
    Platform.setImplicitExit(false);
    started.await();
  }

  private static void installTray() {
    if (!SystemTray.isSupported()) {
      System.out.println("PHASE=tray-unsupported note=SystemTray 不可用");
      return;
    }
    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        img.setRGB(x, y, (x + y) % 4 == 0 ? 0xFF4FC3F7 : 0xFF2B2B2B);
      }
    }
    TrayIcon icon = new TrayIcon(img, "desk-timer spike");
    icon.setImageAutoSize(true);
    PopupMenu menu = new PopupMenu();
    menu.add(new MenuItem("退出"));
    icon.setPopupMenu(menu);
    try {
      SystemTray.getSystemTray().add(icon);
    } catch (Exception e) {
      System.out.println("PHASE=tray-add-failed note=" + e.getMessage());
    }
  }

  private static void capture(String path) {
    try {
      java.awt.Rectangle bounds = new java.awt.Rectangle(
          java.awt.Toolkit.getDefaultToolkit().getScreenSize());
      java.awt.image.BufferedImage img = new java.awt.Robot().createScreenCapture(bounds);
      java.io.File f = new java.io.File(path);
      if (f.getParentFile() != null) {
        f.getParentFile().mkdirs();
      }
      javax.imageio.ImageIO.write(img, "png", f);
      System.out.printf("CAPTURE_PATH=%s bytes=%d screen=%dx%d%n",
          f.getAbsolutePath(), f.length(), img.getWidth(), img.getHeight());
    } catch (Exception e) {
      System.out.println("CAPTURE_FAILED " + e);
    }
  }

  private static void log(String phase, String note) {
    // JDK 17.0.9 的 com.sun.management.OperatingSystemMXBean 没有 getWorkingSetSize()，
    // RSS 由 run.ps1 按 PID 采样，这里只打相位标记。
    System.out.printf("PHASE=%s ts=%d note=%s%n", phase, System.currentTimeMillis(), note);
  }

  private static int intArg(String[] args, String key, int fallback) {
    String v = find(args, key);
    return v == null ? fallback : Integer.parseInt(v);
  }

  private static long longArg(String[] args, String key, long fallback) {
    String v = find(args, key);
    return v == null ? fallback : Long.parseLong(v);
  }

  private static boolean boolArg(String[] args, String key, boolean fallback) {
    String v = find(args, key);
    return v == null ? fallback : Boolean.parseBoolean(v);
  }

  private static String find(String[] args, String key) {
    for (String a : args) {
      if (a.startsWith(key + "=")) {
        return a.substring(key.length() + 1);
      }
    }
    return null;
  }
}
