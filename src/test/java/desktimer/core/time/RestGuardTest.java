package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RestGuardTest {
  @Test void ActiveWorkOrFullscreenDoesNotMistakeLongReadingForARest() {
    RestGuard guard = new RestGuard(90 * 60_000L);
    assertTrue(guard.sample(0, 30 * 60_000L, false, true, 90 * 60_000L));
    assertEquals(90 * 60_000L, guard.usedMs());
    assertFalse(guard.sample(5000, 30 * 60_000L, true, true, 90 * 60_000L));
    assertEquals(0, guard.usedMs(), "实际休息时仍允许离席判据，保护不阻止真正休息");
  }
  @Test void ninetyMinutesOfUseTriggersEvenWithoutATimer() {
    RestGuard guard = new RestGuard(0);
    assertFalse(guard.sample(0, 0L, false, 90 * 60_000));
    for (int s = 5; s < 5400; s += 5) assertFalse(guard.sample(s * 1000L, 0L, false, 90 * 60_000));
    assertTrue(guard.sample(5400_000L, 0L, false, 90 * 60_000));
    guard.forceStarted();
    assertFalse(guard.sample(5405_000L, 0L, true, 90 * 60_000));
  }
  @Test void ShortBreakAndRestartDoNotEraseUsage() {
    RestGuard guard = new RestGuard(80 * 60_000);
    guard.sample(0, 0L, false, 90 * 60_000);
    for (int s = 5; s <= 60; s += 5) guard.sample(s * 1000, 0L, true, 90 * 60_000);
    assertEquals(80 * 60_000, guard.usedMs());
    RestGuard restored = new RestGuard(guard.usedMs());
    assertEquals(guard.usedMs(), restored.usedMs());
  }
  @Test void FiveMinutesOfActualRestOrInactivityResetsUsage() {
    RestGuard guard = new RestGuard(80 * 60_000);
    guard.sample(0, 0L, true, 90 * 60_000);
    for (int s = 5; s <= 300; s += 5) guard.sample(s * 1000, 0L, true, 90 * 60_000);
    assertEquals(0, guard.usedMs());
    guard = new RestGuard(80 * 60_000);
    guard.sample(0, 300_000L, false, 90 * 60_000);
    assertEquals(0, guard.usedMs());
  }
  @Test void UnknownIdleAndSleepGapDoNotInventUsageOrRest() {
    RestGuard guard = new RestGuard(60_000);
    guard.sample(0, 0L, false, 90 * 60_000);
    guard.sample(3600_000, null, false, false, 90 * 60_000);
    guard.sample(3605_000, null, false, false, 90 * 60_000);
    assertEquals(60_000, guard.usedMs());
  }
  @Test void EmergencyExitKeepsDebtAndCompletedForcedRestClearsIt() {
    RestGuard guard = new RestGuard(90 * 60_000);
    guard.forceStarted();
    guard.sample(0, 0L, true, 90 * 60_000);
    for (int s = 5; s <= 360; s += 5) guard.sample(s * 1000L, 300_000L, true, 90 * 60_000);
    assertEquals(90 * 60_000, guard.usedMs(), "强休未完成时，不能被无输入或五分钟轮询提前结清");
    guard.forceEnded(false);
    assertEquals(90 * 60_000, guard.usedMs());
    guard.forceStarted(); guard.forceEnded(true);
    assertEquals(0, guard.usedMs());
  }
  @Test void ExactFiveMinuteCompletionDoesNotDependOnTheLastPollingTick() {
    RestGuard guard = new RestGuard(89 * 60_000);
    guard.recordRest(299_999);
    assertEquals(89 * 60_000, guard.usedMs());
    guard.recordRest(300_000);
    assertEquals(0, guard.usedMs());
  }
}
