package desktimer.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.logging.*;

/** 有界运行诊断：只保存操作类型与异常类，不保存任务、笔记、路径或异常正文。 */
public final class RuntimeDiagnostics implements AutoCloseable {
  public enum Operation { STARTUP, HEARTBEAT, EXPORT, INBOX, BACKUP, AUDIO, UI, SHUTDOWN, UNCAUGHT }
  private static volatile RuntimeDiagnostics active;
  private final Logger logger = Logger.getAnonymousLogger();
  private final Path directory;
  private final EnumMap<Operation, String> failures = new EnumMap<>(Operation.class);
  private FileHandler file;
  private volatile boolean unavailable;
  private final Thread.UncaughtExceptionHandler previous;
  private final Thread.UncaughtExceptionHandler handler;

  public static RuntimeDiagnostics open(Path directory) { return new RuntimeDiagnostics(directory, 512 * 1024, 3); }

  RuntimeDiagnostics(Path directory, int limit, int count) {
    this.directory = directory;
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    try {
      Files.createDirectories(directory);
      file = new FileHandler(directory.resolve("runtime-%g.log").toString(), limit, count, true);
      file.setEncoding(StandardCharsets.UTF_8.name());
      file.setFormatter(new Formatter() {
        @Override public String format(LogRecord r) {
          return Instant.ofEpochMilli(r.getMillis()) + " " + r.getLevel() + " " + r.getMessage() + "\n";
        }
      });
      file.setErrorManager(new ErrorManager() {
        @Override public void error(String msg, Exception ex, int code) { unavailable = true; }
      });
      logger.addHandler(file);
    } catch (IOException | SecurityException e) { unavailable = true; }
    previous = Thread.getDefaultUncaughtExceptionHandler();
    handler = (thread, error) -> {
      failure(Operation.UNCAUGHT, error);
      if (previous != null) previous.uncaughtException(thread, error);
    };
    active = this;
    Thread.setDefaultUncaughtExceptionHandler(handler);
  }

  public static void ok(Operation operation) {
    RuntimeDiagnostics log = active;
    if (log != null) log.record(operation, null);
  }

  public static void failure(Operation operation, Throwable error) {
    RuntimeDiagnostics log = active;
    if (log != null) log.record(operation, error);
  }

  private synchronized void record(Operation operation, Throwable error) {
    if (error == null) {
      // 无故障的心跳不写磁盘；恢复时才留证据。
      boolean recovered = failures.remove(operation) != null;
      if (operation != Operation.HEARTBEAT || recovered)
        logger.info(operation + (recovered ? " recovered" : " ok"));
    } else {
      String kind = error.getClass().getSimpleName();
      failures.put(operation, kind);
      logger.warning(operation + " failed type=" + kind);
    }
  }

  public static String status() {
    RuntimeDiagnostics log = active;
    if (log == null) return "运行诊断尚未初始化";
    synchronized (log) {
      String health = log.failures.isEmpty() ? "当前未记录运行故障" : "尚未恢复的故障：" + log.failures;
      return (log.unavailable ? "日志写入不可用；" : "日志轮转：3 份，每份约 512 KB；") + health;
    }
  }

  public static Path directory() { return active == null ? null : active.directory; }

  @Override public synchronized void close() {
    if (Thread.getDefaultUncaughtExceptionHandler() == handler) Thread.setDefaultUncaughtExceptionHandler(previous);
    if (file != null) { logger.removeHandler(file); file.close(); }
    if (active == this) active = null;
  }
}
