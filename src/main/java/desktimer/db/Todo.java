package desktimer.db;

import java.util.List;

/**
 * 一条待办的读取视图。
 *
 * {@code checkinDate} 与计时条目同一个 08:00 归日算法，按创建时刻算：
 * 待办记的是"哪天想起这件事"，干成哪天看 {@code completedAt}。两个字段各说一件事，
 * 消费方才不会在同一天读到两份账。
 */
public record Todo(
    String id,
    String title,
    boolean done,
    String completedAt,
    String checkinDate,
    String projectId,
    String createdAt,
    List<String> tags,
    List<String> entryIds) {
}
