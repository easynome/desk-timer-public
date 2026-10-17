# desk-timer 设计文档（T-01 产出）

> 2026-10-16，Codex 核对：本文保留初始设计与线框，不代表当前完成清单。v1.2 现状见 [需求实现状态](REQUIREMENTS_STATUS.md) 与 [本轮范围](NEXT_VERSION.md)：CLI 暂缓、托盘闪烁按当前需求取消；任务/时间线、紧凑模式、音效和休息保护已进入实现。提醒和启动恢复应按后续变更后的口径理解。

依据 `docs/prompts/v2-正式.md` v2.5。标记：`[已确认]` 用户写明 / `[假设]` 我定并给理由 / `[待确认]` 需用户裁。
第 5 节 15 条产品规则已在 `docs/dev/decisions.md` 留痕，本文不重述其理由，只做落地设计。

---

## 1. PRD

### 1.1 用户故事（含失败场景）

| # | 故事 | 失败场景必须覆盖 |
|---|---|---|
| US-1 | 我按 `Ctrl+Alt+T`，输入 `25m 写周报 #工作` 回车，窗口消失，托盘图标开始转。全程 < 3 秒，我不碰鼠标。 | 快捷键被占（→ 托盘异常态 + 下次唤起时在面板顶部提示改键，**不弹系统通知**）；解析失败（→ 整串当任务名开默认时长 + `needs_review`） |
| US-2 | 凌晨 01:12 我计了一段 40 分钟，第二天早上看导出数据，这段归到**前一天**，和 `checkin/*.md` 对得上。 | 跨 08:00 边界、跨日、改系统时间 |
| US-3 | 我合盖走人，晚上回来打开机器，应用告诉我「写周报 14:00 开始，最后记录到 14:24」，我确认或改时间，不弹窗打断我开机。 | `suspend` 恢复、`crash` 恢复、我直接关掉那条 |
| US-4 | 番茄结束时我在放全屏视频，应用不抢焦点、不弹窗口，只响提示音并把托盘变红；下次我唤起应用时它顶在最前面告诉我有一条未确认。 | 全屏压制、`missed=true`、对账队列非阻塞 |
| US-5 | 我并行开「方案评审」和「回邮件」两段计时，属不同项目 → 放行；同项目开两段 → 提示我确认，确认后仍能并行。 | `exclusivity_key` 警告可放行、禁止静默重叠 |
| US-6 | 可视化工具只读 `exports\`，某次我改了字段，它报错而不是画出一张错图。 | `schema_version` 不认即拒读 |

`[已确认]`

### 1.2 功能优先级

| 优先级 | 功能 |
|---|---|
| P0 | 捕获窗 + 确定性解析、单计时（正/倒/番茄）、心跳与对账、SQLite、导出（JSONL/CSV/manifest）、托盘 + 全局快捷键、四级打扰 L0-L3 |
| P1 | 随手记、钉住、暗亮色、开机自启、待办、多计时器并行、inbox 与 CLI、备份 zip |
| P2 | 只读 SQLite 副本、localhost API、历史条目编辑 UI、标签管理 UI |

### 1.3 MVP 切片：三个都能日常使用的关口

| 关口 | 进入条件 | 退出条件（我能用它过一天） | 保不住时砍什么 |
|---|---|---|---|
| **M0** | T-02/T-03/T-04/T-06 四项 spike 有实测结论（**T-05 通知已随 Q4 裁决取消**） | 快捷键唤起 → 解析 → 计时（含番茄，仅单并发）→ 心跳对账可用 → `exports\` 里 `time-entries.jsonl` + `manifest.json` 能被独立 Node 脚本正确解析，且 08:00 归日有单测 | 砍暗亮色、砍设置页（配置走 `config.json`）；**不砍导出与对账** |
| **M1** | M0 退出条件达成且导出契约已冻结 | 番茄钟（25/40 + 短长休 + 自动钉住）、随手记（搜索/标签/置顶）、四级打扰含全屏检测、开机自启、**一键备份 zip（Q8 已从 M2 提前）** | 砍随手记的标签与置顶（只留搜索）；**不砍全屏检测**（它决定 L3 是否可靠）；**不砍备份**（数据只有一份，`timer.db` 坏了就是全丢） |
| **M2** | M1 稳定 ≥ 3 天日常使用 | 多计时器 + `exclusivity_key` 警告、待办（含两个完成动作与关联）、补录 UI 完善、备份 zip、CLI 四条 | 砍待办与计时的关联 **UI**（字段保留）；**不砍多计时器的重叠警告**（静默重叠毁数据） |

`[已确认]`：切片边界按用户 v2.5 第 7.1 条（M0 含补录，M1 番茄+随手记，M2 多计时+待办）。
**差异说明**：用户原话把番茄钟放 M1，但番茄钟的自动钉住/长休依赖打扰分级，M0 先只上「单段倒计时 + 放弃」，番茄语义（轮次计数、长短休编排）留 M1。`[假设]`

**如果只能记住一条**：M0 的交付物不是 UI，是**能被第三方脚本正确解析的导出数据 + 崩溃后不撒谎的时间条目**。

---

### 1.4 打扰阶梯（Q4 裁决后重排）

| 级别 | 原定义（v2.5 第 5 节第 7 条） | Q4 裁决后 |
|---|---|---|
| L0 | 静默 | 不变：只写库，不给任何反馈 |
| L1 | 托盘提示 | 托盘图标换色（常态 → 有未确认结束），无声 |
| L2 | **系统通知** | **提示音 + 托盘图标闪烁**（系统通知位被撤，声音成为 L2 唯一载体）`[假设]` 载体由我定 |
| L3 | 弹窗 | **应用内窗口**（计时面板 / 对账卡 / 改键提示），会抢焦点 ⇒ 仅用户主动唤起、阻塞性错误、下一次非全屏时的对账呈现 |

- 降级链不变：全屏或勿扰时 L3→L2、L2→L1、L1→L0。
- 原规则「声音是否保留由用户单独配置」正好接上新 L2：全屏/勿扰下是否仍响提示音由 `sound_in_fullscreen` 单独决定，**默认关**。
- `missed` 语义保留：L2/L3 未能呈现（用户不在、或全屏且静音）时条目落 `missed=true`，下次唤起顶置。
- 连带改动：快捷键冲突提示原设计依赖"可点击的系统通知"，现改为**托盘异常态 + 下次唤起时面板顶部提示条**（US-1 已同步）。

**如果只能记住一条**：撤掉系统通知后，「用户当时没看见」从边缘情况变成常态，`missed` 与托盘顶置队列是**唯一兜底**，必须按主路径实现而不是错误分支。

## 2. 信息架构与 UI

六个界面，M0 只需要前三个（★）。

### 2.1 ★ 快速捕获窗（全局快捷键唤起，无边框、置顶、失焦即隐）

```text
┌──────────────────────────────────────────────┐
│ 25m 写周报 #工作 @Portal              █      │  ← 单行输入，光标即焦点，IME 友好
├──────────────────────────────────────────────┤
│ ✓ 25:00 倒计时 · 写周报 · 工作 · Portal      │  ← 解析回显，边打字边变
│ Enter 开始 · Esc 关 · ⌥Enter 立即开始并隐藏   │
└──────────────────────────────────────────────┘
```

键盘序列：`Ctrl+Alt+T` → 输入 → `Enter`。鼠标不参与。解析失败时回显变 `⚠ 未识别时长，将按 25 分钟开始，任务名=整串`，`Enter` 仍可走。

### 2.2 ★ 计时面板（可钉住；未钉住失焦即隐）

```text
┌─────────────────────────────────── 📌 ─┐
│ 写周报            00:12:30 / 00:25:00  │
│ ███████████░░░░░░░░░░  50%             │
│ 暂停/继续(空格) 结束(E) 放弃(X) 加 5m(+)│
│ 另开一段(N) · 钉住(P) · 随手记(M)       │
└────────────────────────────────────────┘
```

### 2.3 ★ 托盘菜单

```text
左键：唤起捕获窗        右键：菜单
├ 当前计时：写周报 12:30 │ 结束 E
├ ⚠ 待对账 1 条（顶置）  │ ← missed / interrupted 只在这里排队，不弹窗
├ 勿扰：关 (D)
├ 打开主窗口
└ 退出 (Q)
```

### 2.4 主窗口（M1）｜ 时间线（M1，只列条目**不算总和**）｜ 待办页（M2）｜ 设置页（M1，含快捷键改键、两窗失焦行为、提示音、钉住默认值、备份、重置窗口位置）

线框从略（v2.5 §8：篇幅不足砍 UI 不砍契约）。

**如果只能记住一条**：任何需要鼠标的操作都不是主路径；对账、`missed`、快捷键冲突一律走托盘排队，不抢焦点。

---

## 3. 数据模型与 SQLite schema 草案

时区策略 `[假设]`：全部按**系统本地时区**读写，ISO 8601 带偏移（`+08:00`）；`checkin_date` 在写入时一次算定、之后不改（否则历史会被时区改动重排）。

```sql
PRAGMA user_version = 10000;              -- schema_version 1.0.0 -> 10000
PRAGMA foreign_keys = ON;                 -- 连接级必设，SQLite 默认不开

CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);   -- schema_version / timer_home / created_at，导出器从这里读版本

CREATE TABLE project (
  id         TEXT PRIMARY KEY,             -- UUID v4
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
  id                TEXT PRIMARY KEY,                       -- 导出幂等键
  kind              TEXT NOT NULL CHECK (kind IN
                      ('count_up','count_down','pomodoro_work','rest_short','rest_long')),
  status            TEXT NOT NULL CHECK (status IN
                      ('running','paused','finished','aborted','interrupted','superseded')),
  origin            TEXT NOT NULL DEFAULT 'timer' CHECK (origin IN
                      ('timer','manual_backfill')),
  task              TEXT NOT NULL,
  project_id        TEXT REFERENCES project(id) ON DELETE SET NULL,
  exclusivity_key   TEXT NOT NULL,                          -- project_id 或 'unassigned'
  started_at        TEXT NOT NULL,                          -- ISO8601+偏移，墙上时间
  ended_at          TEXT,                                   -- NULL = 进行中
  duration_ms       INTEGER,                                -- 单调时钟算出，非 end-start 之差
  end_precision     TEXT CHECK (end_precision IN ('user','heartbeat')),
  last_heartbeat_at TEXT,
  checkin_date      TEXT NOT NULL,                          -- YYYY-MM-DD，08:00 归日
  aborted_reason    TEXT,
  cause             TEXT CHECK (cause IN ('suspend','crash','power_loss')),
  missed            INTEGER NOT NULL DEFAULT 0,
  needs_review      INTEGER NOT NULL DEFAULT 0,
  overlap_waived    INTEGER NOT NULL DEFAULT 0,             -- 1 = 用户已确认可并行
  pomodoro_round    INTEGER,
  superseded_by     TEXT REFERENCES time_entry(id) ON DELETE SET NULL,
  created_at        TEXT NOT NULL,
  updated_at        TEXT NOT NULL
);
CREATE INDEX idx_entry_date    ON time_entry(checkin_date, started_at);
CREATE INDEX idx_entry_key_int ON time_entry(exclusivity_key, started_at, ended_at);
CREATE INDEX idx_entry_status  ON time_entry(status);

CREATE TABLE entry_tag (
  entry_id TEXT NOT NULL REFERENCES time_entry(id) ON DELETE CASCADE,
  tag_id   TEXT NOT NULL REFERENCES tag(id)        ON DELETE CASCADE,
  PRIMARY KEY (entry_id, tag_id)
);

CREATE TABLE note (
  id           TEXT PRIMARY KEY,
  body_md      TEXT NOT NULL,
  created_at   TEXT NOT NULL,          -- 时间轴必填：不允许无时间戳笔记
  checkin_date TEXT NOT NULL,
  entry_id     TEXT REFERENCES time_entry(id) ON DELETE SET NULL,
  project_id   TEXT REFERENCES project(id)    ON DELETE SET NULL,
  pinned       INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL
);
CREATE INDEX idx_note_date ON note(checkin_date, created_at);

CREATE TABLE note_tag (note_id TEXT REFERENCES note(id) ON DELETE CASCADE,
  tag_id TEXT REFERENCES tag(id) ON DELETE CASCADE, PRIMARY KEY (note_id, tag_id));

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

CREATE TABLE todo_tag (todo_id TEXT REFERENCES todo(id) ON DELETE CASCADE,
  tag_id TEXT REFERENCES tag(id) ON DELETE CASCADE, PRIMARY KEY (todo_id, tag_id));

CREATE TABLE todo_entry (               -- 待办与计时的关联，多对多
  todo_id  TEXT REFERENCES todo(id) ON DELETE CASCADE,
  entry_id TEXT REFERENCES time_entry(id) ON DELETE CASCADE,
  linked_at TEXT NOT NULL,
  PRIMARY KEY (todo_id, entry_id)
);

CREATE TABLE setting (                  -- 钉住状态、勿扰开关、窗口指纹等
  key   TEXT PRIMARY KEY,               -- 例 ui.pinned.timer_panel = 1
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

### 3.1 区间重叠：应用层为主，触发器只兜闭区间

`[已确认]` 事实级约束：SQLite 无 `EXCLUDE`，且「进行中」条目的 `ended_at` 为 NULL（区间右端是"当下"），触发器无法可靠判定。

- **应用层**（主）：保存前在同一事务内查 `exclusivity_key` 相同、`status NOT IN ('aborted','superseded')`、区间相交的行；命中则走 UI 确认，用户放行才写 `overlap_waived=1`。
- **触发器**（兜底，只拦已闭合区间的静默写入）：

```sql
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
```

`[假设]` 字符串比较可用是因为同一偏移量的 ISO 8601 字典序等于时间序；跨偏移（出差到别的时区）会失真 → 用 `substr(...,1,19)` 截到秒并统一按本地比较，且**新增列存 epoch 毫秒**留作以后修正的余地，见 3.2。

### 3.2 迁移样例（只加列不改列）

```sql
-- 目标：把 duration_ms 变成必填。SQLite 不能 ALTER COLUMN 加 NOT NULL。
BEGIN;
CREATE TABLE time_entry_new ( ... , duration_ms INTEGER NOT NULL DEFAULT 0);   -- 新表定义 = 新 schema
INSERT INTO time_entry_new (id, ..., duration_ms)
  SELECT id, ..., COALESCE(duration_ms, 0) FROM time_entry;                     -- 旧行补 0 并留痕
DROP TABLE time_entry; ALTER TABLE time_entry_new RENAME TO time_entry;
UPDATE meta SET value='10100' WHERE key='schema_version';
COMMIT;
-- 导出侧兼容：1.0.0 与 1.1.0 的字段字典都列在 manifest.json，消费方按版本选白名单。
```

### 3.3 状态机（`time_entry.status`）

```text
running ──pause──> paused ──resume──> running
   │                   │
   │ end(E)            │ finish(X-确认后)         abort
   ▼                   ▼                             ▼
 finished        finished(end_precision=user)   aborted(+aborted_reason)
   ▲
   └─ 心跳恢复：重启时 running/paused 且无 ended_at ⇒ interrupted(cause) → 对账队列
        ├ 确认/改时间 → finished(end_precision=user|heartbeat)
        └ 丢弃 → aborted(aborted_reason='discarded_on_recovery')
   合并/拆分 → 原行 status=superseded, superseded_by=<新行id>；导出只出非 superseded
```

**如果只能记住一条**：`duration_ms` 来自单调时钟、`started_at/ended_at` 来自墙上时间，两者**不同源**，别在导出里用后者算前者。

---

## 4. 互操作方案

### 4.1 目录（`TIMER_HOME` 默认 `D:\timer-data`）

```text
D:\timer-data\
  db\timer.db(+ -wal / -shm)         只有应用进程打开
  exports\manifest.json              提交点：一轮导出是否完整
  exports\time-entries.jsonl         主数据，消费方首选
  exports\time-entries.csv           无 BOM，给脚本
  exports\time-entries.excel.csv     带 BOM，给 Excel
  exports\notes.jsonl  todos.jsonl
  exports\daily-2026-10.md           人读，只列条目不算总和
  exports\inbox.jsonl                CLI 写入口（应用吸收后原样归档到 inbox.archive.jsonl）
  logs\timer-2026-10-06.log
  backup\timer-backup-20261006T143102.zip
```

### 4.2 原子导出协议

1. 全部产物写到同目录 `*.tmp`；
2. 逐个 `rename` 覆盖正式名（除 manifest）；
3. **最后**写并 rename `manifest.json` ⇒ 消费方以 manifest 出现为"这轮完整"的唯一信号；
4. `rows` 与 `sha256` 逐文件校验；`generated_at` 单调，重复导出内容相同则 `rows/sha256` 不变，幂等键 = `entry_id`。

### 4.3 `manifest.json`（真实可解析）

```json
{
  "schema_version": "1.0.0",
  "app_version": "0.1.0",
  "generated_at": "2026-10-06T14:31:02+08:00",
  "timer_home": "D:/timer-data",
  "day_attribution": {"rule": "local_08:00", "note": "本地 08:00 前的记录归前一日"},
  "files": [
    {"name": "time-entries.jsonl", "kind": "time_entries", "rows": 183,
     "sha256": "9f2c1d...e7", "updated_at": "2026-10-06T14:31:02+08:00"}
  ],
  "fields": {"time_entries": ["entry_id","kind","status","origin","task","project","tags",
    "exclusivity_key","checkin_date","started_at","ended_at","duration_ms","end_precision",
    "missed","needs_review","overlap_waived","aborted_reason","cause","pomodoro_round","schema_version"]}
}
```

### 4.4 `time-entries.jsonl` 示例行（一行一条，UTF-8，无 BOM）

```json
{"entry_id":"01948ea1-6c2f-7a11-9bd3-5c9d2e7f1a44","kind":"count_down","status":"finished","origin":"timer","task":"写周报","project":null,"tags":["工作"],"exclusivity_key":"unassigned","checkin_date":"2026-10-05","started_at":"2026-10-06T01:12:03+08:00","ended_at":"2026-10-06T01:37:48+08:00","duration_ms":1545000,"end_precision":"user","missed":false,"needs_review":false,"overlap_waived":false,"aborted_reason":null,"cause":null,"pomodoro_round":null,"schema_version":"1.0.0"}
{"entry_id":"01948f02-11aa-7c3b-8f21-4d7c9b0e2a55","kind":"pomodoro_work","status":"interrupted","origin":"timer","task":"方案评审","project":"Portal","tags":["评审"],"exclusivity_key":"prj:Portal","checkin_date":"2026-10-06","started_at":"2026-10-06T14:00:00+08:00","ended_at":null,"duration_ms":null,"end_precision":null,"missed":false,"needs_review":true,"overlap_waived":false,"aborted_reason":null,"cause":"suspend","pomodoro_round":1,"schema_version":"1.0.0"}
```

第 1 条就是跨 08:00 的例子：`started_at` 在 10-06 凌晨 01:12，但 `checkin_date` 是 **10-05**。`[已确认]`

字段字典（稳定性）：`entry_id/kind/status/origin/task/checkin_date/started_at/ended_at/duration_ms` = **冻结**（只加不改）；`project/tags/exclusivity_key` = 稳定；`end_precision/missed/needs_review/overlap_waived/cause/aborted_reason/pomodoro_round` = 可空可增。

### 4.5 CSV / Markdown 样例

```text
# time-entries.csv（无 BOM，给脚本）
entry_id,kind,status,origin,task,project,checkin_date,started_at,ended_at,duration_ms,end_precision
01948ea1-...,count_down,finished,timer,写周报,,2026-10-05,2026-10-06T01:12:03+08:00,2026-10-06T01:37:48+08:00,1545000,user
```

```markdown
<!-- daily-2026-10.md，只列条目，任何求和都由消费方算 -->
## 2026-10-05
- 01:12–01:37 写周报（倒计时 · 25 分档 · 用户确认结束）
```

`[假设]` CSV 双变体保留：只给一种就会在"脚本要无 BOM / Excel 要带 BOM"之间反复横跳，代价是多一个 20 行导出分支，远小于返工。若嫌通道多，先砍 `time-entries.excel.csv`。

### 4.6 CLI 四条（`timer.cmd` → `jbang`/jar，读走只连、写走 inbox）

```text
timer export --full
  → 强制重导全部 exports\ 产物；stdout: "exported 183 entries -> D:/timer-data/exports (sha256 9f2c1d...)"
timer entries --since=2026-10-01 --until=2026-10-06 [--format jsonl|csv]
  → 只读打开 db（immutable/readonly 连接），stdout 每行一条 entry；应用正在跑也能读（WAL 多读）
timer note "刚调完分块参数，要点：overlap 为什么取 50" [--tag 学习] [--project Portal]
  → 追加一行到 exports\inbox.jsonl：{"op":"note","text":"...","client":"cli","ts":"..."}；stdout 回执 op_id
timer start "25m 写周报 #工作"
  → 同上写 inbox {"op":"start","expr":"..."}；应用未运行则条目停在 inbox，下次启动吸收并在条目上标 needs_review=1
```

单写者规则 `[已确认]`：CLI 永不打开 `timer.db` 写连接。`timer entries` 用只读 URI（`file:...?mode=ro`）。

### 4.7 localhost API（P2，默认关闭）

```text
配置开启：setting.api_enabled=1 + setting.api_token=<随机串>
绑定：仅 127.0.0.1，端口 47811 起冲突递增
GET /healthz              -> {"ok":true,"schema_version":"1.0.0","rows":183}
GET /v1/entries?since=&project=   -> {"entries":[...]}    只读，等价 timer entries
GET /v1/manifest          -> manifest.json 内容
鉴权：Authorization: Bearer <api_token>；无写端点
```

**如果只能记住一条**：`manifest.json` 是提交点，字段字典在 manifest 里，不认的版本直接报错——静默兼容是这条链上唯一的真危险。

---

## 5. 选型校验与 spike 计划

### 5.1 主方案硬门槛判定（**除标注"实测"外全为推断，spike 前不得当依据**）

| # | 门槛 | JavaFX 判定 | 坑与绕法 |
|---|---|---|---|
| 1 | 全局快捷键 | 有已知坑 | JavaFX 无内置。JNativeHook 2.x：全局钩子稳但带 dll + 一个线程；JNA + `RegisterHotKey` 更轻但需要窗口句柄与消息循环。**spike T-03 二选一实测** |
| 2 | 托盘 + 菜单 | 有已知坑 | 用 AWT `SystemTray`（JavaFX 无托盘 API）。坑：图标在 Win11 风格托盘可能被折叠；16×16/24×24 双尺寸资源；`SystemTray` 与 JavaFX 工具箱并存需在同一 EDT/AWT 线程调 |
| 3 | 无边框 + 置顶 + 失焦隐藏 + 150% DPI | 有已知坑 | `stage.initStyle(TRANSPARENT)` + `setAlwaysOnTop(true)` + `focusedProperty` 监听。坑：TRANSPARENT 窗失焦判定与 `isShowing` 交互；DPI 变更后 `Screen` 坐标需重算（见 §5.3） |
| 4 | Win10 通知带应用名/图标 | **已裁决不做** | Q4 裁决：不弹系统通知。原绕法 (b)(c)（PowerShell/JNA 调 WinRT + HKCU 写 AUMID）全部作废，T-05 取消；结束事件改由 L2 = 提示音 + 托盘闪烁承载 |
| 5 | 开机自启（免管理员） | 已验证可行（机制层面） | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 写一行，用户级、不需 UAC。不用启动文件夹以外的机器级路径 |
| 6 | 单实例锁 | 已验证可行（机制层面） | `Timer_HOME` 下 `.lock` 文件 + `FileChannel.tryLock()`；进程退出锁自动释放 |
| 7 | SQLite 零原生编译 | 已验证 | `sqlite-jdbc` jar 内带原生库并自动解到临时目录，无 node-gyp 类问题（**本机已在本地 Maven 仓库实测存在**） |
| 8 | 完全离线 | 已验证可行 | 依赖只在构建期下载；运行期无任何网络调用（解析器为本地正则集） |
| 9 | 便携包 | 已验证可行（机制层面） | `jpackage --type app-image` 出目录，不需 WiX、不写注册表（自启除外） |
| 10 | **内存 ≤ 200 MB 空闲驻留** | **未知，最高否决风险** | 构成：JVM + Prism/D3D 上下文 + 隐藏窗 scene。手段：`-Xmx256m` → jlink 裁模块（`javafx.graphics` 必需，`javafx.fxml`/`controls` 可按需）→ 隐藏时销毁 scene → `-Dprism.order=sw`（动画变糙）。**T-02 实测** |

### 5.2 主方案最让我后悔的点

第 3–4 周：托盘与窗口的"系统外壳"能力仍要靠 AWT/JavaFX 双工具箱拼装（通知项已随 Q4 取消，风险面缩小），改一个状态要跨两个工具箱，而它们不在我的 Java 舒适区里 —— 我的 Java 经验是 Spring Boot 后端，桌面外壳是另一套知识。

### 5.3 spike 计划（2 小时，按否决风险从高到低）

| 顺序 | 做什么 | 通过判据 | 失败后 |
|---|---|---|---|
| **T-02 内存** | 起最小 JavaFX 工程：一个隐藏 `Stage` + AWT 托盘 + `-Xmx256m`；驻留 10 分钟不操作 | `Get-Process -Name <exe>` 的 `WorkingSet64/1MB` ≤ 200；开合窗 20 次后仍 ≤ 200 | > 250 ⇒ 依次试 jlink、销毁 scene、`prism.order=sw`；仍 > 250 ⇒ **切降级 ① C#/.NET 8 WPF**（Electron 排在它之后，因为内存与 SQLite 两条都不占优） |
| **T-03 快捷键** | 同一小工程分别接 JNativeHook 与 JNA `RegisterHotKey`，把焦点切到记事本按 `Ctrl+Alt+T` | 日志出现 `HOTKEY_HIT`；被占用时能拿到注册失败并识别占用方（拿不到占用方名字算部分通过） | 两者都挂 ⇒ 保留托盘双击唤起 + 应用内快捷键，全局键移到 P2 |
| **T-04 窗口** | TRANSPARENT + alwaysOnTop + 失焦隐藏；125%/150% 切换、拖到副屏、拔屏 | 切走即隐、置顶不被覆盖、缩放变更后窗体完整且在屏内 | 若 TRANSPARENT 焦点判定不可靠 ⇒ 退回带极小边框的 `UNDECORATED`，视觉可接受 |
| **T-05 通知** | ~~三法对比~~ **已取消（Q4 裁决：不弹系统通知）** | — | 不需要后备：L2 已改为提示音 + 托盘闪烁 |
| **T-06 存储** | sqlite-jdbc：`UPDATE last_heartbeat_at` 每 30 s、WAL、`.tmp`+rename 导出、08:00 归日函数；跑一次 `kill` 强杀再重启 | 重启能对账（读到最后心跳）；`node docs/verify-contract.mjs` 通过（可解析、字段齐、归日对、无静默重叠） | 心跳与 WAL 交互出问题 ⇒ 降 `synchronous=NORMAL` + 缩短心跳到 15 s；驱动层问题基本不可能 |

`[假设]` 内存基线未实测，spike 前 200 MB 只是判据不是承诺。

### 5.4 推翻条件与切换代价

- T-02 失败 → 切 WPF：UI（FXML→XAML）、平台适配层重写；**schema、导出器契约、解析器规则、对账状态机不变**（这正是 §3–4 与语言无关的理由）。预计重写 3 天。
- ~~T-05 通知~~ 已随 Q4 裁决取消 ⇒ 栈级否决项少一个；剩下只有 T-02（已过）与 T-04b（非 100% 缩放，需人工）。
- T-03 失败 → 不切栈，砍全局快捷键，保留托盘唤起，捕获体验降级。
- T-06 失败 → 唯一可能推翻"文件契约"设计的项，届时重开 §4。

**如果只能记住一条**：先测内存（T-02），它一条就能否掉整个 JavaFX 方案；别先测有趣的快捷键。

---

## 6. 开发计划（单主控串行）

工时折算按每天实际可用 **2–3 小时**（状态差 60–90 分钟）；下表"预计"单位是这个口径下的会话数，不是小时。

| 模块 | 依赖 | 产出物 | 验收命令 | 一次会话能否做完 |
|---|---|---|---|---|
| S1 spike 工程骨架（Maven + OpenJFX + jlink profile） | 无 | `spike/pom.xml`、`Spike.java`、**更新 docs/dev/todo.md + progress.md** | `mvn -q -f spike/pom.xml package` 出 jar | 是 |
| S2 内存实测 | S1 | spike 启动参数 + **更新 freeze.md/decisions.md**（记录实测值与选型结论） | T-02 判据（`Get-Process` 三次采样） | 否 → 拆 S2a 采样脚本(1会话)、S2b jlink 与参数调优(第 2 会话，可能要 3 轮) |
| S3 快捷键 + 托盘适配层（`PlatformAdapter` 接口） | S2 定案 | `adapter/win/...`、**更新 todo/freeze** | T-03 判据 + 手动 | 否 → 拆 S3a 快捷键、S3b 托盘菜单 |
| S4 时间内核（状态机 + 心跳 + 单调/墙上分工 + 归日） | S1 | `core/time/...`、单测、**更新 freeze.md（接口冻结）** | `mvn -q test`；`node docs/verify-contract.mjs --fixture core` | 否 → 拆 S4a 状态机与心跳、S4b 归日与对账判定 |
| S5 存储层（schema 迁移 + repository + overlap 检查） | S4 | `db/`、迁移脚本、**更新 freeze.md** | `mvn -q test` + 手工强杀重启 | 否 → 拆 S5a schema/迁移、S5b repository/overlap |
| S6 导出器（JSONL/CSV/manifest + 原子提交 + inbox 吸收） | S5 | `export/`、`docs/verify-contract.mjs`(Node) | `node docs/verify-contract.mjs` 全绿 | 是 |
| S7 确定性解析器（`25m 写周报 #工作 @项目`） | 无 | `core/parse/`、语料表 + 单测、**更新 freeze.md（语法冻结）** | `mvn -q test -Dtest=ParserTest` | 是 |
| S8 捕获窗 + 计时面板（最小 UI） | S3,S4,S7 | `ui/`、**更新 todo** | 手动：快捷键 → 输入 → 计时 → 面板显示 | 否 → 拆 S8a 捕获窗、S8b 计时面板 |
| S9 对账队列（托盘顶置 + 非阻塞确认） | S4,S5 | `ui/reconcile/`、**更新 progress** | 手动强杀进程 → 重启 → 条目排队且可确认/丢弃 | 是 |
| S10 M0 便携打包 | S8,S9 | `jpackage` 脚本、README 启动说明 | 拷到 `D:\timer-app\` 双击起，`Get-Process` 复测内存 | 否 → 拆 S10a 打包、S10b 体积/内存复测 |
| M1 番茄 + 随手记 + 打扰分级 + 自启 | M0 冻结 | 见 todo 后续追加行 | 同上口径 | 第 2 阶段，M0 后拆 |
| M2 多计时 + 待办 + 备份 zip | M1 | 同上 | 同上 | 第 3 阶段 |

**契约一致性测试**（`docs/verify-contract.mjs`，S6 落地，独立于应用）：断言 ① `manifest.json` 可解析且 `schema_version` 在白名单内；② 每个 `files[]` 的 `rows` 与实际行数、`sha256` 与内容一致；③ 每行 JSONL 必需字段齐全、`started_at < ended_at`（或 `ended_at=null`）；④ `checkin_date` 与 `started_at` 按 08:00 规则自洽；⑤ 同 `exclusivity_key` 且 `overlap_waived=0` 的区间两两不相交；⑥ 导出中不出现 `status=superseded`。

### 6.1 前 5 个技术风险与早期信号

| 风险 | 早期可观测信号 |
|---|---|
| 内存压不进 200 MB | S2 首次采样即知；>220 MB 且 jlink 后不降 = 定局 |
| JavaFX/AWT 双工具箱打架（托盘、焦点、EDT） | S3 里快捷键回调能否安全触发 FX 线程动作；偶发 `IllegalStateException` |
| 08:00 归日与消费方口径不一致 | S4b 单测；`verify-contract.mjs` 第 ④ 项红 |
| 崩溃恢复产生重叠或丢区间 | S5/S9：强杀 20 次脚本化，检查是否出现相交或 `duration_ms` 为负 |
| 导出通道太多、维护挤占开发 | 每次改动要同步 6 个产物；出现「CSV 与 JSONL 字段不一致」类 bug 即触发 §8 收敛 |

**如果只能记住一条**：顺序被刻意排成「先否掉栈、再碰数据、最后才画 UI」，S8 之前的任何一步失败都不浪费已完成模块。

---

## 7. 待我确认（≤8 条）

| # | 问题 | 默认方案 | 影响面 | 建议 |
|---|---|---|---|---|
| Q1 | 番茄钟是否进 M0 | M0 只做单段倒计时 + 放弃，番茄语义（轮次/长短休/自动钉住）在 M1 | M0 范围、S8 工作量 | 按默认：M0 先跑通"记录不撒谎"，编排放 M1 |
| Q2 | 时区与偏移处理 | 全按系统本地时区 + 固定 08:00 边界，字符串比较截到秒 | 归日正确性 | 保持；除非你会跨时区工作，那时加 epoch 毫秒列（§3.1 已留余地） |
| Q3 | `daily-*.md` 是否必要 | 保留（人读 + Markdown 消费者） | 导出通道数 | 若嫌多，先砍 `excel.csv`，`daily.md` 是 Typora 时代的习惯，砍了你大概会想它 |
| Q4 | ~~通知失败时是否接受纯托盘方案~~ **已裁（2026-10-06）：采纳降级，不弹系统通知** | 阶梯重排见 §1.4：L2 = 提示音 + 托盘闪烁 | 打扰模型、US-1 的改键提示 | 已定，无需再议 |
| Q5 | spike 失败切 WPF 你是否同意 | 同意，Electron 排在 WPF 之后 | 栈级决策 | 需要你点头，因为 WPF 意味着新语言 + 新构建链（.NET SDK 需装，可能要 UAC） |
| Q6 | 端口与 token（P2 API）现在是否要做 | 不做，字段与目录先留位 | 无 | 按第 5 节 15 条，本来就该砍 |
| Q7 | 标签是否需要独立管理 UI | 不做，标签在解析和随手记里就地创建 | M1 工作量 | 不做，SQLite 里 `tag` 表已够，管理页是 P2 |
| Q8 | ~~备份 zip 放 M2 还是更早~~ **已裁（2026-10-06）：提到 M1** | 见 §1.3 M1 行 | 数据丢失风险窗口 | 已定 |

---

## 8. 我要求了但我怀疑不该做的（v2.5 §8 要求）

| 现设计 | 怀疑点 | 收敛建议 |
|---|---|---|
| JSONL + CSV + Excel CSV + Markdown + 只读副本 + CLI + localhost API（7 个通道） | 通道数量 = 每次字段变更要同步的地方数量，最容易出「两个产物互相矛盾」 | **最小集：`time-entries.jsonl` + `manifest.json`**。CSV/Excel/Markdown 降为同一轮导出里的可选产物（默认关，配置开）；只读副本与 API 直接 P2 不做 |
| `exclusivity_key` 用 `project_id ?? 默认桶` | 没选项目的计时全落 `unassigned`，等于"默认允许一切重叠" | 保留，但捕获窗在没带 `@项目` 时回显显式提示"未归项目，将允许并行"，让它是个知情选择 |
| 触发器兜底 overlap | 字符串比较脆弱，可能误拦合法写入 | 保留但只在闭区间触发（当前设计即如此）；主判据在应用层，误报时优先信应用层 |

## 9. scope creep 清单（我替你默认加的，逐项否决）

1. `meta`/`setting` 两张键值表（你没要求；不装就得用外部 JSON，导出器要读两个权威源）。
2. 导出 `sha256` 与 `rows` 逐文件校验（你没要求；只写 `schema_version` 也能判版本，但判不出半截文件）。
3. `cause` 字段区分 `suspend/crash/power_loss`（你只说"作废并标记"；不区分则对账文案无法说人话）。
4. `pomodoro_round` 记在条目上（你没要求；不存则长休判定只能靠内存状态，重启即丢）。
5. `docs/verify-contract.mjs` 独立测试脚本（你要求了验收，但没指定它长什么样；这是新增文件，不在 `docs/dev/` 四文件上限内 —— 若你认为违规，我把它并入 S6 的模块代码里）。
6. 捕获窗回显解析结果那一行（你没要求；不回显则解析错了我不知道）。
7. S2 拆成 3 轮调优的预算（工时乐观通常是事故来源，不是功能膨胀）。
