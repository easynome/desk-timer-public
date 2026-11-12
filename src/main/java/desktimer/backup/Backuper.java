package desktimer.backup;

import desktimer.core.time.Clock;
import desktimer.db.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键备份：把主库做成一份**可独立打开**的 zip，落在 {@code backup/}。
 *
 * 三个要点：
 *  - 用 {@code VACUUM INTO} 而不是复制文件 —— WAL 模式下直接拷 timer.db 会拷出一份
 *    缺 -wal 的、可能打不开的库；VACUUM INTO 由 SQLite 自己产出一致快照，不需要停写。
 *  - 备份完**立刻把快照当库打开数一遍行**：只证明"文件生成了"没有意义，
 *    能读出行数才证明可恢复。
 *  - 轮转删除只删自己产出的 {@code timer-backup-*.zip}，别的一律不碰。
 */
public final class Backuper {

  public static final int KEEP = 14;
  public static final String PREFIX = "timer-backup-";

  private final Connection c;
  private final Object lock;
  private final Path backupDir;
  private final Clock clock;
  private final String appVersion;
  private final int userVersion;

  public Backuper(DataSource ds, Path backupDir, Clock clock, String appVersion, int userVersion) {
    this.c = ds.conn();
    this.lock = ds.lock();
    this.backupDir = backupDir;
    this.clock = clock;
    this.appVersion = appVersion;
    this.userVersion = userVersion;
  }

  public record Result(Path zip, String sha256_12, int rows, int kept, int deleted) {
  }

  public Result backup(String reason) throws SQLException, IOException {
    synchronized (lock) {
      Files.createDirectories(backupDir);
      String at = clock.wall();
      Path snapshot = backupDir.resolve("snapshot-in-progress.db");
      Files.deleteIfExists(snapshot);

      try (Statement s = c.createStatement()) {
        s.execute("VACUUM INTO " + literal(snapshot));
      }
      byte[] digest = sha256(snapshot);
      int rows = countRows(snapshot, "time_entry");
      int notes = countRows(snapshot, "note");
      int todos = countRows(snapshot, "todo");

      String manifest = "{"
          + "\"kind\":\"desk-timer-backup\","
          + "\"app_version\":\"" + appVersion + "\","
          + "\"user_version\":" + userVersion + ","
          + "\"created_at\":\"" + at + "\","
          + "\"reason\":\"" + escape(reason) + "\","
          + "\"db_sha256\":\"" + hex(digest) + "\","
          + "\"rows\":{\"time_entry\":" + rows + ",\"note\":" + notes + ",\"todo\":" + todos + "}}";

      Path zip = backupDir.resolve(PREFIX + at.replace(":", "").replace("+", "_") + ".zip");
      Path tmp = backupDir.resolve(zip.getFileName() + ".tmp");
      try (OutputStream out = Files.newOutputStream(tmp);
           ZipOutputStream z = new ZipOutputStream(out)) {
        put(z, "timer.db", snapshot);
        putBytes(z, "backup-manifest.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      Files.deleteIfExists(snapshot);
      moveAtomic(tmp, zip);

      int[] rotation = rotate();
      System.out.println("BACKUP zip=" + zip.getFileName() + " sha256_12=" + shortHex(digest)
          + " rows=" + rows + " note=" + notes + " todo=" + todos
          + " kept=" + rotation[0] + " deleted=" + rotation[1] + " reason=" + reason);
      return new Result(zip, shortHex(digest), rows, rotation[0], rotation[1]);
    }
  }

  /** VACUUM INTO 不接受绑定参数，只能拼字面量 —— 所以路径里出现单引号就直接拒绝。 */
  private static String literal(Path p) {
    String raw = p.toAbsolutePath().toString();
    if (raw.indexOf('\'') >= 0) {
      throw new IllegalArgumentException("备份路径含单引号，拒绝拼接 VACUUM INTO：" + raw);
    }
    return "'" + raw.replace("\\", "/") + "'";
  }

  /** 打开快照自己数行数：这一步才是"可恢复"的证据，文件存在不是。 */
  private static int countRows(Path snapshot, String table) throws SQLException {
    try (Connection ro = DriverManager.getConnection(
        "jdbc:sqlite:" + snapshot.toAbsolutePath().toString().replace("\\", "/"));
        Statement s = ro.createStatement()) {
      s.execute("PRAGMA busy_timeout=3000");
      try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  private int[] rotate() throws IOException {
    List<Path> backups = new ArrayList<>();
    try (var stream = Files.list(backupDir)) {
      stream.filter(p -> p.getFileName().toString().startsWith(PREFIX)
          && p.getFileName().toString().endsWith(".zip")).forEach(backups::add);
    }
    backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
    int deleted = 0;
    for (int i = KEEP; i < backups.size(); i++) {
      Path old = backups.get(i);
      Files.delete(old);
      System.out.println("BACKUP_ROTATED_OUT " + old.getFileName());
      deleted++;
    }
    return new int[] {Math.min(KEEP, backups.size()), deleted};
  }

  private static void put(ZipOutputStream z, String name, Path file) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    Files.copy(file, z);
    z.closeEntry();
  }

  private static void putBytes(ZipOutputStream z, String name, byte[] body) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    z.write(body);
    z.closeEntry();
  }

  private static void moveAtomic(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static byte[] sha256(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
          md.update(buf, 0, n);
        }
      }
      return md.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String shortHex(byte[] digest) {
    return hex(digest).substring(0, 12);
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
