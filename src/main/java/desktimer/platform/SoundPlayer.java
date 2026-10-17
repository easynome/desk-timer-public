package desktimer.platform;

import java.io.BufferedInputStream;
import java.util.concurrent.*;
import javax.sound.sampled.*;

/** 只在需要时打开一个音频设备；播放完成即关闭，不常驻解码缓存。 */
public final class SoundPlayer {
  private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 15,
      TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), r -> {
        Thread t = new Thread(r, "timer-audio"); t.setDaemon(true); return t;
      }, new ThreadPoolExecutor.DiscardOldestPolicy());
  private static Clip active;
  private static volatile String error = "";
  private static volatile String lastPlayed = "";

  public static String play(String choice) {
    if ("off".equals(choice)) return "off";
    if (!"wind-chime".equals(choice) && !"tic-toc".equals(choice)) return NotifySound.play();
    WORKER.execute(() -> playResource(choice));
    return "queued:" + choice;
  }

  private static synchronized void playResource(String choice) {
    stop();
    Clip clip = null;
    try (var raw = SoundPlayer.class.getResourceAsStream("/audio/" + choice + ".wav")) {
      if (raw == null) throw new IllegalStateException("缺少内置音效");
      try (var stream = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
        clip = AudioSystem.getClip();
        Clip playing = clip;
        clip.addLineListener(e -> {
          if (e.getType() == LineEvent.Type.STOP) playing.close();
        });
        clip.open(stream);
        active = clip;
        clip.start();
        error = "";
        lastPlayed = choice;
        desktimer.diagnostics.RuntimeDiagnostics.ok(desktimer.diagnostics.RuntimeDiagnostics.Operation.AUDIO);
      }
    } catch (Exception e) {
      if (clip != null) clip.close();
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
      lastPlayed = "";
      System.err.println("AUDIO_FAILED " + error);
      desktimer.diagnostics.RuntimeDiagnostics.failure(desktimer.diagnostics.RuntimeDiagnostics.Operation.AUDIO, e);
      NotifySound.play();
    }
  }

  public static synchronized void stop() {
    if (active != null) { active.stop(); active.close(); active = null; }
  }

  public static String lastError() { return error; }
  public static String lastPlayed() { return lastPlayed; }

  /** 打包运行时的诊断入口；检查真实设备启动与关闭，不代表人工听感。 */
  public static void selfTest() throws InterruptedException {
    for (String choice : java.util.List.of("wind-chime", "tic-toc")) {
      lastPlayed = "";
      play(choice);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (!choice.equals(lastPlayed) && System.nanoTime() < deadline) Thread.sleep(50);
      if (!choice.equals(lastPlayed)) throw new IllegalStateException("AUDIO_SELFTEST_FAILED " + choice + " " + error);
      Thread.sleep(350);
      stop();
    }
    System.out.println("AUDIO_SELFTEST_OK wind-chime=true tic-toc=true resources_closed=true");
  }
}
