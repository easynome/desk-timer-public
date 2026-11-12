package desktimer.core.parse;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 对账卡上"改时间"的解析：用户只输入一个时刻（`14:20`），补成完整的带偏移时刻。
 *
 * 两条相反的力量：
 *  - 跨零点的计时真实存在（23:50 跑、心跳 00:15），所以"输入的时刻早于开始"
 *    不能直接判非法 —— 按次日解释；
 *  - 但按次日解释完还得是个**合理时长**，否则就是打错了。实测过一条
 *    09:35 开始、输入 `8:55` 被解释成次日 ⇒ 23 小时 19 分的计时块，
 *    这种脏数据比拒绝一次输入破坏性大得多（它会长在导出里被下游算进总和）。
 * 所以超过 12 小时的"次日"一律判非法，让卡片显示 ⚠ 由用户重填。
 */
public final class TimeText {

  /** "次日"解释的上限：超过就认定是打错，不当作用户的本意。 */
  public static final long MAX_ROLL_HOURS = 12;

  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("H:mm");
  private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("H:mm:ss");

  private TimeText() {
  }

  /** 解析失败或补出来的时长不合理都返回 null：调用方在卡片上显示 ⚠，不弹窗。 */
  public static OffsetDateTime after(String text, OffsetDateTime notBefore) {
    if (text == null) {
      return null;
    }
    String t = text.trim();
    // 按冒号个数选模板：用户写 9:05 而不是 09:05 是常态，长度判不出这两种
    boolean withSeconds = t.chars().filter(ch -> ch == ':').count() > 1;
    LocalTime clock;
    try {
      clock = LocalTime.parse(t, withSeconds ? HMS : HM);
    } catch (DateTimeParseException e) {
      return null;
    }
    LocalDate day = notBefore.toLocalDate();
    OffsetDateTime same = day.atTime(clock).atOffset(notBefore.getOffset());
    if (!same.isBefore(notBefore)) {
      return same;
    }
    OffsetDateTime nextDay = same.plusDays(1);
    // 用 compareTo 而不是 toHours()：后者截断，12h40m 会被算成 12 而蒙混过关
    return Duration.between(notBefore, nextDay).compareTo(Duration.ofHours(MAX_ROLL_HOURS)) <= 0
        ? nextDay
        : null;
  }

  /** 是否跨到了次日 —— 卡片要在显示里点出来。 */
  public static boolean isNextDay(OffsetDateTime value, OffsetDateTime reference) {
    return value.toLocalDate().isAfter(reference.toLocalDate());
  }
}
