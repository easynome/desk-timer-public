package desktimer.core.time;

/** 连续使用累计与休息判据。纯状态机，不操作窗口或记录学习时间。 */
public final class RestGuard {
  public static final long QUALIFYING_REST_MS = 5 * 60_000L;
  private long usedMs;
  private long lastSample = -1;
  private long actualRestMs;
  private boolean forced;

  public RestGuard(long savedUsedMs) { usedMs = Math.max(0, savedUsedMs); }

  /** 工作计时/全屏前台是持续使用证据，不能仅因没有键鼠输入把阅读或视频当休息。 */
  public boolean sample(long nowMs, Long idleMs, boolean resting, boolean activeUse, long limitMs) {
    Long effectiveIdle = idleMs;
    if (activeUse && !resting) effectiveIdle = 0L;
    return sample(nowMs, effectiveIdle, resting, limitMs);
  }

  /** 长采样间隙不算使用；明确离席满五分钟才自然清零，未知输入状态保留原累计。 */
  public boolean sample(long nowMs, Long idleMs, boolean resting, long limitMs) {
    long delta = lastSample < 0 ? 0 : Math.max(0, nowMs - lastSample);
    lastSample = nowMs;
    // 强休只有完成设定时长才结清；紧急退出要保留进入强休前的累计，重启也一样。
    if (forced) return false;
    if (idleMs != null && idleMs >= QUALIFYING_REST_MS) {
      usedMs = 0;
      actualRestMs = 0;
    } else if (delta <= 15_000) {
      if (resting) {
        actualRestMs += delta;
        if (actualRestMs >= QUALIFYING_REST_MS) usedMs = 0;
      } else {
        actualRestMs = 0;
        if (idleMs != null) usedMs += delta;
      }
    } else {
      actualRestMs = 0;
    }
    return !forced && !resting && usedMs >= limitMs;
  }

  public void forceStarted() { forced = true; actualRestMs = 0; }
  /** 收表的精确时长优先于5秒轮询，避免正好5分钟结束时漏掉最后一个采样间隔。 */
  public void recordRest(long durationMs) {
    if (!forced && durationMs >= QUALIFYING_REST_MS) usedMs = 0;
  }
  public void forceEnded(boolean completed) {
    forced = false;
    actualRestMs = 0;
    if (completed) usedMs = 0;
  }
  public long usedMs() { return usedMs; }
  public boolean forced() { return forced; }
}
