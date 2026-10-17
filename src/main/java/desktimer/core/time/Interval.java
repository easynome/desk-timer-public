package desktimer.core.time;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 半开区间 [start, end)。end 为空表示"进行中"，与闭区间比时右端按 now 解析；
 * 两条都"进行中"则必然相交（都占到当下），见 {@link #intersects}。
 * 用于 S5 的应用层重叠检查：同 exclusivity_key 相交才警告，不同 key 并行放行。
 */
public record Interval(OffsetDateTime start, OffsetDateTime end) {

  public static Interval closed(String startIso, String endIso) {
    return new Interval(OffsetDateTime.parse(startIso), OffsetDateTime.parse(endIso));
  }

  public static Interval open(String startIso) {
    return new Interval(OffsetDateTime.parse(startIso), null);
  }

  public boolean isOpen() {
    return end == null;
  }

  public OffsetDateTime endOr(OffsetDateTime now) {
    return end == null ? now : end;
  }

  /**
   * 相交判据。{@code now} 只用来给"进行中"的右端一个当下值。
   *
   * 两段都还在跑时必须直接判相交：按 {@code now} 解析的话，刚起的那一段是
   * {@code [now, now)} —— 零长半开区间，跟谁都不撞。同桶并发的放行就是这么漏掉的
   * （2026-10-08 真实库里留下一对 {@code overlap_waived=0} 的相交行，验收器当场红）。
   * "进行中"的含义是"至少占到当下"，所以两条 open 的公共部分必然包含 now。
   */
  public static boolean intersects(Interval a, Interval b, OffsetDateTime now) {
    if (a.isOpen() && b.isOpen()) {
      return true;
    }
    return a.start().isBefore(b.endOr(now)) && b.start().isBefore(a.endOr(now));
  }

  /** 首尾相接不算重叠：14:00-14:30 与 14:30-15:00 是两段独立时间。 */
  public static List<Interval> overlappingWith(Interval candidate, List<Interval> existing,
      OffsetDateTime now) {
    List<Interval> hits = new ArrayList<>();
    for (Interval one : existing) {
      if (intersects(candidate, one, now)) {
        hits.add(one);
      }
    }
    return hits;
  }
}
