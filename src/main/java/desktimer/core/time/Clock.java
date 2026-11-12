package desktimer.core.time;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 两个时钟分工明确，不许混用：
 *  - wall()：墙上时间，用于"几点开始"和跨进程恢复；带偏移量的 ISO 8601。
 *  - elapsedMs()：单调时钟，只用于进程存活期内的时长与显示。
 * 跨重启的时长一律由 墙上时间 + last_heartbeat_at 推，不猜单调时钟跨睡眠的行为。
 */
public final class Clock {

  /**
   * 落库与导出用的定宽格式（秒恒定，不带小数秒；亚秒精度走 duration_ms）。
   * 定宽的意义：跨工具比对与人肉 diff 时长度稳定；
   * 绝不能用 OffsetDateTime.toString() —— 它在秒为 0 时会把秒省掉。
   */
  public static final DateTimeFormatter WIRE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  /** 解析入站时间戳用（容忍小数秒与不同偏移写法）。 */
  public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final long baseNanos = System.nanoTime();

  public String wall() {
    return wallNow().format(WIRE);
  }

  public OffsetDateTime wallNow() {
    return OffsetDateTime.now(ZoneId.systemDefault());
  }

  public long elapsedMs() {
    return (System.nanoTime() - baseNanos) / 1_000_000L;
  }
}
