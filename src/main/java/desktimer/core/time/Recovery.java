package desktimer.core.time;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 休眠与崩溃对账判定（纯函数，不碰数据库）。
 *
 * 关键取舍：
 *  - 参考点是"最后一次确认存活"（心跳），没有心跳就退回开始时间。
 *  - 结束时间取参考点而不是重启时刻 —— 否则会把断电/合盖期间也算进时长。
 *  - **没有"接着跑"这个答案**。这段判定只在启动时跑（{@code EntryRepository.reconcileRunning}），
 *    而跑它的是新进程：它手里没有那一段的会话对象，判成"继续"就是留下一行没人认领的僵尸 ——
 *    状态还是 running，于是心跳线程按 status 给它续命，既不结束也不进队列，也没有窗口
 *    （2026-10-11 真实库：一行被这样续了两小时二十分钟）。间隙大小从此只影响读数（{@code gapMs}），
 *    不再决定要不要摊开给用户看。
 *  - 结果一律要用户确认（needsReview=true），但不在启动瞬间弹窗：托盘先亮，唤起时再摊开。
 */
public final class Recovery {

  public enum Action {
    NOTHING_TO_RECOVER, RECONCILE
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
    if (startedAt == null || startedAt.isBlank()) {
      return Decision.nothing(cause);
    }
    OffsetDateTime start = OffsetDateTime.parse(startedAt);
    OffsetDateTime reference = lastHeartbeat == null || lastHeartbeat.isBlank()
        ? start
        : OffsetDateTime.parse(lastHeartbeat);
    OffsetDateTime at = OffsetDateTime.parse(now);
    long gapMs = Duration.between(reference, at).toMillis();
    long durationMs = Math.max(0L, Duration.between(start, reference).toMillis());
    EndPrecision precision = lastHeartbeat == null || lastHeartbeat.isBlank()
        ? null
        : EndPrecision.HEARTBEAT;
    return new Decision(Action.RECONCILE, EntryStatus.INTERRUPTED,
        reference.format(Clock.WIRE), durationMs, precision, cause, true, gapMs);
  }
}
