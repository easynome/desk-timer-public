// 契约一致性测试：独立于应用，只读 exports/ 目录，不依赖应用源码、不要求应用在跑。
// 用法：node docs/verify-contract.mjs [exports目录]
import { createHash } from "node:crypto";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";

// 两级版本判据（2026-10-07 裁定）：major 不认就停；minor 更高就只读自己认识的 kind。
// 加 kind / 加可选列走 minor，改语义 / 删字段 / 可选变必填走 major。
const SUPPORTED_MAJOR = 1;
/** 本消费者认识的 kind：time_entries(1.0) + notes(1.1) + todos(1.2)。加一种就抬一格。 */
const SUPPORTED_MINOR = 2;
// 必需 kind 是**本消费者**的清单，绝不写进 manifest —— 生产者不替消费方决定什么必需。
const REQUIRED_KINDS = ["time_entries", "notes", "todos"];
const NOTE_REQUIRED = [
  "note_id", "body", "created_at", "checkin_date", "linked_entry_id", "project", "tags",
  "schema_version",
];
const TODO_REQUIRED = [
  "todo_id", "title", "done", "created_at", "completed_at", "checkin_date", "project", "tags",
  "linked_entry_ids", "schema_version",
];
const REQUIRED = [
  "entry_id", "kind", "status", "origin", "task", "exclusivity_key",
  "checkin_date", "started_at", "ended_at", "duration_ms", "end_precision",
  "missed", "needs_review", "overlap_waived", "schema_version",
];
const dir = process.argv[2] || "D:/timer-data/exports";
const errors = [];
const notes = [];

function fail(msg) {
  errors.push(msg);
}

/** 08:00 归日：本地 08:00 前的记录归前一天。输入是带偏移的 ISO 串，按偏移后的本地时刻判。 */
function expectCheckinDate(iso) {
  const offset = iso.slice(-6); // +08:00
  const wall = new Date(iso); // 同一瞬间
  const localMs = wall.getTime() + hoursOf(offset) * 3600000;
  const d = new Date(localMs);
  const hour = d.getUTCHours();
  const day = new Date(localMs - (hour < 8 ? 86400000 : 0));
  return day.toISOString().slice(0, 10);
}

function hoursOf(offset) {
  const sign = offset[0] === "-" ? -1 : 1;
  return sign * (Number(offset.slice(1, 3)) + Number(offset.slice(4, 6)) / 60);
}

const manifestPath = path.join(dir, "manifest.json");
if (!existsSync(manifestPath)) {
  console.log("CONTRACT_FAIL manifest.json 不存在：" + manifestPath);
  process.exit(1);
}
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const [major, minor] = String(manifest.schema_version ?? "").split(".").map(Number);
if (major !== SUPPORTED_MAJOR || Number.isNaN(minor)) {
  fail(`major 不认：schema_version=${manifest.schema_version}，本消费者只认 ${SUPPORTED_MAJOR}.x`);
} else if (minor > SUPPORTED_MINOR) {
  notes.push(`minor=${minor} 高于本消费者认识的 ${SUPPORTED_MINOR}：只读自己认识的 kind`);
}
const kinds = (manifest.files ?? []).map((f) => f.kind);
for (const k of REQUIRED_KINDS) {
  if (!kinds.includes(k)) {
    fail(`本消费者声明的必需 kind 缺失：${k}（manifest 只给了 ${kinds.join(",") || "空"}）`);
  }
}
if (manifest.day_attribution?.rule !== "local_08:00") {
  fail(`day_attribution.rule 不是 local_08:00：${JSON.stringify(manifest.day_attribution)}`);
}

for (const f of manifest.files ?? []) {
  const p = path.join(dir, f.name);
  if (!existsSync(p)) {
    fail(`${f.name} 在 manifest 里声明但文件不存在`);
    continue;
  }
  const buf = readFileSync(p);
  const sha = createHash("sha256").update(buf).digest("hex");
  if (sha !== f.sha256) {
    fail(`${f.name} sha256 不符：manifest=${f.sha256} 实际=${sha}`);
  }
  const lines = buf.toString("utf8").split("\n").filter((l) => l.trim() !== "");
  // rows 只属于 time_entries；其余产物声明 lines。一个字段名不许有两种语义。
  const declared = "rows" in f ? {key: "rows", value: f.rows}
      : ("lines" in f ? {key: "lines", value: f.lines} : null);
  if (!declared) {
    fail(`${f.name} 既没有 rows 也没有 lines，消费方无从判断完整性`);
  } else if (lines.length !== declared.value) {
    fail(`${f.name} ${declared.key} 不符：manifest=${declared.value} 实际=${lines.length}`);
  }
  if (f.kind === "notes") {
    for (const [i, line] of lines.entries()) {
      let n;
      try {
        n = JSON.parse(line);
      } catch {
        fail(`${f.name}:${i + 1} 不是合法 JSON`);
        continue;
      }
      for (const key of NOTE_REQUIRED) {
        if (!(key in n)) {
          fail(`${f.name}:${i + 1} 缺字段 ${key}`);
        }
      }
      if (!Array.isArray(n.tags) || n.tags.some((t) => typeof t !== "string")) {
        fail(`${f.name}:${i + 1} tags 必须是字符串数组：${JSON.stringify(n.tags)}`);
      }
      if (typeof n.body === "string" && /[\r\n]/.test(n.body)) {
        fail(`${f.name}:${i + 1} 正文里不许留换行（契约承诺折叠成单空格）`);
      }
      if (n.linked_entry_id !== null && typeof n.linked_entry_id !== "string") {
        fail(`${f.name}:${i + 1} linked_entry_id 必须是 null 或字符串`);
      }
      // 故意不做跨文件引用完整性：superseded 的行根本不导出，悬空链接可能是合法的
      if (n.created_at && n.checkin_date
          && expectCheckinDate(n.created_at) !== n.checkin_date) {
        fail(`${f.name}:${i + 1} 归日不符：created_at=${n.created_at}`
            + ` checkin_date=${n.checkin_date} 应为 ${expectCheckinDate(n.created_at)}`);
      }
      if (n.schema_version !== manifest.schema_version) {
        fail(`${f.name}:${i + 1} 行内 schema_version 与 manifest 不一致`);
      }
    }
    continue;
  }
  if (f.kind === "todos") {
    for (const [i, line] of lines.entries()) {
      let d;
      try {
        d = JSON.parse(line);
      } catch {
        fail(`${f.name}:${i + 1} 不是合法 JSON`);
        continue;
      }
      for (const key of TODO_REQUIRED) {
        if (!(key in d)) {
          fail(`${f.name}:${i + 1} 缺字段 ${key}`);
        }
      }
      if (d.done !== 0 && d.done !== 1) {
        fail(`${f.name}:${i + 1} done 必须是 0 或 1：${JSON.stringify(d.done)}`);
      }
      if (typeof d.title === "string" && /[\r\n]/.test(d.title)) {
        fail(`${f.name}:${i + 1} 标题里不许留换行（与笔记同一承诺：折叠成单空格）`);
      }
      for (const key of ["tags", "linked_entry_ids"]) {
        if (!Array.isArray(d[key]) || d[key].some((t) => typeof t !== "string")) {
          fail(`${f.name}:${i + 1} ${key} 必须是字符串数组：${JSON.stringify(d[key])}`);
        }
      }
      if (d.completed_at !== null && typeof d.completed_at !== "string") {
        fail(`${f.name}:${i + 1} completed_at 必须是 null 或字符串`);
      }
      if (d.done === 1 && d.completed_at == null) {
        fail(`${f.name}:${i + 1} 标记完成了却没有完成时刻`);
      }
      // 待办的归日按 created_at（"哪天想起的"），完成时刻不参与归日 —— 与导出侧同一口径
      if (d.created_at && d.checkin_date
          && expectCheckinDate(d.created_at) !== d.checkin_date) {
        fail(`${f.name}:${i + 1} 归日不符：created_at=${d.created_at}`
            + ` checkin_date=${d.checkin_date} 应为 ${expectCheckinDate(d.created_at)}`);
      }
      if (d.schema_version !== manifest.schema_version) {
        fail(`${f.name}:${i + 1} 行内 schema_version 与 manifest 不一致`);
      }
    }
    continue;
  }
  if (f.kind !== "time_entries") {
    continue;
  }

  const seen = new Set();
  const entries = [];
  for (const [i, line] of lines.entries()) {
    let e;
    try {
      e = JSON.parse(line);
    } catch (err) {
      fail(`${f.name}:${i + 1} 不是合法 JSON`);
      continue;
    }
    for (const key of REQUIRED) {
      if (!(key in e)) {
        fail(`${f.name}:${i + 1} 缺字段 ${key}`);
      }
    }
    if (seen.has(e.entry_id)) {
      fail(`${f.name}:${i + 1} entry_id 重复 ${e.entry_id}（幂等键失效）`);
    }
    seen.add(e.entry_id);
    if (e.status === "superseded") {
      fail(`${f.name}:${i + 1} 导出了 status=superseded 的行`);
    }
    if (e.schema_version !== manifest.schema_version) {
      fail(`${f.name}:${i + 1} 行内 schema_version 与 manifest 不一致`);
    }
    const start = Date.parse(e.started_at);
    if (Number.isNaN(start)) {
      fail(`${f.name}:${i + 1} started_at 不可解析：${e.started_at}`);
    } else if (!/[+-]\d{2}:\d{2}$/.test(e.started_at)) {
      fail(`${f.name}:${i + 1} started_at 必须带偏移量：${e.started_at}`);
    } else {
      const want = expectCheckinDate(e.started_at);
      if (e.checkin_date !== want) {
        fail(`${f.name}:${i + 1} 归日不符：started_at=${e.started_at} 应为 ${want}，实际 ${e.checkin_date}`);
      }
    }
    if (e.ended_at != null) {
      const end = Date.parse(e.ended_at);
      if (Number.isNaN(end)) {
        fail(`${f.name}:${i + 1} ended_at 不可解析`);
      } else if (end < start) {
        // 只拦"倒着走"。等于不算反向：时间戳是秒级定宽，不足一秒的一段（或起止落在同一秒）
        // 本来就会 start == end，真实时长看 duration_ms —— 2026-10-08 00:22 那次自检就是这么触发的
        fail(`${f.name}:${i + 1} 区间反向：${e.started_at} → ${e.ended_at}`);
      }
      if (typeof e.duration_ms !== "number" || e.duration_ms < 0) {
        fail(`${f.name}:${i + 1} duration_ms 非法：${e.duration_ms}`);
      }
    } else if (e.status !== "running" && e.status !== "paused") {
      fail(`${f.name}:${i + 1} ended_at 为空但状态是 ${e.status}`);
    }
    entries.push(e);
  }

  // 同 exclusivity_key 且未被用户放行的区间，两两不得相交
  const byKey = new Map();
  for (const e of entries) {
    if (e.overlap_waived === true || e.ended_at == null) {
      continue;
    }
    (byKey.get(e.exclusivity_key) ?? byKey.set(e.exclusivity_key, []).get(e.exclusivity_key)).push(e);
  }
  for (const [key, list] of byKey) {
    list.sort((a, b) => Date.parse(a.started_at) - Date.parse(b.started_at));
    for (let i = 1; i < list.length; i++) {
      if (Date.parse(list[i].started_at) < Date.parse(list[i - 1].ended_at)) {
        fail(`静默重叠：exclusivity_key=${key} ${list[i - 1].entry_id} 与 ${list[i].entry_id}`);
      }
    }
  }
  notes.push(`time_entries=${entries.length} 组数=${byKey.size}`);
}

for (const n of notes) {
  console.log("note " + n);
}
if (errors.length) {
  for (const e of errors) {
    console.log("FAIL " + e);
  }
  console.log(`CONTRACT_FAIL ${errors.length} 项不通过`);
  process.exit(1);
}
console.log("CONTRACT_OK");
