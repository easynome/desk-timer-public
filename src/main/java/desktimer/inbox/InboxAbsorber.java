package desktimer.inbox;

import desktimer.core.parse.CaptureParser;
import desktimer.core.time.Clock;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * `inbox/` 吸收器：外部工具（命令行、打卡脚本）往 `inbox/inbox.jsonl` 追加计时行，
 * 应用把它们读进来变成条目。方向与 `exports/` 相反，两个目录不混用。
 *
 * 四条判据各挡一种坏情况：
 *  1. **改名先于插入**。先把 `inbox.jsonl` 原子改名成 `absorbing-<时刻>.jsonl` 再读，
 *     读到一半崩了文件还在（名字就说明它没走完），下次开机接着处理，不会静默吞数据。
 *  2. **台账去重**（`absorbed-ledger.log`，按整行哈希）。重放、手抖多写一次，
 *     都不该变成"多一段计时" —— 时长账最怕这个。
 *  3. **认不了的行落到 `rejected-*.jsonl`，不猜**。宁可让外部工具看到"这条没进去 + 为什么"，
 *     也不要凭空造出一段没发生过的时间。
 *  4. **轮转只删自己产出的文件名**（`absorbed-*.jsonl`，留 14 份）：
 *     `inbox.jsonl`、`absorbing-*`、`rejected-*`、台账一律不碰。
 *
 * 落库走 {@link EntryRepository#finishBackfill}：外部给的时长与界面里的"补录"是同一种东西
 * （人给的、不是量出来的），所以 origin 仍是 `manual_backfill`，来源凭证留在台账里。
 * 同项目相交一律拒 —— 与界面补录同一条判据，不替外部写的人认 overlap_waived。
 */
public final class InboxAbsorber {

  private static final int KEEP = 14;
  private static final String LEDGER = "absorbed-ledger.log";

  private final EntryRepository entries;
  private final Path dir;
  private final Clock clock;
  private final CaptureParser parser = new CaptureParser();

  public record Result(int absorbed, int rejected, int skipped) {
    public boolean didWork() {
      return absorbed + rejected + skipped > 0;
    }
  }

  public InboxAbsorber(EntryRepository entries, Path inboxDir, Clock clock) {
    this.entries = entries;
    this.dir = inboxDir;
    this.clock = clock;
  }

  /** 一次吸收：处理所有遗留的 `absorbing-*` 与当前的 `inbox.jsonl`。没活干时静默返回。 */
  public Result absorbOnce() throws IOException {
    if (!Files.isDirectory(dir)) {
      return new Result(0, 0, 0);
    }
    Files.createDirectories(dir);
    List<Path> pending = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> p.getFileName().toString().startsWith("absorbing-")
              && p.getFileName().toString().endsWith(".jsonl"))
          .sorted().forEach(pending::add);
    }
    Path inbox = dir.resolve("inbox.jsonl");
    if (Files.isRegularFile(inbox) && Files.size(inbox) > 0) {
      Path moved = dir.resolve("absorbing-" + stamp() + ".jsonl");
      if (!atomicMove(inbox, moved)) {
        return new Result(0, 0, 0);
      }
      pending.add(moved);
    }
    int absorbed = 0;
    int rejected = 0;
    int skipped = 0;
    for (Path file : pending) {
      int[] counts = processFile(file);
      absorbed += counts[0];
      rejected += counts[1];
      skipped += counts[2];
      atomicMove(file, dir.resolve(fileName(file).replaceFirst("^absorbing-", "absorbed-")));
    }
    rotate();
    if (absorbed + rejected + skipped > 0) {
      System.out.println("INBOX_ABSORBED absorbed=" + absorbed + " rejected=" + rejected
          + " skipped=" + skipped);
    }
    return new Result(absorbed, rejected, skipped);
  }

  /** 返回 {absorbed, rejected, skipped}。一行失败不影响后面的行。 */
  private int[] processFile(Path file) throws IOException {
    Set<String> seen = ledgerKeys();
    int absorbed = 0;
    int rejected = 0;
    int skipped = 0;
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      String key = sha12(line);
      if (seen.contains(key)) {
        skipped++;
        continue;
      }
      String reason = absorbLine(line);
      if (reason == null) {
        seen.add(key);
        appendLedger(key, line);
        absorbed++;
      } else {
        reject(line, reason);
        rejected++;
      }
    }
    return new int[] {absorbed, rejected, skipped};
  }

  /** null = 收下了；否则是要写进 rejected 文件的原因。 */
  private String absorbLine(String line) {
    if (parser.isPomodoro(line)) {
      return "`p` 前缀是番茄，而轮次（今天第几个）由应用的状态机数，外部给不了 ⇒ 不接，"
          + "免得消费方按 kind 数番茄时对不上。补一段计时就写「25m 任务名」；"
          + "要真番茄请在界面里用唤起键起一段";
    }
    if (line.contains(":")) {
      return "只认时长写法（25m / 1h30m / 90分钟），不认 14:20 这种时刻："
          + "时刻是从今往后算的，拿来往回补会算成一段未来";
    }
    CaptureParser.Plan plan = parser.parse(line);
    if (!plan.isCountDown()) {
      return "没读出时长：inbox 的一行必须是「25m 任务名」这种带时长的计时行";
    }
    try {
      String projectId = entries.ensureProject(plan.project());
      String ended = clock.wall();
      String started = OffsetDateTime.parse(ended).minusNanos(plan.targetMs() * 1_000_000L)
          .format(Clock.WIRE);
      Entry e = entries.finishBackfill(plan.task(), projectId, started, ended);
      entries.attachTags(e.id(), plan.tags());
      return null;
    } catch (IllegalStateException | IllegalArgumentException e) {
      return e.getMessage();
    } catch (Exception e) {
      return "落库失败：" + e.getMessage();
    }
  }

  private void reject(String line, String reason) throws IOException {
    Path file = dir.resolve("rejected-" + stamp() + ".jsonl");
    String row = "{\"line\":\"" + escape(line) + "\",\"reason\":\"" + escape(reason)
        + "\",\"at\":\"" + clock.wall() + "\"}\n";
    Files.writeString(file, row, StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    System.out.println("INBOX_REJECTED " + reason + " | " + line);
  }

  private Set<String> ledgerKeys() throws IOException {
    Set<String> out = new HashSet<>();
    Path ledger = dir.resolve(LEDGER);
    if (!Files.isRegularFile(ledger)) {
      return out;
    }
    for (String row : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
      if (row.startsWith("sha=")) {
        out.add(row.substring(4, Math.min(row.length(), 4 + 12)));
      }
    }
    return out;
  }

  private void appendLedger(String key, String line) throws IOException {
    Path ledger = dir.resolve(LEDGER);
    String row = "sha=" + key + " at=" + clock.wall() + " line=" + line + "\n";
    Files.writeString(ledger, row, StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
  }

  /** 只删自己产出的那批：前缀 + .jsonl 双条件，台账与 rejected 都不会被"清理"掉。 */
  private void rotate() throws IOException {
    List<Path> old = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> {
        String n = fileName(p);
        return n.startsWith("absorbed-") && n.endsWith(".jsonl");
      }).sorted(Comparator.comparing(InboxAbsorber::fileName).reversed()).forEach(old::add);
    }
    for (int i = KEEP; i < old.size(); i++) {
      Files.deleteIfExists(old.get(i));
    }
  }

  private boolean atomicMove(Path from, Path to) {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
      return true;
    } catch (AtomicMoveNotSupportedException e) {
      try {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        return true;
      } catch (IOException e2) {
        System.out.println("INBOX_MOVE_FAILED " + e2.getMessage());
        return false;
      }
    } catch (IOException e) {
      System.out.println("INBOX_MOVE_FAILED " + e.getMessage());
      return false;
    }
  }

  private String stamp() {
    // 文件名里不能出现 ':'；'+' 保留（测试与人工核对时要看得到原始偏移量）
    return clock.wall().replace(':', '-');
  }

  private static String fileName(Path p) {
    return p.getFileName().toString();
  }

  private static String sha12(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        sb.append(String.format("%02x", d[i]));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
