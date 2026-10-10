-- schema_version 1.0.2 -> PRAGMA user_version = 10200
-- M2-g：反重叠判据原来只挂在 INSERT 上，而并发是"两段都在跑、收表那一刻才相交"的 ——
-- 那条路一次都没被查过，真实库里因此留下一对 overlap_waived=0 的相交行（验收器当场红）。
-- 加两样：overlap_pending（内部列，**不进导出**，与 note.pinned / target_ms 同类）
-- 与一条 BEFORE UPDATE 触发器兜底。

ALTER TABLE time_entry ADD COLUMN overlap_pending INTEGER NOT NULL DEFAULT 0;

-- 收表（或改时间）那一刻新成形的相交必须被拦住，除非这一行自己就是"待确认"。
-- 老行只改标记位（needs_review / missed / last_heartbeat_at）不许被拦：
-- 历史里已经存在的那对静默重叠得能被对账卡清理，不然应用连改回来的能力都没有。
CREATE TRIGGER trg_no_silent_overlap_update
BEFORE UPDATE ON time_entry
WHEN NEW.ended_at IS NOT NULL
     AND NEW.overlap_waived = 0 AND NEW.overlap_pending = 0
     AND NEW.status NOT IN ('aborted','superseded')
     AND (OLD.ended_at IS NULL
          OR OLD.ended_at IS NOT NEW.ended_at
          OR OLD.started_at IS NOT NEW.started_at
          OR OLD.overlap_pending = 1)
BEGIN
  SELECT RAISE(ABORT,
    'overlap: 收表时同 exclusivity_key 出现未放行的相交区间，须用户确认（overlap_waived=1）或标 overlap_pending=1 送对账队列')
  FROM time_entry e
  WHERE e.exclusivity_key = NEW.exclusivity_key
    AND e.id <> NEW.id
    AND e.overlap_waived = 0 AND e.overlap_pending = 0
    AND e.status NOT IN ('aborted','superseded')
    AND substr(e.started_at,1,19) < substr(NEW.ended_at,1,19)
    AND substr(NEW.started_at,1,19) < substr(COALESCE(e.ended_at,'9999'),1,19);
END;
