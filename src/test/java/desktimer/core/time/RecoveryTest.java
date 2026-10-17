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
 * S4 的核心判据（2026-10-11 改）：结束时间取最后心跳而不是重启时刻；没有心跳退回开始时间；
 * **不再区分"间隙小于宽限期 ⇒ 原样继续"** —— 这段判定只在启动时跑，而新进程手里没有那一段的
 * 会话对象，判成"继续"就是留一行没人认领、又被心跳续命的僵尸（见 `Recovery` 类注释与
 * `EntryRepositoryTest.heartbeatOnlyFeedsRowsWithALiveSession`）。
 */
class RecoveryTest {

  /** 心跳只差 30 秒（旧行为：判"还在跑"）——现在也必须摊开，结束点仍是最后心跳。 */
  @Test
  void shortGapIsReconciledBecauseNoOneCanContinueIt() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T14:24:30+08:00",
        Cause.SUSPEND);
    assertEquals(Action.RECONCILE, d.action());
    assertEquals(EntryStatus.INTERRUPTED, d.status());
    assertEquals("2026-10-06T14:24:00+08:00", d.suggestedEnd());
    assertEquals(24 * 60 * 1000L, d.durationMs(), "只算到最后一次心跳，那 30 秒不算进去");
    assertTrue(d.needsReview());
    assertEquals(30_000L, d.gapMs(), "间隙大小仍然报得出来，只是不再决定要不要摊开");
  }

  /** 重启只比最后一次心跳晚 0 秒：也不能免掉确认，而且精度必须落在心跳上。 */
  @Test
  void zeroGapStillReconcilesAndEndsAtTheHeartbeatNotAtBoot() {
    Decision d = Recovery.decide(
        "2026-10-06T14:00:00+08:00", "2026-10-06T14:24:00+08:00", "2026-10-06T14:24:00+08:00",
        Cause.CRASH);
    assertEquals(Action.RECONCILE, d.action());
    assertEquals("2026-10-06T14:24:00+08:00", d.suggestedEnd());
    assertEquals(EndPrecision.HEARTBEAT, d.precision());
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
