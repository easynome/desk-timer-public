package desktimer.platform;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例锁：开机自启与手动双击同时发生时，后启动的那个直接退出。
 * 进程死了锁自动释放，所以不需要清理陈旧锁文件。
 */
public final class SingleInstance implements AutoCloseable {

  private final FileChannel channel;
  private final FileLock lock;

  private SingleInstance(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * 拿不到锁返回 null，**不抛**。
   * "已经有一个在跑"是正常情况不是故障：抛出去会让 jpackage 的启动器弹
   * "Failed to launch JVM"（用户反馈正是被这句误导去查安装的）。
   */
  public static SingleInstance acquireOrNone(Path lockFile) throws IOException {
    FileChannel ch = FileChannel.open(lockFile,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired = ch.tryLock();
    if (acquired == null) {
      ch.close();
      return null;
    }
    return new SingleInstance(ch, acquired);
  }

  @Override
  public void close() throws IOException {
    if (lock.isValid()) {
      lock.release();
    }
    channel.close();
  }
}
