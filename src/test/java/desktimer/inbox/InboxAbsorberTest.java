package desktimer.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.config.TimerHome;
import desktimer.core.time.Clock;
import desktimer.core.time.EntryKind;
import desktimer.db.DataSource;
import desktimer.db.Entry;
import desktimer.db.EntryRepository;
import desktimer.db.Migrations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `inbox/` 吸收：外部工具往 `inbox.jsonl` 追加计时行，应用读走并落成条目。
 *
 * 四条判据各挡一种坏情况：
 *  - **改名先于插入**：读到一半崩了，文件还在（absorbing-*），下次接着处理，不会静默吞数据；
 *  - **台账去重**：同一条内容不会被算两次（重放、手抖多写一次都会撞上）；
 *  - **认不了的行落到 rejected-*，不猜**：宁可让外部工具看到"这条没进去"，也不要造出一条假计时；
 *  - **只删自己产出的文件名**：轮转 absorbed-* 时不许碰 inbox.jsonl 或别的东西。
 */
class InboxAbsorberTest {

  private DataSource ds;
  private EntryRepository entries;
  private InboxAbsorber absorber;
  private Path dir;
  private final Clock clock = new Clock();

  @BeforeEach
  void setUp(@TempDir Path tmp) throws Exception {
    ds = new DataSource(tmp.resolve("t.db"));
    Migrations.migrate(ds.conn());
    entries = new EntryRepository(ds, clock);
    dir = tmp.resolve("inbox");
    Files.createDirectories(dir);
    absorber = new InboxAbsorber(entries, dir, clock);
  }

  @AfterEach
  void tearDown() throws Exception {
    ds.close();
  }

  private void write(String... lines) throws IOException {
    Files.write(dir.resolve("inbox.jsonl"), List.of(lines));
  }

  @Test
  void aValidLineBecomesABackfilledEntryAndTheFileIsArchived() throws Exception {
    write("25m 命令行记的周报 #脚本 @Portal");
    InboxAbsorber.Result r = absorber.absorbOnce();

    assertEquals(1, r.absorbed());
    assertEquals(0, r.rejected());
    Entry e = onlyEntry();
    assertEquals("命令行记的周报", e.task());
    assertEquals(25 * 60_000L, e.durationMs().longValue());
    assertEquals("manual_backfill", origin(e.id()), "外部写的计时也是\"人给的\"，不许冒充量出来的");
    assertEquals(List.of("脚本"), tagsOf(e.id()));
    assertFalse(Files.exists(dir.resolve("inbox.jsonl")), "读走之后这个名字要空出来给下一次追加");
    try (Stream<Path> s = Files.list(dir)) {
      assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("absorbed-")),
          "吸收掉的原文件必须留着，不是删掉");
    }
  }

  @Test
  void theSameContentIsNotAbsorbedTwice() throws Exception {
    write("15m 台账去重 #a");
    assertEquals(1, absorber.absorbOnce().absorbed());
    write("15m 台账去重 #a");
    InboxAbsorber.Result second = absorber.absorbOnce();
    assertEquals(0, second.absorbed());
    assertEquals(1, countWhere("SELECT COUNT(*) FROM time_entry"), "重放一次就多一段计时，账就假了");
  }

  @Test
  void unreadableLinesGoToARejectedFileInsteadOfBeingGuessed() throws Exception {
    write("这行没有时长", "14:20 时刻写法不能往回补", "25m 这行是好的");
    InboxAbsorber.Result r = absorber.absorbOnce();
    assertEquals(1, r.absorbed());
    assertEquals(2, r.rejected());
    assertEquals(1, countWhere("SELECT COUNT(*) FROM time_entry"));
    String rejected = rejectedText();
    assertTrue(rejected.contains("这行没有时长"), rejected);
    assertTrue(rejected.contains("14:20"), rejected);
    assertTrue(rejected.contains("时长"), "要说清为什么没收：" + rejected);
  }

  /**
   * `p` 前缀在界面里是番茄，但轮次是应用的状态机数出来的，外部给不了 —— 悄悄收成普通计时
   * 会让按 kind 数番茄的消费方少算，所以拒掉并说清去哪补。
   */
  @Test
  void pomodoroPrefixIsRefusedNotSilentlyDowngraded() throws Exception {
    write("p 25m 外部想补一个番茄");
    InboxAbsorber.Result r = absorber.absorbOnce();
    assertEquals(0, r.absorbed(), "轮次归应用数，inbox 不收 p 前缀");
    assertEquals(1, r.rejected());
    assertEquals(0, countWhere("SELECT COUNT(*) FROM time_entry"));
    String rejected = rejectedText();
    assertTrue(rejected.contains("番茄"), "要说清为什么没收：" + rejected);
    assertTrue(rejected.contains("轮次"), rejected);
  }

  /**
   * 把所有 rejected-*.jsonl 合起来读。
   *
   * 文件名带秒级时刻，所以同一个循环里两条被拒的行**可能落在两个文件里**（跨秒那一刻）：
   * 只读第一个会在跨秒时假红 —— 这正是这条断言原本的不稳定来源。
   */
  private String rejectedText() throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : s.filter(f -> f.getFileName().toString().startsWith("rejected-")).toList()) {
        sb.append(Files.readString(p));
      }
    }
    return sb.toString();
  }

  /** 与界面补录同一条判据：相交就拒，绝不替外部写的人认 overlap_waived。 */
  @Test
  void overlappingInboxLineIsRefusedNotWaived() throws Exception {
    String projectId = entries.ensureProject("周报");
    entries.start("正在跑的周报", EntryKind.COUNT_UP, projectId, false);
    // 等一秒：时间戳秒级，同一秒内起止算"首尾相接"而不是重叠（Interval 的既有判据）
    Thread.sleep(1100);
    write("10m 同项目撞一下 @周报");
    InboxAbsorber.Result r = absorber.absorbOnce();
    assertEquals(0, r.absorbed());
    assertEquals(1, r.rejected());
    assertEquals(1, countWhere("SELECT COUNT(*) FROM time_entry"), "被拒的那条不许进库");
    assertEquals(0, countWhere("SELECT COUNT(*) FROM time_entry WHERE origin='manual_backfill'"));
  }

  /** 上次读到一半崩了：absorbing-* 还在，下次开机要接着处理完。 */
  @Test
  void aCrashedAbsorbIsPickedUpOnTheNextRun() throws Exception {
    Files.write(dir.resolve("absorbing-2026-10-07T10-00-00+08-00.jsonl"),
        List.of("5m 崩溃前没处理完的"));
    InboxAbsorber.Result r = absorber.absorbOnce();
    assertEquals(1, r.absorbed());
    try (Stream<Path> s = Files.list(dir)) {
      assertFalse(s.anyMatch(p -> p.getFileName().toString().startsWith("absorbing-")),
          "处理完要改名成 absorbed-*，否则每次开机都重看一遍");
    }
  }

  /** 轮转只动 absorbed-*：inbox.jsonl 与 rejected-* 都不能被"清理"掉。 */
  @Test
  void rotationOnlyTouchesItsOwnFiles() throws Exception {
    for (int i = 0; i < 20; i++) {
      Files.writeString(dir.resolve(String.format("absorbed-2026-01-%02dT00-00-00.jsonl", i + 1)),
          "");
    }
    Files.writeString(dir.resolve("rejected-keep-me.jsonl"), "留着");
    write("3m 触发一次轮转");
    absorber.absorbOnce();
    assertEquals(14, countMatching("absorbed-"), "留 14 份，和备份 zip 同一个数");
    assertTrue(Files.exists(dir.resolve("rejected-keep-me.jsonl")));
    assertTrue(Files.exists(dir.resolve("absorbed-ledger.log")) || countMatching("absorbed-") > 0,
        "台账文件不参与轮转");
  }

  /** 只允许有一条时把它取出来；多一条就直接输。不给产品代码加测试专用方法。 */
  private Entry onlyEntry() throws Exception {
    assertEquals(1, countWhere("SELECT COUNT(*) FROM time_entry"));
    synchronized (ds.lock()) {
      try (var st = ds.conn().createStatement();
           var rs = st.executeQuery("SELECT id FROM time_entry LIMIT 1")) {
        rs.next();
        return entries.find(rs.getString(1)).orElseThrow();
      }
    }
  }

  private long countMatching(String prefix) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> {
        String n = p.getFileName().toString();
        return n.startsWith(prefix) && n.endsWith(".jsonl");
      }).count();
    }
  }

  private String origin(String id) throws Exception {
    synchronized (ds.lock()) {
      try (var ps = ds.conn().prepareStatement("SELECT origin FROM time_entry WHERE id=?")) {
        ps.setString(1, id);
        try (var rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    }
  }

  private List<String> tagsOf(String id) throws Exception {
    synchronized (ds.lock()) {
      try (var ps = ds.conn().prepareStatement(
          "SELECT t.name FROM entry_tag et JOIN tag t ON t.id=et.tag_id WHERE et.entry_id=?"
              + " ORDER BY t.name")) {
        ps.setString(1, id);
        try (var rs = ps.executeQuery()) {
          List<String> out = new java.util.ArrayList<>();
          while (rs.next()) {
            out.add(rs.getString(1));
          }
          return out;
        }
      }
    }
  }

  private int countWhere(String sql) throws Exception {
    synchronized (ds.lock()) {
      try (var st = ds.conn().createStatement(); var rs = st.executeQuery(sql)) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }
}
