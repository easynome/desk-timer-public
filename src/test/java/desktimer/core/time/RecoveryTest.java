package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.Recovery.Action;
import desktimer.core.time.Recovery.Cause;
import desktimer.core.time.Recovery.Decision;
import desktimer.core.time.Recovery.EndPrecision;
import org.junit.jupiter.api.Test;

/**
 * S4 的三组核心判据：跨睡眠 <60s 续、>=60s 作废、结束时间取最后心跳而不是重启时刻。
 */
class RecoveryTest {

  @Test
  void shortGapKeepsRunning() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T14:24:30+08:00",
        Cause.SUSPEND);
    assertEquals(Action.CONTINUE_RUNNING, d.action());
    assertEquals(EntryStatus.RUNNING, d.status());
    assertNull(d.suggestedEnd());
    assertTrue(!d.needsReview());
  }

  @Test
  void gapExactlyAtGraceBoundaryStillKeepsRunning() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T14:25:00+08:00",
        Cause.SUSPEND);
    assertEquals(Action.CONTINUE_RUNNING, d.action());
  }

  @Test
  void longGapReconcilesToEndAtLastHeartbeatNotAtBoot() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T19:02:00+08:00",
        Cause.SUSPEND);
    assertEquals(Action.RECONCILE, d.action());
    assertEquals(EntryStatus.INTERRUPTED, d.status());
    assertEquals("2026-10-06T14:24:00+08:00", d.suggestedEnd(), "结束时间必须是最后心跳，不是重启时刻");
    assertEquals(24 * 60 * 1000L, d.durationMs());
    assertEquals(EndPrecision.HEARTBEAT, d.precision());
    assertTrue(d.needsReview());
  }

  @Test
  void missingHeartbeatFallsBackToStartAndNeedsReview() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", null, "2026-10-06T19:00:00+08:00", Cause.POWER_LOSS);
    assertEquals(Action.RECONCILE, d.action());
    assertEquals("2026-10-06T14:00:00+08:00", d.suggestedEnd());
    assertEquals(0L, d.durationMs());
    assertNull(d.precision(), "没有心跳就谈不上精确到心跳，留给用户填");
    assertTrue(d.needsReview());
  }

  @Test
  void clockMovedBackwardsNeverYieldsNegativeDuration() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T14:10:00+08:00",
        Cause.CRASH);
    assertEquals(Action.RECONCILE, d.action());
    assertTrue(d.durationMs() >= 0, "改系统时间不能产生负时长");
    assertTrue(d.gapMs() < 0);
  }

  @Test
  void blankStartIsNothingToRecover() {
    assertEquals(Action.NOTHING_TO_RECOVER, Recovery.decide(null, null,
        "2026-10-06T19:00:00+08:00", Cause.CRASH).action());
    assertEquals(Action.NOTHING_TO_RECOVER, Recovery.decide("  ", null,
        "2026-10-06T19:00:00+08:00", Cause.CRASH).action());
  }
}
