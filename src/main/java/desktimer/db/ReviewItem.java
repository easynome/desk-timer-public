package desktimer.db;

import desktimer.core.time.EntryStatus;

/**
 * 对账卡的读取视图：一条"程序替用户决定过、需要他过目"的条目。
 *
 * 为什么不复用 {@link Entry}：这里要的是 end_precision / cause / missed 三个
 * "为什么需要你确认"的理由字段，而 Entry 是计时主视图，加进去会让每条读取都拖着
 * 用不上的列。两个视图各读各的，字段语义仍由 time_entry 单点决定。
 */
public record ReviewItem(
    String id,
    String task,
    EntryStatus status,
    String startedAt,
    String endedAt,
    Long durationMs,
    String endPrecision,
    String cause,
    boolean missed,
    boolean needsReview,
    boolean overlapPending) {

  /** 结束时间是按最后心跳猜的，还是用户自己定的。 */
  public boolean guessedEnd() {
    return "heartbeat".equals(endPrecision);
  }

  /**
   * 卡上的这一条要问的话。重叠待确认与"结束时间是猜的"是两类问题，
   * 动作的含义也不同（前者"两段都算/这段作废"，后者"确认/改时间"），所以文案在这儿分家，
   * 不在卡里再判一次字段。
   */
  public String question() {
    if (overlapPending) {
      return "这段与另一段相交且你还没认过 —— 两段都算，还是这段作废？";
    }
    return guessedEnd() ? "结束时间是按最后心跳估的，确认或改时间" : "请确认这段时间";
  }
}
