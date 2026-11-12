package desktimer.db;

import java.util.List;

/**
 * 一条随手记。字段顺序与 {@code note} 表一致，{@code tags} 是关联表读出来的派生列。
 *
 * {@code entryId} 可以为空：笔记不要求挂在哪段计时上，挂上只是"当时正好在跑"。
 *
 * {@code pinned} 是**数据的置顶**（这条值不值得常驻在默认视图里），不是窗口置顶；
 * 它 001 建表就有列，但**不进导出** —— 所以它是应用内部状态，改它不动契约。
 */
public record Note(
    String id,
    String body,
    String createdAt,
    String checkinDate,
    String projectId,
    String entryId,
    boolean pinned,
    List<String> tags) {
}
