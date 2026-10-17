import desktimer.core.time.*;
import desktimer.db.*;
import desktimer.platform.*;
import desktimer.ui.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.scene.image.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Actual CSS regression: independent data, no native hotkeys or registry writes. */
public final class ThemeSelfTest {
  static Path output;
  static SettingsWindow settingsWindow;
  static WorkbenchWindow workbench;
  static TimerPanel timer;
  static int assertions;
  public static void main(String[] args) throws Exception {
    output = Files.createTempDirectory(Path.of("target"), "theme-selftest-");
    CountDownLatch started = new CountDownLatch(1);
    Platform.startup(started::countDown); started.await(); Platform.setImplicitExit(false);
    if (Arrays.asList(args).contains("--viewport-probe")) {
      try {
        fx(() -> {
          var scroll = new ScrollPane(new javafx.scene.layout.VBox(new CheckBox("紧凑计时面板")));
          scroll.setId("preferences-scroll");
          var root = new javafx.scene.layout.VBox(scroll); root.setStyle(UiTheme.DARK.window("plain"));
          var scene = new Scene(root, 360, 240); UiTheme.styleScene(scene);
          root.applyCss(); root.resize(360,240); root.layout();
          verifyViewport(root, "#preferences-scroll", UiTheme.DARK);
        });
        System.out.println("VIEWPORT_PROBE_OK");
      } finally { Platform.exit(); }
      return;
    }
    try (DataSource ds = new DataSource(output.resolve("timer.db"))) {
      Migrations.migrate(ds.conn());
      Clock clock = new Clock();
      var entries = new EntryRepository(ds, clock);
      var settings = new SettingRepository(ds, clock);
      var todos = new TodoRepository(ds, clock);
      todos.add("阅读计时状态机", null, List.of("学习"));
      var e = entries.start("核对提醒与休息记录", EntryKind.COUNT_UP, null, false); entries.finish(e.id(), 25 * 60_000);
      var live = new AtomicReference<>(KeySpec.parse("ctrl+alt+f12"));
      var keys = new AtomicReference<>(KeyMap.from(""));
      var preference = new AtomicReference<>("dark");
      AtomicInteger applies = new AtomicInteger();
      fx(() -> {
        settingsWindow = new SettingsWindow(live::get, spec -> { live.set(spec); return null; },
            keys::get, keys::set, () -> {}, () -> {}, () -> {}, settings,
            new Autostart() { public boolean available() { return true; } public boolean enabled() { return false; } public String set(boolean on) { return null; } },
            UiTheme.DARK, preference::get, preference::set);
        workbench = new WorkbenchWindow(todos, entries, settings, todo -> {}, todo -> {},
            (todo, ms) -> null, applies::incrementAndGet, () -> {}, UiTheme.DARK);
        Runnable noop = () -> {};
        timer = new TimerPanel(noop,noop,noop,noop,noop,noop,noop,noop,noop,settings,UiTheme.DARK);
        timer.setAutoHideOnFocusLost(false);
      });
      // Start with light after creation, then switch dark/light on populated windows.
      for (var palette : List.of(UiTheme.LIGHT, UiTheme.DARK, UiTheme.LIGHT, UiTheme.DARK)) {
        fx(() -> {
          preference.set(palette.name()); settingsWindow.show(); settingsWindow.select(3);
          // Change an already populated window; show/select would otherwise mask stale rows.
          settingsWindow.applyTheme(palette);
          Parent root = (Parent) settingsWindow.root(); root.applyCss(); root.layout();
          verifyViewport(root, "#settings-scroll", palette);
          checkText(root, "settings-" + palette.name());
          snapshot(root, "settings-" + palette.name() + ".png"); settingsWindow.hide();
          workbench.applyTheme(palette); workbench.show();
          TabPane tabs = (TabPane) workbench.root().lookup(".tab-pane");
          for (int i=0; i<3; i++) {
            tabs.getSelectionModel().select(i); workbench.root().applyCss(); workbench.root().layout();
            checkText(workbench.root(), "workbench-" + palette.name() + "-" + i);
            snapshot(workbench.root(), "workbench-" + palette.name() + "-" + i + ".png");
          }
          verifyViewport(workbench.root(), "#preferences-scroll", palette);
          Button save = (Button) workbench.root().lookup("#preferences-save");
          require(save.localToScene(save.getBoundsInLocal()).getMaxY() <= workbench.root().getScene().getHeight(), "save remains visible");
          save.fire();
          ScrollPane scroll = (ScrollPane) workbench.root().lookup("#preferences-scroll"); scroll.setVvalue(1);
          workbench.root().layout(); snapshot(workbench.root(), "preferences-bottom-" + palette.name() + ".png");
          ComboBox<?> sound = (ComboBox<?>) workbench.root().lookup(".combo-box");
          sound.show();
          for (Window window : List.copyOf(Window.getWindows())) if (window instanceof javafx.stage.PopupWindow && window.isShowing()) {
            Parent popup = window.getScene().getRoot(); popup.applyCss(); popup.layout(); checkText(popup, "sound-popup-" + palette.name());
            snapshot(popup, "sound-popup-" + palette.name() + ".png");
          }
          sound.hide(); scroll.setVvalue(0); workbench.hide();
          timer.applyTheme(palette);
          for (boolean compact : List.of(false,true)) {
            timer.setCompact(compact); timer.render("00:25:00", "居中时间验证", 0.25); timer.show();
            Parent timerRoot=(Parent)timer.root(); timerRoot.applyCss(); timerRoot.layout();
            Label clockLabel=(Label)timerRoot.lookup("#timer-readout");
            Node glyph=clockLabel.lookup(".text");
            var glyphBounds=glyph.localToScene(glyph.getBoundsInLocal());
            var panelBounds=timerRoot.localToScene(timerRoot.getBoundsInLocal());
            require(Math.abs((glyphBounds.getMinX()+glyphBounds.getMaxX()-panelBounds.getMinX()-panelBounds.getMaxX())/2) < 1.5,
                "timer glyphs center in window compact="+compact);
            snapshot(timerRoot,"timer-"+palette.name()+"-"+(compact?"compact":"normal")+".png"); timer.hide();
          }
        });
      }
      require(applies.get() == 4, "save remains functional across theme changes");
      require("90".equals(settings.get("guard.limit_min", "")), "threshold saved without changes");
      Files.writeString(Path.of("target/theme-selftest-result.txt"), output.toAbsolutePath().toString());
      System.out.println("THEME_SELFTEST_OK assertions=" + assertions + " contrast=4.5 viewport=true live_switch=true save=true output=" + output);
    } finally {
      fx(() -> { if (settingsWindow != null) settingsWindow.hide(); if (workbench != null) workbench.hide(); if(timer!=null)timer.hide(); });
      Platform.exit();
    }
  }
  static void verifyViewport(Parent root, String selector, UiTheme.Palette palette) {
    ScrollPane scroll = (ScrollPane) root.lookup(selector);
    Region viewport = (Region) scroll.lookup(".viewport");
    Color actual = background(viewport);
    require(hex(actual).equalsIgnoreCase(palette.base()), selector + " actual viewport " + hex(actual) + " expected " + palette.base());
  }
  static void checkText(Node node, String context) {
    if (!node.isVisible() || node.getOpacity() == 0) return;
    if (node instanceof Labeled label && label.getText() != null && !label.getText().isBlank() && label.getTextFill() instanceof Color text) {
      double contrast = UiTheme.contrastRatio(hex(text), hex(background(label)));
      require(contrast >= 4.5, context + " low contrast " + label.getText() + " " + hex(text) + " on " + hex(background(label)) + " ratio=" + contrast);
    }
    if (node instanceof javafx.scene.text.Text text && text.getText() != null && !text.getText().isBlank() && text.getFill() instanceof Color color) {
      double contrast = UiTheme.contrastRatio(hex(color), hex(background(text)));
      require(contrast >= 4.5, context + " rendered glyph low contrast " + text.getText() + " ratio=" + contrast);
    }
    if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) checkText(child, context);
  }
  static Color background(Node node) {
    List<Node> ancestors = new ArrayList<>(); for (Node n=node; n!=null; n=n.getParent()) ancestors.add(n);
    Collections.reverse(ancestors); Color result = Color.WHITE;
    for (Node n : ancestors) if (n instanceof Region r && r.getBackground() != null)
      for (var fill : r.getBackground().getFills()) if (fill.getFill() instanceof Color c) {
        double a=c.getOpacity(); result = new Color(c.getRed()*a+result.getRed()*(1-a), c.getGreen()*a+result.getGreen()*(1-a), c.getBlue()*a+result.getBlue()*(1-a), 1);
      }
    return result;
  }
  static String hex(Color c) { return String.format("#%02x%02x%02x", Math.round(c.getRed()*255), Math.round(c.getGreen()*255), Math.round(c.getBlue()*255)); }
  static void require(boolean pass, String message) { assertions++; if (!pass) throw new AssertionError(message); }
  interface Work { void run() throws Exception; }
  static void fx(Work work) throws Exception { var task=new FutureTask<Void>(() -> {work.run(); return null;}); Platform.runLater(task); task.get(20, TimeUnit.SECONDS); }
  static void snapshot(Parent node, String name) throws Exception {
    node.applyCss(); node.layout(); WritableImage image=node.snapshot(null,null);
    int w=(int)image.getWidth(), h=(int)image.getHeight(); int[] pixels=new int[w*h];
    image.getPixelReader().getPixels(0,0,w,h,PixelFormat.getIntArgbInstance(),pixels,0,w);
    BufferedImage png=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); png.setRGB(0,0,w,h,pixels,0,w);
    ImageIO.write(png,"png",output.resolve(name).toFile());
  }
}
