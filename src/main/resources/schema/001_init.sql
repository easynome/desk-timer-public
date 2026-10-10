-- schema_version 1.0.0 -> PRAGMA user_version = 10000
-- 只加列不改列不删列；本文件一旦发布即冻结，改结构请新增 00N_*.sql 并升 user_version。

CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE project (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  archived   INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE tag (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);

CREATE TABLE time_entry (
  id                TEXT PRIMARY KEY,
  kind              TEXT NOT NULL CHECK (kind IN
                      ('count_up','count_down','pomodoro_work','rest_short','rest_long')),
  status            TEXT NOT NULL CHECK (status IN
                      ('running','paused','finished','aborted','interrupted','superseded')),
  origin            TEXT NOT NULL DEFAULT 'timer' CHECK (origin IN
                      ('timer','manual_backfill')),
  task              TEXT NOT NULL,
  project_id        TEXT REFERENCES project(id) ON DELETE SET NULL,
  exclusivity_key   TEXT NOT NULL,
  started_at        TEXT NOT NULL,
  ended_at          TEXT,
  duration_ms       INTEGER,
  end_precision     TEXT CHECK (end_precision IN ('user','heartbeat')),
  last_heartbeat_at TEXT,
  checkin_date      TEXT NOT NULL,
  aborted_reason    TEXT,
  cause             TEXT CHECK (cause IN ('suspend','crash','power_loss')),
  missed            INTEGER NOT NULL DEFAULT 0,
  needs_review      INTEGER NOT NULL DEFAULT 0,
  overlap_waived    INTEGER NOT NULL DEFAULT 0,
  pomodoro_round    INTEGER,
  superseded_by     TEXT REFERENCES time_entry(id) ON DELETE SET NULL,
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL
);

CREATE INDEX idx_entry_date    ON time_entry(checkin_date, started_at);
CREATE INDEX idx_entry_key_int ON time_entry(exclusivity_key, started_at, ended_at);
CREATE INDEX idx_entry_status  ON time_entry(status);

-- 兜底：只拦"已闭合区间"的静默重叠。进行中条目的右端是"当下"，触发器判不准，
-- 主判据在应用层事务内（S5）。用户确认后写 overlap_waived=1 才放行。
CREATE TRIGGER trg_no_silent_overlap_insert
BEFORE INSERT ON time_entry
WHEN NEW.overlap_waived = 0 AND NEW.ended_at IS NOT NULL
     AND NEW.status NOT IN ('aborted','superseded')
BEGIN
  SELECT RAISE(ABORT, 'overlap: 同 exclusivity_key 已有相交区间，需用户确认后写 overlap_waived=1')
  FROM time_entry e
  WHERE e.exclusivity_key = NEW.exclusivity_key
    AND e.id <> NEW.id
    AND e.status NOT IN ('aborted','superseded')
    AND substr(e.started_at,1,19) < substr(NEW.ended_at,1,19)
    AND substr(NEW.started_at,1,19) < substr(COALESCE(e.ended_at,'9999'),1,19);
END;

CREATE TABLE entry_tag (
  entry_id TEXT NOT NULL REFERENCES time_entry(id) ON DELETE CASCADE,
  tag_id   TEXT NOT NULL REFERENCES tag(id)        ON DELETE CASCADE,
  PRIMARY KEY (entry_id, tag_id)
);

CREATE TABLE note (
  id           TEXT PRIMARY KEY,
  body_md      TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  checkin_date TEXT NOT NULL,
  entry_id     TEXT REFERENCES time_entry(id) ON DELETE SET NULL,
  project_id   TEXT REFERENCES project(id)    ON DELETE SET NULL,
  pinned       INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL
);
CREATE INDEX idx_note_date ON note(checkin_date, created_at);

CREATE TABLE note_tag (
  note_id TEXT REFERENCES note(id) ON DELETE CASCADE,
  tag_id  TEXT REFERENCES tag(id)  ON DELETE CASCADE,
  PRIMARY KEY (note_id, tag_id)
);

CREATE TABLE todo (
  id           TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  done         INTEGER NOT NULL DEFAULT 0,
  completed_at TEXT,
  checkin_date TEXT,
  project_id   TEXT REFERENCES project(id) ON DELETE SET NULL,
  pinned       INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);

CREATE TABLE todo_tag (
  todo_id TEXT REFERENCES todo(id) ON DELETE CASCADE,
  tag_id  TEXT REFERENCES tag(id)  ON DELETE CASCADE,
  PRIMARY KEY (todo_id, tag_id)
);

CREATE TABLE todo_entry (
  todo_id   TEXT REFERENCES todo(id)      ON DELETE CASCADE,
  entry_id  TEXT REFERENCES time_entry(id) ON DELETE CASCADE,
  linked_at TEXT NOT NULL,
  PRIMARY KEY (todo_id, entry_id)
);

CREATE TABLE setting (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
