import desktimer.core.time.Clock;
import desktimer.db.DataSource;
import desktimer.db.Migrations;
import desktimer.db.SettingRepository;
import desktimer.ui.CaptureWindow;
import desktimer.ui.TimerPanel;
import desktimer.ui.UiTheme;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/** Renders only application nodes into PNGs, using a scratch database and no global hotkeys. */
public final class UiPreview {
  public static void main(String[] args) throws Exception {
    Path dir = Path.of("target", "ui-preview");
    Files.createDirectories(dir);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    try (DataSource ds = new DataSource(dir.resolve("preview.db"))) {
      Migrations.migrate(ds.conn());
      SettingRepository settings = new SettingRepository(ds, new Clock());
      Platform.startup(() -> {
        try {
          Runnable noop = () -> {};
          TimerPanel panel = new TimerPanel(noop, noop, noop, noop, noop, noop, noop,
              noop, noop, settings, UiTheme.DARK);
          panel.setAutoHideOnFocusLost(false);
          panel.renderRoster(List.of(new TimerPanel.Line("● 00:24:38  完成项目设计 @工作", true),
              new TimerPanel.Line("  00:12:05  阅读源码 @学习", false)));
          panel.render("00:24:38", "目标 00:25:00 · 共 2 段在跑 · 待确认 1 条（停下来按快捷键看）", 0.25);
          panel.show();
          Node row = ((Parent) panel.root().lookup(".timer-roster")).getChildrenUnmodifiable().get(0);
          for (UiTheme.Palette theme : List.of(UiTheme.DARK, UiTheme.LIGHT)) {
            panel.applyTheme(theme);
            snapshot(panel.root(), dir.resolve("timer-" + theme.name() + ".png"));
          }
          panel.renderRoster(List.of(new TimerPanel.Line("● 00:24:37  完成项目设计 @工作", true)));
          Node after = ((Parent) panel.root().lookup(".timer-roster")).getChildrenUnmodifiable().get(0);
          if (row != after) throw new AssertionError("Roster nodes were replaced");
          panel.hide();
          CaptureWindow capture = new CaptureWindow(p -> {}, n -> {}, t -> {}, noop,
              settings, UiTheme.DARK);
          capture.setAutoHideOnFocusLost(false);
          capture.type("25m 完成项目设计 #开发 @工作");
          capture.show();
          for (UiTheme.Palette theme : List.of(UiTheme.DARK, UiTheme.LIGHT)) {
            capture.applyTheme(theme);
            snapshot(capture.root(), dir.resolve("capture-" + theme.name() + ".png"));
          }
          capture.hide();
          System.out.println("UI_PREVIEW_OK row_reused=true themes=dark,light");
        } catch (Throwable e) {
          failure.set(e);
        } finally {
          done.countDown();
        }
      });
      done.await();
      Platform.exit();
      if (failure.get() != null) throw new AssertionError(failure.get());
    }
  }

  private static void snapshot(Node node, Path path) throws Exception {
    node.applyCss();
    ((Parent) node).layout();
    WritableImage image = node.snapshot(null, null);
    int w = (int) image.getWidth(), h = (int) image.getHeight();
    BufferedImage png = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int[] pixels = new int[w * h];
    image.getPixelReader().getPixels(0, 0, w, h,
        javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
    png.setRGB(0, 0, w, h, pixels, 0, w);
    ImageIO.write(png, "png", path.toFile());
  }
}
