package desktimer.core.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 08:00 归日：本地时间 08:00 之前的记录归前一天。
 * 这是和下游"按天归档"的流水线对齐的口径：消费方直接用每行算好的 checkin_date，不得自行按自然日重算。
 */
public final class DayAttribution {

  static final int DAY_BOUNDARY_HOUR = 8;

  private DayAttribution() {
  }

  public static String checkinDate(OffsetDateTime at) {
    // 归属日按**时间戳自带的偏移**判 08:00，也就是"捕获当时那台机器的本地时间"。
    // 不转系统时区：外部消费者（verify-contract.mjs）拿不到我们的时区，
    // 只能按串里的偏移量复算；两边必须同一条规则，否则导出会被判归日错误。
    LocalDateTime local = at.toLocalDateTime();
    return (local.getHour() < DAY_BOUNDARY_HOUR ? local.minusDays(1) : local).toLocalDate().toString();
  }

  public static String checkinDate(String isoOffsetDateTime) {
    return checkinDate(OffsetDateTime.parse(isoOffsetDateTime));
  }
}
