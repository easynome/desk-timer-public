package desktimer.core.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class TimeTextTest {

  private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-06T23:50:00+08:00");

  @Test
  void sameDayWhenAfterStart() {
    OffsetDateTime v = TimeText.after("23:58", START);
    assertEquals(6, v.getDayOfMonth());
    assertEquals(23, v.getHour());
    assertEquals(58, v.getMinute());
    assertFalse(TimeText.isNextDay(v, START));
  }

  @Test
  void rollsToTomorrowInsteadOfRejectingTheInput() {
    // 跨零点的计时是真的存在：23:50 开始、心跳 00:15。
    // 判成"次日"并显示出来，比拒绝用户输入要好。
    OffsetDateTime v = TimeText.after("0:15", START);
    assertEquals(7, v.getDayOfMonth());
    assertEquals(8, hours(v));
    assertTrue(TimeText.isNextDay(v, START));
  }

  /** 真机实测踩过：09:35 开始、输入 8:55 被解释成次日 ⇒ 导出里长出一个 23 小时的块。 */
  @Test
  void refusesTheNextDayRollWhenTheBlockWouldBeImplausible() {
    OffsetDateTime morning = OffsetDateTime.parse("2026-10-07T09:35:54+08:00");
    assertNull(TimeText.after("8:55", morning), "23 小时的次日解释不可能是他的本意");
    assertEquals(11, TimeText.after("11:00", START).getHour(), "23:50 起 11h10m，算跨零点正常");
    assertNull(TimeText.after("12:30", START), "12h40m 超上限（截断成 12 就蒙不过去）");
    // 上限只卡"我替用户推断出来的次日"；他自己敲的当天晚些时刻不卡
    OffsetDateTime sameDayEvening = TimeText.after("21:00", morning);
    assertEquals(21, sameDayEvening.getHour());
    assertEquals(7, sameDayEvening.getDayOfMonth());
  }

  @Test
  void secondsAccepted() {
    assertEquals(31, TimeText.after("07:02:31", START).getSecond());
  }

  @Test
  void offsetIsInheritedFromTheReferenceNotTheMachine() {
    OffsetDateTime minus = OffsetDateTime.parse("2026-10-06T23:50:00-07:00");
    assertEquals(-7, hours(TimeText.after("23:59", minus)));
  }

  /** ZoneOffset 的 getHours() 不是公开方法，公开的是 getTotalSeconds()。 */
  private static int hours(OffsetDateTime at) {
    return at.getOffset().getTotalSeconds() / 3600;
  }

  @Test
  void unparseableYieldsNullSoTheCardCanWarnInPlace() {
    assertNull(TimeText.after(null, START));
    assertNull(TimeText.after("", START));
    assertNull(TimeText.after("25:99", START));
    assertNull(TimeText.after("14", START));
    assertNull(TimeText.after("两点", START));
    assertNull(TimeText.after("14:20:31:00", START));
  }
}
