-- schema_version 1.0.1 -> PRAGMA user_version = 10100
-- 只加列：倒计时的目标时长。没有它，重启后正跑着的倒计时不知道该显示什么。
-- 不进导出契约（消费方拿 duration_ms 与 started/ended 就够了），所以 1.0.0 的字段表不变。

ALTER TABLE time_entry ADD COLUMN target_ms INTEGER;
