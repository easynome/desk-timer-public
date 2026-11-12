package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.EntryKind;
import desktimer.ui.ActiveSessions.Cell;
import desktimer.ui.ActiveSessions.Cell.Fold;
import desktimer.ui.ActiveSessions.Cell.Shown;
import desktimer.ui.ActiveSessions.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 面板那一列"正在跑的会话"的运行时影子（① 纯内存，不接 FX 不接库）。
 *
 * 判据逐条都来自已定的产品口径，每条一个测试：
 *  - 列表按加入顺序排列，新加入的成为选中项，动作键作用在刚输入的那一段；
 *  - 删掉选中项时选中号**前移**（落在同一位置的下一条），删的是最后一条就退回前一条，
 *  -  ↑↓ 环绕：面板只有那么几行，走到头还能接着走；
 *  - 时长用单调时钟读数量（accumulated + (paused ? 0 : now - resumedAt)），暂停期间不再增长；
 *  - 到点只响一次：tick 每 250ms 跑一遍，takeDue 返回的同时就标记已处理；
 *  - 超出的段折叠成"N 段没显示"，但**可见的那几格必须是一个连续窗口且一定含选中项**：
 *    窗口不连续或选中项看不见，↑↓ 就变成"按了没反应"。
 */
class ActiveSessionsTest {

  @Test
  void heartbeatCanSnapshotIdsWhileTheUiStartsAndEndsSessions() throws Exception {
    ActiveSessions sessions = new ActiveSessions();
    CountDownLatch start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var writer = workers.submit(() -> {
        start.await();
        for (int i = 0; i < 100_000; i++) {
          String id = "session-" + i;
          sessions.add(new Session(id, EntryKind.COUNT_UP, "task", null, 0, 0));
          sessions.remove(id);
        }
        return null;
      });
      var reader = workers.submit(() -> {
        start.await();
        for (int i = 0; i < 100_000; i++) {
          List<String> ids = sessions.idsSnapshot();
          assertTrue(ids.size() <= 1, "只有一个 UI 写者，每次最多一段有主人的会话");
          assertTrue(ids.stream().allMatch(id -> id.startsWith("session-")));
        }
        return null;
      });
      start.countDown();
      writer.get(15, TimeUnit.SECONDS);
      reader.get(15, TimeUnit.SECONDS);
      assertEquals(List.of(), sessions.idsSnapshot());
    } finally {
      workers.shutdownNow();
    }
  }

  /** 面板可见区当前定的行数（接进 UiController 时用的就是这个值）。 */
  private static final int ROWS = 6;

  private static Session row(String id, EntryKind kind, Long targetMs, int round) {
    return new Session(id, kind, "任务-" + id, targetMs, round, 0L, null);
  }

  /** 面板行尾的 `@项目` 靠 Session.project()；空白必须归一成 null，不然会出现一个光杆 @。 */
  @Test
  void sessionCarriesItsProjectForThePanelRow() {
    assertEquals("Portal", new Session("p1", EntryKind.COUNT_DOWN, "写周报", 60_000L, 0, 0L,
        "Portal").project());
    assertNull(new Session("p2", EntryKind.COUNT_UP, "空项目", null, 0, 0L, "   ").project(),
        "只有空白的项目等于没挂");
    assertNull(new Session("p3", EntryKind.COUNT_UP, "没挂项目", null, 0, 0L, null).project());
  }

  private static ActiveSessions rows(String... ids) {
    ActiveSessions s = new ActiveSessions();
    for (String id : ids) {
      s.add(row(id, EntryKind.COUNT_UP, null, 0));
    }
    // add 会让刚起的那一段成为选中项（见 laterAdds… 那条），夹具要的是"从第一行开始"，
    // 所以这里显式把选中放回第一条，别让它靠默认行为的巧合成立。
    if (ids.length > 0) {
      s.select(ids[0]);
    }
    return s;
  }

  private static List<Session> shownCells(List<Cell> cells) {
    List<Session> out = new ArrayList<>();
    for (Cell c : cells) {
      if (c instanceof Shown sh) {
        out.add(sh.session());
      }
    }
    return out;
  }

  /** 只有一格 Shown 时它必须是选中项：折叠态下"看得见的那一行就是能操作的那一行"。 */
  private static void assertExactlyOneRowMarkedSelected(ActiveSessions s, List<Cell> cells) {
    int marked = 0;
    for (Cell c : cells) {
      if (c instanceof Shown sh && sh.selected()) {
        marked++;
        assertEquals(s.selected(), sh.session(), "被标记的格必须是当前选中那一行");
      }
    }
    assertEquals(1, marked, "可见区里只能有一格带选中标记");
  }

  @Test
  void theFirstAddedSessionIsTheSelectedOne() {
    ActiveSessions s = new ActiveSessions();
    Session first = row("a", EntryKind.COUNT_UP, null, 0);
    assertEquals(0, s.add(first), "add 返回加入后的下标，列表按加入顺序排列");
    assertEquals(first, s.selected(), "第一条一加就成为选中项：面板不能开局没有可操作行");
    assertEquals(0, s.selectedIndex());
    assertEquals(1, s.size());
    assertFalse(s.isEmpty());
  }

  /**
   * 新起一段**就是**选中项。反过来（不抢）会出事故：番茄在跑，他敲 `10m 煮饭`，
   * 紧接着按 E —— 不抢的规则结束掉的是那个跑到一半的番茄，正是他不想发生的事；
   * "刚起的那条被选中"符合输入即走的直觉，想动旧的先按一次方向键，面板上标着选中的是哪行。
   */
  @Test
  void laterAddsAppendToTheTailAndBecomeTheSelectedRow() {
    ActiveSessions s = new ActiveSessions();
    Session a = row("a", EntryKind.COUNT_UP, null, 0);
    s.add(a);
    assertEquals(1, s.add(row("b", EntryKind.COUNT_DOWN, 60_000L, 0)));
    Session c = row("c", EntryKind.POMODORO_WORK, 25 * 60_000L, 1);
    assertEquals(2, s.add(c));
    assertEquals(List.of("a", "b", "c"), s.all().stream().map(Session::id).toList(),
        "列表仍按加入顺序排列：最早的在上面");
    assertEquals(c, s.selected(), "刚起的这一段拿住动作键");
    assertEquals(2, s.selectedIndex());
  }

  @Test
  void removingTheSelectedRowLetsTheNextOneTakeItsPlace() {
    ActiveSessions s = rows("a", "b", "c", "d");
    assertTrue(s.select("b"));
    assertTrue(s.remove("b"), "id 在列表里就该真删掉");
    assertEquals("c", s.selected().id(), "删的是中间那条 ⇒ 选中号留在同一位置，落在顺延上来的下一条");
    assertEquals(1, s.selectedIndex());
    assertEquals(3, s.size());
  }

  @Test
  void removingTheLastRowMovesTheSelectionBackOne() {
    ActiveSessions s = rows("a", "b", "c");
    s.select("c");
    assertTrue(s.remove("c"));
    assertEquals("b", s.selected().id(), "删的是最后一条 ⇒ 没有下一条可顶，选中号退回前一条");
    assertEquals(1, s.selectedIndex());
  }

  @Test
  void removingAnUnselectedRowKeepsTheSameRowSelected() {
    ActiveSessions s = rows("a", "b", "c");
    s.select("c");
    assertTrue(s.remove("a"));
    assertEquals("c", s.selected().id(), "删掉别的行不能把选中挪到别人身上");
    assertEquals(1, s.selectedIndex(), "前面的行前移了，选中号要跟着自己的那一行走");
  }

  @Test
  void removingTheOnlyRowEmptiesTheListAndTheSelection() {
    ActiveSessions s = rows("a");
    assertTrue(s.remove("a"));
    assertTrue(s.isEmpty());
    assertEquals(0, s.size());
    assertNull(s.selected(), "空列表没有选中项");
    assertEquals(-1, s.selectedIndex());
  }

  @Test
  void removingAnUnknownIdFromAnyStateReportsFalse() {
    assertFalse(rows("a", "b").remove("zzz"), "列表里没有这个 id ⇒ 如实说没删掉");
    assertFalse(rows("a", "b").remove(null), "id 为空不许抛异常，只报没删掉");
    ActiveSessions empty = new ActiveSessions();
    assertFalse(empty.remove("a"), "空列表 remove 不存在");
    assertEquals(2, rows("a", "b").size(), "被拒的 remove 不许改动列表");
  }

  @Test
  void selectionWrapsAroundBothEnds() {
    ActiveSessions s = rows("a", "b", "c");
    s.selectNext();
    assertEquals("b", s.selected().id());
    s.selectNext();
    assertEquals("c", s.selected().id());
    s.selectNext();
    assertEquals("a", s.selected().id(), "最后一行往下走回到第一行：面板只有几行，走到头还得能走");
    s.selectPrev();
    assertEquals("c", s.selected().id(), "第一行往上走回到最后一行");
  }

  @Test
  void selectionOnAnEmptyListIsHarmless() {
    ActiveSessions s = new ActiveSessions();
    assertNull(s.selected());
    assertEquals(-1, s.selectedIndex());
    assertFalse(s.select("a"), "没有这一行就选不上");
    s.selectNext();
    s.selectPrev();
    assertEquals(-1, s.selectedIndex());
    assertTrue(s.cells(ROWS).isEmpty(), "空列表 ⇒ 空输出");
    assertTrue(s.takeDue(9_999L).isEmpty());
    assertFalse(s.hasPomodoro());
    assertFalse(s.hasPomodoroOrRest());
  }

  @Test
  void selectByIdMovesSelectionAndAnUnknownIdChangesNothing() {
    ActiveSessions s = rows("a", "b", "c");
    assertFalse(s.select("zzz"));
    assertEquals("a", s.selected().id(), "选不上也不许把原来的选中弄丢");
    assertTrue(s.select("c"));
    assertEquals("c", s.selected().id());
    assertEquals(2, s.selectedIndex());
    assertFalse(s.select(null));
  }

  @Test
  void allReturnsAnImmutableCopy() {
    ActiveSessions s = rows("a", "b");
    List<Session> snapshot = s.all();
    assertEquals(2, snapshot.size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(row("c",
        EntryKind.COUNT_UP, null, 0)), "外部改不动面板那一列");
    assertEquals(2, s.size());
  }

  @Test
  void byIdFindsTheRowOrSaysNull() {
    ActiveSessions s = rows("a", "b");
    assertEquals("b", s.byId("b").id());
    assertNull(s.byId("zzz"));
    assertNull(s.byId(null));
  }

  @Test
  void hasPomodoroIgnoresRestSegments() {
    ActiveSessions s = new ActiveSessions();
    assertFalse(s.hasPomodoro());
    s.add(row("r", EntryKind.REST_SHORT, 5 * 60_000L, 0));
    assertFalse(s.hasPomodoro(), "休息段不算番茄：番茄同时只允许一个的判据只管工作段");
    s.add(row("p", EntryKind.POMODORO_WORK, 25 * 60_000L, 1));
    assertTrue(s.hasPomodoro());
    assertTrue(s.remove("p"));
    assertFalse(s.hasPomodoro(), "番茄结束后就该允许再起一个");
  }

  @Test
  void hasPomodoroOrRestCoversEveryAutoPinnedKind() {
    for (EntryKind kind : List.of(EntryKind.POMODORO_WORK, EntryKind.REST_SHORT,
        EntryKind.REST_LONG)) {
      ActiveSessions s = new ActiveSessions();
      s.add(row("k", kind, 60_000L, 0));
      assertTrue(s.hasPomodoroOrRest(), kind.wire() + " 在跑就该自动钉住");
    }
    for (EntryKind kind : List.of(EntryKind.COUNT_UP, EntryKind.COUNT_DOWN)) {
      ActiveSessions s = new ActiveSessions();
      s.add(row("k", kind, kind == EntryKind.COUNT_UP ? null : 60_000L, 0));
      assertFalse(s.hasPomodoroOrRest(), kind.wire() + " 不是番茄链路，不该连带钉住");
    }
  }

  @Test
  void elapsedIsMeasuredFromMonotonicReadingsAndFreezesWhilePaused() {
    Session s = new Session("a", EntryKind.COUNT_DOWN, "任务-a", 10_000L, 0, 1_000L);
    assertEquals(0, s.elapsedMs(1_000L), "起点那一刻 elapsed 是 0");
    assertEquals(500, s.elapsedMs(1_500L), "时长是量出来的：只取单调时钟读数之差");
    s.pause(1_500L);
    assertTrue(s.paused());
    assertEquals(500, s.elapsedMs(9_000L), "暂停期间不再增长");
    s.resume(9_000L);
    assertFalse(s.paused());
    assertEquals(700, s.elapsedMs(9_200L), "继续之后从暂停点接着量，暂停那段不计入");
    s.pause(9_200L);
    s.pause(9_300L);
    assertEquals(700, s.elapsedMs(20_000L), "重复暂停不许把时间算两遍");
    s.resume(20_000L);
    assertEquals(700, s.elapsedMs(20_000L), "继续那一刻读到的还是暂停时结算下来的数");
    s.resume(20_100L);
    assertEquals(800, s.elapsedMs(20_100L), "没暂停时重复继续不许凭空加时长：只涨实际的 100ms");
  }

  @Test
  void takeDueSkipsCountUpRowsAndPausedRows() {
    ActiveSessions s = new ActiveSessions();
    Session up = new Session("up", EntryKind.COUNT_UP, "正计时", null, 0, 0L);
    Session down = new Session("down", EntryKind.COUNT_DOWN, "倒计时", 1_000L, 0, 0L);
    Session resting = new Session("rest", EntryKind.COUNT_DOWN, "暂停的倒计时", 1_000L, 0, 0L);
    resting.pause(0L);
    s.add(up);
    s.add(down);
    s.add(resting);
    List<Session> due = s.takeDue(5_000L);
    assertEquals(List.of("down"), due.stream().map(Session::id).toList(),
        "正计时没有到点这一说，暂停中的那段也不该响");
    assertFalse(up.fired());
    assertFalse(resting.fired(), "跳过的那条不许被标记成已处理：它恢复之后还要正常到点");
    assertFalse(down.paused());
  }

  @Test
  void takeDueFiresEachSegmentOnlyOnce() {
    ActiveSessions s = new ActiveSessions();
    s.add(new Session("a", EntryKind.POMODORO_WORK, "番茄", 1_000L, 1, 0L));
    assertEquals(1, s.takeDue(1_001L).size());
    assertTrue(s.byId("a").fired(), "已经为到点处理过一次就该留痕");
    assertTrue(s.takeDue(60_000L).isEmpty(), "tick 每 250ms 跑一遍，同一段不能反复响铃");
  }

  @Test
  void reachingTheTargetExactlyCountsAsDue() {
    ActiveSessions s = new ActiveSessions();
    Session justShort = new Session("a", EntryKind.COUNT_DOWN, "还差一点", 1_000L, 0, 0L);
    s.add(justShort);
    assertTrue(s.takeDue(999L).isEmpty(), "没到点不许提前响");
    assertEquals(1, s.takeDue(1_000L).size(), "elapsed == target 也算到点（判据是 >=）");
  }

  /** 六参构造是纯函数可测的代价：起点由调用方给，targetMs 为空就是正计时。 */
  @Test
  void aSessionExposesItsOwnTargetRoundAndKind() {
    Session pomodoro = new Session("p", EntryKind.POMODORO_WORK, "第 2 轮番茄",
        25 * 60_000L, 2, 1_000L);
    assertEquals(25 * 60_000L, pomodoro.targetMs(), "到点判定读的是同一条 targetMs");
    assertEquals(2, pomodoro.round(), "轮次由调用方给，非番茄才是 0");
    assertEquals(EntryKind.POMODORO_WORK, pomodoro.kind());
    assertEquals("第 2 轮番茄", pomodoro.task());
    assertEquals(500, pomodoro.elapsedMs(1_500L), "起点就是构造传进来的那个读数：1000 到 1500 量出 500");
    Session up = new Session("u", EntryKind.COUNT_UP, "正计时", null, 0, 0L);
    assertNull(up.targetMs(), "targetMs 为空表示正计时，没有到点");
    assertEquals(0, up.round(), "非番茄的轮次是 0");
  }

  @Test
  void cellsShowEveryRowWhileThePanelIsNotFull() {
    ActiveSessions s = rows("a", "b", "c");
    s.select("b");
    List<Cell> cells = s.cells(ROWS);
    assertEquals(3, cells.size(), "size <= maxRows ⇒ 有几行就出几格");
    assertEquals(List.of("a", "b", "c"),
        cells.stream().map(c -> ((Shown) c).session().id()).toList(), "没超就不许出现折叠格");
    assertFalse(cells.stream().anyMatch(c -> c instanceof Fold));
    assertFalse(((Shown) cells.get(0)).selected());
    assertTrue(((Shown) cells.get(1)).selected(), "每格带 selected 标记");
    assertFalse(((Shown) cells.get(2)).selected());
    assertExactlyOneRowMarkedSelected(s, cells);
  }

  @Test
  void cellsFoldTheOverflowWhenThereAreTooManySessions() {
    ActiveSessions s = rows("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    List<Cell> cells = s.cells(ROWS);
    assertEquals(ROWS, cells.size(), "超了就恰好 maxRows 格");
    assertEquals(ROWS - 1, shownCells(cells).size(), "其中 maxRows-1 格是会话");
    assertTrue(cells.get(ROWS - 1) instanceof Fold, "最后一格是折叠指示");
    assertEquals(10 - (ROWS - 1), ((Fold) cells.get(ROWS - 1)).hidden(),
        "Fold 写的就是没显示出来的段数");
    assertEquals(List.of("a", "b", "c", "d", "e"),
        shownCells(cells).stream().map(Session::id).toList(), "选中项在第一行时窗口顶上对齐");
  }

  @Test
  void theWindowSlidesToIncludeTheSelectedRow() {
    ActiveSessions s = rows("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    s.select("i");
    List<Cell> cells = s.cells(ROWS);
    assertEquals(List.of("e", "f", "g", "h", "i"),
        shownCells(cells).stream().map(Session::id).toList(), "选中项在窗口之外 ⇒ 窗口滑到把它包进来");
    assertEquals(5, ((Fold) cells.get(ROWS - 1)).hidden(), "Fold 顺延到腾不下的那一行");
    assertExactlyOneRowMarkedSelected(s, cells);
  }

  @Test
  void everySelectedIndexStaysVisibleWhileFolded() {
    ActiveSessions s = rows("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    for (int i = 0; i < s.size(); i++) {
      assertTrue(s.select(s.all().get(i).id()));
      List<Cell> cells = s.cells(ROWS);
      assertEquals(ROWS, cells.size(), "第 " + i + " 行被选中时也要恰好 maxRows 格");
      List<Session> shown = shownCells(cells);
      assertEquals(ROWS - 1, shown.size());
      assertTrue(shown.contains(s.selected()), "选中项第 " + i + " 行必须落在可见窗口里");
      int start = s.all().indexOf(shown.get(0));
      for (int k = 0; k < shown.size(); k++) {
        assertEquals(start + k, s.all().indexOf(shown.get(k)),
            "第 " + i + " 行被选中时可见那几格必须是连续窗口");
      }
      assertEquals(s.size() - (ROWS - 1), ((Fold) cells.get(ROWS - 1)).hidden());
      assertExactlyOneRowMarkedSelected(s, cells);
    }
  }

  @Test
  void aPanelThatFitsNothingFoldsEverything() {
    ActiveSessions s = rows("a", "b", "c");
    // maxRows=1 按定死的公式退化成一格折叠：可见格数是 maxRows-1，产品实际用的是 6。
    List<Cell> one = s.cells(1);
    assertEquals(1, one.size());
    assertTrue(one.get(0) instanceof Fold);
    assertEquals(3, ((Fold) one.get(0)).hidden());
    assertTrue(s.cells(0).isEmpty(), "一行都放不下就没有格");
    assertTrue(s.cells(-5).isEmpty(), "负数行数不许抛异常");
  }

  @Test
  void toStringNamesTheRowItIs() {
    Session s = new Session("p1", EntryKind.POMODORO_WORK, "写周报", 25 * 60_000L, 3, 0L);
    assertTrue(s.toString().contains("p1"), "断言失败时要能读出是哪一行：" + s);
    assertTrue(s.toString().contains("写周报"), "任务文本也要在：" + s);
    s.pause(0L);
    assertTrue(s.toString().contains("已暂停"), "暂停状态要看得出来：" + s);
    assertFalse(new Session("u", EntryKind.COUNT_UP, "正计时", null, 0, 0L).toString().isEmpty());
  }
}
