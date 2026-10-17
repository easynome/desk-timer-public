package desktimer.core.time;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态机。非法转移直接抛，静默改状态会让时间条目变得不可信。
 *
 * INTERRUPTED 不是终态：对账确认后落到 FINISHED（用户确认或改时间）或 ABORTED（丢弃）。
 * SUPERSEDED 是终态，只由合并/拆分、或用户对账时判"这段与另一段重叠、不作数"产生，
 * 一律配合 superseded_by 留血缘。
 */
public final class Transitions {

  private static final Map<EntryStatus, Set<EntryStatus>> ALLOWED =
      new EnumMap<>(EntryStatus.class);

  static {
    ALLOWED.put(EntryStatus.RUNNING, EnumSet.of(
        EntryStatus.PAUSED, EntryStatus.FINISHED, EntryStatus.ABORTED, EntryStatus.INTERRUPTED));
    ALLOWED.put(EntryStatus.PAUSED, EnumSet.of(
        EntryStatus.RUNNING, EntryStatus.FINISHED, EntryStatus.ABORTED, EntryStatus.INTERRUPTED));
    ALLOWED.put(EntryStatus.INTERRUPTED, EnumSet.of(
        EntryStatus.FINISHED, EntryStatus.ABORTED, EntryStatus.SUPERSEDED));
    ALLOWED.put(EntryStatus.FINISHED, EnumSet.of(EntryStatus.SUPERSEDED));
    ALLOWED.put(EntryStatus.ABORTED, EnumSet.noneOf(EntryStatus.class));
    ALLOWED.put(EntryStatus.SUPERSEDED, EnumSet.noneOf(EntryStatus.class));
  }

  private Transitions() {
  }

  public static boolean canMove(EntryStatus from, EntryStatus to) {
    return ALLOWED.getOrDefault(from, EnumSet.noneOf(EntryStatus.class)).contains(to);
  }

  public static Set<EntryStatus> allowedFrom(EntryStatus from) {
    return Collections.unmodifiableSet(ALLOWED.getOrDefault(from, EnumSet.noneOf(EntryStatus.class)));
  }

  public static EntryStatus require(EntryStatus from, EntryStatus to) {
    if (!canMove(from, to)) {
      throw new IllegalStateException("非法状态转移 " + from.wire() + " -> " + to.wire()
          + "（允许：" + allowedFrom(from) + "）");
    }
    return to;
  }
}
