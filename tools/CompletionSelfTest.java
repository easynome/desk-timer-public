import desktimer.core.time.*;
import desktimer.db.*;
import desktimer.platform.*;
import desktimer.ui.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.scene.image.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Completion lifecycle in an isolated data home; foreground policy supplied, no global hooks. */
public final class CompletionSelfTest {
  static UiController ui;
  static Stage other;
  static Path output;
  static int checks;
  public static void main(String[] args) throws Exception {
    output = Files.createTempDirectory(Path.of("target"), "completion-selftest-");
    var started=new CountDownLatch(1); Platform.startup(started::countDown); started.await(); Platform.setImplicitExit(false);
    var ds=new DataSource(output.resolve("timer.db"));
    try {
      Migrations.migrate(ds.conn()); var clock=new Clock(); var entries=new EntryRepository(ds,clock);
      var settings=new SettingRepository(ds,clock); settings.put("guard.enabled","false");
      settings.put("sound.choice","off"); settings.put("rest.fullscreen","false"); settings.put("ui.panel.pinned","false");
      var fullscreen=new AtomicBoolean(false);
      fx(() -> ui=new UiController(entries,clock,()->{},settings,new NoteRepository(ds,clock),new TodoRepository(ds,clock),
          new FullScreenDetector(),UiTheme.DARK,()->0L,()->new FullScreenDetector.State(fullscreen.get(),"fixture","controlled")));

      String normal=start("p 2s 完成后下一步 @番茄"); Thread.sleep(2700);
      fx(() -> {
        check(entries.find(normal).orElseThrow().status()==EntryStatus.FINISHED,"pomodoro finishes");
        check(ui.panel().showing() && ui.panel().awaitingChoice(),"hidden panel becomes a stable completion prompt");
        check(!ui.panel().pinned() && window().isAlwaysOnTop(),"completion pin is separate from user preference");
        check("false".equals(settings.get("ui.panel.pinned","")),"does not overwrite pin preference");
        check(restCount(ds)==0,"waiting is not recorded as rest"); snapshot((Parent)ui.panel().root(),"next-step.png");
      });
      // Real loss of keyboard focus to another owned window must not hide the completion prompt.
      focus(ui.panel().root());
      fx(() -> { other=new Stage(); other.setAlwaysOnTop(true); other.setX(650); other.setY(200);
        other.setScene(new Scene(new Label("焦点切换夹具"),240,120)); other.show(); });
      focus(other.getScene().getRoot()); Thread.sleep(250);
      fx(() -> { check(!ui.panel().focused() && ui.panel().showing(),"completion survives actual focus loss"); other.hide();
        ui.panel().press(javafx.scene.input.KeyCode.H); check(ui.pendingRest().isEmpty()&&!ui.panel().awaitingChoice(),"explicit close clears completion"); });

      fullscreen.set(true); String full=start("p 2s 全屏完成 @番茄"); Thread.sleep(2700);
      fx(() -> check(!ui.panel().showing()&&!ui.pendingRest().isEmpty(),"fullscreen holds pending choice without raising"));
      check(entries.reviewQueue().stream().anyMatch(item->item.id().equals(full)),"suppressed reminder enters review queue");
      fullscreen.set(false); Thread.sleep(5500);
      fx(() -> { check(ui.panel().showing()&&ui.panel().awaitingChoice(),"existing five-second poll delivers after fullscreen ends even with guard disabled");
        ui.panel().press(javafx.scene.input.KeyCode.H); });

      settings.put("dnd.until",clock.wallNow().plusHours(1).format(Clock.WIRE));
      start("p 2s 勿扰完成 @番茄"); Thread.sleep(2700);
      fx(() -> check(!ui.panel().showing()&&!ui.pendingRest().isEmpty(),"dnd holds next step"));
      settings.put("dnd.until","");
      fx(() -> { invoke("guardPulse"); check(ui.panel().showing(),"dnd exit delivers choice"); ui.panel().press(javafx.scene.input.KeyCode.H);
        invoke("guardPulse"); check(!ui.panel().showing(),"explicit dismissal does not reappear"); });

      fx(() -> { ui.capture().type("并行任务 @其他"); ui.capture().submit(); });
      start("p 2s 并行完成 @番茄"); Thread.sleep(2700);
      fx(() -> { check(!ui.pendingRest().isEmpty(),"parallel pomodoro keeps a choice");
        ui.panel().press(javafx.scene.input.KeyCode.X);
        check(!ui.pendingRest().isEmpty()&&ui.panel().awaitingChoice(),"closing unrelated session does not erase next step");
        fire("延后 5 分"); check(!ui.panel().awaitingChoice()&&!ui.pendingRest().isEmpty(),"deferral releases foreground pin");
        check(restCount(ds)==0,"deferral creates no fictional rest");
        field("deferredUntil").setLong(ui,clock.elapsedMs()-1); fullscreen.set(true); ui.panel().hide(); invoke("guardPulse");
        check(!ui.panel().showing(),"deferral expiry respects fullscreen"); fullscreen.set(false); invoke("guardPulse");
        check(ui.panel().showing()&&ui.panel().awaitingChoice(),"deferred choice recovers after fullscreen");
        fire("开始休息"); check(ui.pendingRest().isEmpty()&&!ui.panel().awaitingChoice()&&restCount(ds)==1,"rest starts only on explicit action");
        ui.panel().press(javafx.scene.input.KeyCode.X); });

      start("p 2s 下一番茄 @番茄"); Thread.sleep(2700);
      fx(() -> { fire("跳过 → 下一番茄"); check(ui.hasActiveSession()&&!ui.panel().awaitingChoice(),"next pomodoro starts and clears prompt");
        ui.panel().press(javafx.scene.input.KeyCode.X); });
      Files.writeString(Path.of("target/completion-selftest-result.txt"),output.toAbsolutePath().toString());
      System.out.println("COMPLETION_SELFTEST_OK checks="+checks+" normal=true focus_loss=true fullscreen=true dnd=true deferred=true parallel=true actions=true output="+output);
    } finally { fx(() -> { if(ui!=null)ui.shutdown(); if(other!=null)other.hide(); }); ds.close(); Platform.exit(); }
  }
  static String start(String expression) throws Exception {
    var id=new java.util.concurrent.atomic.AtomicReference<String>();
    fx(() -> { ui.capture().type(expression); ui.capture().submit(); id.set(ui.activeEntryId()); ui.panel().hide(); }); return id.get();
  }
  static Stage window() { return (Stage)ui.panel().root().getScene().getWindow(); }
  static int restCount(DataSource ds) throws Exception { try(var s=ds.conn().createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM time_entry WHERE kind LIKE 'rest_%'")){rs.next();return rs.getInt(1);} }
  static void fire(String text) { ((Button)((Parent)ui.panel().root()).lookupAll(".button").stream().filter(n->n instanceof Button b&&text.equals(b.getText())).findFirst().orElseThrow()).fire(); }
  static java.lang.reflect.Field field(String name) throws Exception {var f=UiController.class.getDeclaredField(name);f.setAccessible(true);return f;}
  static void invoke(String name) throws Exception {var m=UiController.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(ui);}
  static void focus(Node root) throws Exception {
    var point=new java.util.concurrent.atomic.AtomicReference<javafx.geometry.Point2D>(); fx(()->point.set(root.localToScreen(24,24)));
    var robot=new java.awt.Robot(); var old=java.awt.MouseInfo.getPointerInfo().getLocation();
    robot.mouseMove((int)point.get().getX(),(int)point.get().getY());robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.mouseMove(old.x,old.y);Thread.sleep(250);
    fx(()->check(root.getScene().getWindow().isFocused(),"owned fixture has focus"));
  }
  interface Work {void run() throws Exception;}
  static void fx(Work work) throws Exception {var task=new FutureTask<Void>(()->{work.run();return null;});Platform.runLater(task);task.get(20,TimeUnit.SECONDS);}
  static void check(boolean pass,String text){checks++;if(!pass)throw new AssertionError(text);}
  static void snapshot(Parent root,String name) throws Exception {
    root.applyCss();root.layout();WritableImage img=root.snapshot(null,null);int w=(int)img.getWidth(),h=(int)img.getHeight();int[] pixels=new int[w*h];
    img.getPixelReader().getPixels(0,0,w,h,PixelFormat.getIntArgbInstance(),pixels,0,w);var png=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);png.setRGB(0,0,w,h,pixels,0,w);ImageIO.write(png,"png",output.resolve(name).toFile());
  }
}
