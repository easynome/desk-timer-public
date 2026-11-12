package desktimer.db;

import desktimer.core.time.EntryStatus;

/**
 * 一条时间条目的读取视图。字段顺序与 time_entry 的 SELECT 保持一致。
 */
public record Entry(
    String id,
    String task,
    EntryStatus status,
    String exclusivityKey,
    String startedAt,
    String endedAt,
    Long durationMs,
    String checkinDate,
    String lastHeartbeatAt,
    Long targetMs) {
}
