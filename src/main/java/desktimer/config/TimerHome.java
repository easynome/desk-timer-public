package desktimer.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据根目录：环境变量 TIMER_HOME 覆盖，默认 D:/timer-data。
 * 目录结构是跨工具契约的一部分，消费方只读 exports/。
 */
public final class TimerHome {

  public static final String DEFAULT_ROOT = "D:/timer-data";

  private final Path root;

  private TimerHome(Path root) {
    this.root = root;
  }

  public static TimerHome resolveAndCreate() throws IOException {
    String fromEnv = System.getenv("TIMER_HOME");
    Path p = (fromEnv == null || fromEnv.isBlank())
        ? Paths.get(DEFAULT_ROOT)
        : Paths.get(fromEnv.trim());
    TimerHome home = new TimerHome(p.toAbsolutePath().normalize());
    Files.createDirectories(home.dbDir());
    Files.createDirectories(home.exportsDir());
    Files.createDirectories(home.logsDir());
    Files.createDirectories(home.backupDir());
    Files.createDirectories(home.inboxDir());
    return home;
  }

  public Path root() {
    return root;
  }

  public Path dbDir() {
    return root.resolve("db");
  }

  public Path dbFile() {
    return dbDir().resolve("timer.db");
  }

  public Path exportsDir() {
    return root.resolve("exports");
  }

  public Path logsDir() {
    return root.resolve("logs");
  }

  public Path backupDir() {
    return root.resolve("backup");
  }

  /**
   * 命令行/其他工具往应用里写东西的**唯一入口**，跟 `exports/` 方向相反：
   * `exports/` 是应用写、别人读；`inbox/` 是别人写、应用读。两个目录不混用，
   * 混一次就等于让消费方分不清哪份数据是权威。
   */
  public Path inboxDir() {
    return root.resolve("inbox");
  }

  /** 应用吸收走的那个文件名（外部只往这个名字追加）。 */
  public Path inboxFile() {
    return inboxDir().resolve("inbox.jsonl");
  }

  public Path lockFile() {
    return root.resolve(".lock");
  }
}
