package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntervalTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T15:00:00+08:00");

  @Test
  void touchingIntervalsDoNotOverlap() {
    Interval a = Interval.closed("2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00");
    Interval b = Interval.closed("2026-10-06T14:30:00+08:00", "2026-10-06T15:00:00+08:00");
    assertFalse(Interval.intersects(a, b, NOW), "半开区间首尾相接不算重叠");
    assertFalse(Interval.intersects(b, a, NOW));
  }

  @Test
  void partialAndContainedOverlap() {
    Interval a = Interval.closed("2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00");
    assertTrue(Interval.intersects(a,
        Interval.closed("2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00"), NOW));
    assertTrue(Interval.intersects(a,
        Interval.closed("2026-10-06T14:05:00+08:00", "2026-10-06T14:10:00+08:00"), NOW));
  }

  @Test
  void openIntervalEndsAtNow() {
    Interval open = Interval.open("2026-10-06T14:50:00+08:00");
    assertTrue(open.isOpen());
    assertEquals(NOW, open.endOr(NOW));
    assertTrue(Interval.intersects(open,
        Interval.closed("2026-10-06T14:00:00+08:00", "2026-10-06T14:55:00+08:00"), NOW));
    assertFalse(Interval.intersects(open,
        Interval.closed("2026-10-06T13:00:00+08:00", "2026-10-06T14:00:00+08:00"), NOW));
  }

  @Test
  void twoRunningSegmentsAlwaysClash() {
    // 真实场景：14:50 起了一段还在跑，14:55 又起一段。两段的右端都是"未知"，
    // 按 now 解析的话 14:55 那段变成 [14:55,15:00)?? 不 —— 刚起的那一刻 now==start，
    // 半开区间 [start,now) 是**零长**，于是谁都撞不上，静默重叠就是这么来的（见 decisions 的 M2-g）。
    Interval older = Interval.open("2026-10-06T14:50:00+08:00");
    Interval fresher = Interval.open("2026-10-06T14:55:00+08:00");
    assertTrue(Interval.intersects(older, fresher, NOW), "两段都在跑＝此刻正并行，必须算相交");
    assertTrue(Interval.intersects(fresher, older, NOW), "谁先看无所谓，相交是对称的");
    // 同一瞬间起的两段也要报：now 就是它们的起点，零长判据在这儿最要命
    Interval justStarted = Interval.open("2026-10-06T15:00:00+08:00");
    assertTrue(Interval.intersects(justStarted, older, NOW));
  }

  @Test
  void onlyReportsTheOnesThatActuallyHit() {
    Interval candidate = Interval.closed("2026-10-06T14:20:00+08:00", "2026-10-06T14:40:00+08:00");
    List<Interval> existing = List.of(
        Interval.closed("2026-10-06T14:00:00+08:00", "2026-10-06T14:30:00+08:00"),
        Interval.closed("2026-10-06T13:00:00+08:00", "2026-10-06T13:30:00+08:00"),
        Interval.closed("2026-10-06T14:30:00+08:00", "2026-10-06T15:00:00+08:00"));
    List<Interval> hits = Interval.overlappingWith(candidate, existing, NOW);
    assertEquals(2, hits.size());
    assertEquals(OffsetDateTime.parse("2026-10-06T14:00:00+08:00"), hits.get(0).start());
  }
}
