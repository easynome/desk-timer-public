package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class DayAttributionTest {

  @Test
  void beforeEightOclockBelongsToPreviousDay() {
    assertEquals("2026-10-05", DayAttribution.checkinDate("2026-10-06T01:12:03+08:00"));
    assertEquals("2026-10-05", DayAttribution.checkinDate("2026-10-06T07:59:59+08:00"));
  }

  @Test
  void fromEightOclockBelongsToSameDay() {
    assertEquals("2026-10-06", DayAttribution.checkinDate("2026-10-06T08:00:00+08:00"));
    assertEquals("2026-10-06", DayAttribution.checkinDate("2026-10-06T23:30:00+08:00"));
  }

  @Test
  void crossesMonthBoundary() {
    assertEquals("2026-10-31", DayAttribution.checkinDate("2026-11-01T00:30:00+08:00"));
    assertEquals("2026-12-31", DayAttribution.checkinDate("2027-01-01T03:00:00+08:00"));
  }

  @Test
  void attributionFollowsTheOffsetInTheStringNotOurSystemZone() {
    // 规则：按时间戳自带偏移算本地时间。外部消费者只有这串字符，算不出我们的时区。
    // +09:00 的 08:30 就是"当时那台机器上的 08:30"，>=08:00 ⇒ 归当天。
    assertEquals("2026-10-06", DayAttribution.checkinDate(
        OffsetDateTime.parse("2026-10-06T08:30:00+09:00")));
    assertEquals("2026-10-05", DayAttribution.checkinDate(
        OffsetDateTime.parse("2026-10-06T07:30:00+09:00")));
    assertEquals("2026-10-06", DayAttribution.checkinDate(
        OffsetDateTime.parse("2026-10-06T08:30:00Z")));
  }
}
