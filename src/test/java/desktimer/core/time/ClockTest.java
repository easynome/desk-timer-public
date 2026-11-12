package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 时间戳序列化必须定宽。
 * ISO 默认格式会在秒为 0 时省略秒，长度一变，字典序比较就不再等于时间序。
 */
class ClockTest {

  private static final String FIXED_WIDTH = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}";

  @Test
  void wireFormatKeepsZeroSeconds() {
    OffsetDateTime onTheMinute = OffsetDateTime.parse("2026-10-06T14:24:00+08:00");
    String out = onTheMinute.format(Clock.WIRE);
    assertEquals("2026-10-06T14:24:00+08:00", out);
    assertTrue(out.matches(FIXED_WIDTH), "宽度不固定：" + out);
  }

  @Test
  void toStringWouldDropZeroSecondsSoItMustNotBeUsedForStorage() {
    OffsetDateTime onTheMinute = OffsetDateTime.parse("2026-10-06T14:24:00+08:00");
    String viaToString = onTheMinute.toString();
    assertTrue(!viaToString.matches(FIXED_WIDTH),
        "toString() 在秒为 0 时会省略秒，落库与导出都不许用它，实际值=" + viaToString);
    assertEquals("2026-10-06T14:24:00+08:00", onTheMinute.format(Clock.WIRE));
  }

  @Test
  void wallOutputIsFixedWidthAndLexicallyOrdered() throws Exception {
    Clock clock = new Clock();
    String a = clock.wall();
    Thread.sleep(1100);
    String b = clock.wall();
    assertTrue(a.matches(FIXED_WIDTH), a);
    assertTrue(b.matches(FIXED_WIDTH), b);
    assertTrue(a.compareTo(b) < 0, "字典序应当等于时间序：" + a + " vs " + b);
  }
}
