package desktimer.core.time;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 休眠与崩溃对账判定（纯函数，不碰数据库）。
 *
 * 关键取舍：
 *  - 参考点是"最后一次确认存活"（心跳），没有心跳就退回开始时间。
 *  - 结束时间取参考点而不是重启时刻 —— 否则会把断电/合盖期间也算进时长。
 *  - 间隙小于宽限期视为"只是开盖太快"，直接继续，不作废番茄。
 *  - 所有 RECONCILE 结果都要用户确认（needsReview=true），但不在启动瞬间弹窗。
 */
public final class Recovery {

  public enum Action {
    NOTHING_TO_RECOVER, CONTINUE_RUNNING, RECONCILE
  }

  /** 与 schema 的 cause CHECK 约束一致。 */
  public enum Cause {
    SUSPEND, CRASH, POWER_LOSS
  }

  public enum EndPrecision {
    USER("user"), HEARTBEAT("heartbeat");

    private final String wire;

    EndPrecision(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  public static final long DEFAULT_SUSPEND_GRACE_MS = 60_000L;

  public record Decision(
      Action action,
      EntryStatus status,
      String suggestedEnd,
      Long durationMs,
      EndPrecision precision,
      Cause cause,
      boolean needsReview,
      long gapMs) {

    static Decision nothing(Cause cause) {
      return new Decision(Action.NOTHING_TO_RECOVER, EntryStatus.RUNNING,
          null, null, null, cause, false, 0L);
    }
  }

  private Recovery() {
  }

  public static Decision decide(String startedAt, String lastHeartbeat, String now, Cause cause) {
    return decide(startedAt, lastHeartbeat, now, DEFAULT_SUSPEND_GRACE_MS, cause);
  }

  public static Decision decide(String startedAt, String lastHeartbeat, String now,
      long graceMs, Cause cause) {
    if (startedAt == null || startedAt.isBlank()) {
      return Decision.nothing(cause);
    }
    OffsetDateTime start = OffsetDateTime.parse(startedAt);
    OffsetDateTime reference = lastHeartbeat == null || lastHeartbeat.isBlank()
        ? start
        : OffsetDateTime.parse(lastHeartbeat);
    OffsetDateTime at = OffsetDateTime.parse(now);
    long gapMs = Duration.between(reference, at).toMillis();

    if (gapMs >= 0 && gapMs <= graceMs) {
      return new Decision(Action.CONTINUE_RUNNING, EntryStatus.RUNNING,
          null, null, null, cause, false, gapMs);
    }

    long durationMs = Math.max(0L, Duration.between(start, reference).toMillis());
    EndPrecision precision = lastHeartbeat == null || lastHeartbeat.isBlank()
        ? null
        : EndPrecision.HEARTBEAT;
    return new Decision(Action.RECONCILE, EntryStatus.INTERRUPTED,
        reference.format(Clock.WIRE), durationMs, precision, cause, true, gapMs);
  }
}
