// 契约负向验收：证明"响亮失败"这条不是嘴上说说。
// 用法：node docs/verify-contract-negatives.mjs [exports目录]
// 默认拿 D:/timer-data/exports 当样本，但只在临时副本上动手，绝不改真数据。
import { cpSync, mkdirSync, rmSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";

const src = process.argv[2] || "D:/timer-data/exports";
const verifier = path.join(path.dirname(fileURLToPath(import.meta.url)), "verify-contract.mjs");
const root = path.join(tmpdir(), `contract-negatives-${Date.now()}`);
const failures = [];

function clone(name) {
  const dir = path.join(root, name);
  mkdirSync(dir, { recursive: true });
  cpSync(src, dir, { recursive: true });
  return dir;
}

function run(dir) {
  const r = spawnSync(process.execPath, [verifier, dir], { encoding: "utf8" });
  return { code: r.status, out: (r.stdout || "") + (r.stderr || "") };
}

function expect(label, dir, shouldFail, wantText) {
  const r = run(dir);
  const okCode = shouldFail ? r.code !== 0 : r.code === 0;
  const okText = wantText === null || r.out.includes(wantText);
  if (okCode && okText) {
    console.log(`PASS ${label} :: exit=${r.code}`);
  } else {
    failures.push(`${label} 期望 exit${shouldFail ? "≠0" : "=0"} 且含「${wantText}」，`
        + `实际 exit=${r.code}：${r.out.trim()}`);
  }
}

if (!existsSync(path.join(src, "manifest.json"))) {
  console.log(`NEG_SKIP 没有样本可改：${src} 里还没有 manifest.json`);
  process.exit(0);
}

// 0) 正向基线：真产物必须过
expect("正向基线", clone("baseline"), false, "CONTRACT_OK");

// 1) major 不认 ⇒ 必须停
const major = clone("major");
const mf = path.join(major, "manifest.json");
writeFileSync(mf, readFileSync(mf, "utf8").replace(/"\d+\.\d+\.\d+"/, "\"2.0.0\""));
expect("major 不认就停", major, true, "major 不认");

// 2) 必需 kind 缺失 ⇒ 必须停（这是 minor 容忍的唯一漏洞）
// 注意要连 manifest 里的声明一起删：只删文件测到的是"声明了但文件不在"，另一条规则。
// 每种必需 kind 都单独抽掉测一遍：只测 notes 的话，"新加的 kind 缺了能不能停"是没验过的。
for (const kind of ["notes", "todos"]) {
  const missing = clone("missing-kind-" + kind);
  const missingManifest = path.join(missing, "manifest.json");
  const mm = JSON.parse(readFileSync(missingManifest, "utf8"));
  const taken = mm.files.find((f) => f.kind === kind);
  mm.files = mm.files.filter((f) => f.kind !== kind);
  writeFileSync(missingManifest, JSON.stringify(mm, null, 2));
  rmSync(path.join(missing, taken.name));
  expect(`必需 kind 缺失就停（${kind}）`, missing, true, "必需 kind 缺失");
}

// 3) minor 更高 + 未知 kind ⇒ 只读自己认识的，继续通过
const minor = clone("minor-ahead");
const m2 = JSON.parse(readFileSync(path.join(minor, "manifest.json"), "utf8"));
m2.schema_version = "1.9.0";
for (const f of m2.files) {
  if (["time_entries", "time_entries_csv", "time_entries_csv_excel", "time_entries_markdown",
      "notes", "todos"].includes(f.kind)) {
    const body = readFileSync(path.join(minor, f.name), "utf8")
        .replace(/1\.\d+\.\d+/g, "1.9.0");
    writeFileSync(path.join(minor, f.name), body);
    // 改了字节就必须重算指纹，否则测的是"sha 不符"而不是版本判据
    f.sha256 = createHash("sha256").update(Buffer.from(body)).digest("hex");
  }
}
writeFileSync(path.join(minor, "future.jsonl"), '{"note":"本消费者不认识的新产物"}\n');
m2.files.push({ name: "future.jsonl", kind: "future_things", lines: 1,
  sha256: createHash("sha256")
      .update(readFileSync(path.join(minor, "future.jsonl"))).digest("hex") });
writeFileSync(path.join(minor, "manifest.json"), JSON.stringify(m2, null, 2));
// 未知 kind 忽略 + 版本更高只读认识的 ⇒ 仍然通过，但必须留下一句"我降权处理了"
const ahead = run(minor);
if (ahead.code === 0 && ahead.out.includes("minor=9")) {
  console.log("PASS minor 更高只读认识的 kind（并留下提示）");
} else {
  failures.push(`minor-ahead 应当通过且提示降权：exit=${ahead.code} ${ahead.out.trim()}`);
}

rmSync(root, { recursive: true, force: true });
if (failures.length) {
  console.log("NEGATIVE_CHECK_FAILED");
  failures.forEach((f) => console.log(" - " + f));
  process.exit(1);
}
console.log("NEGATIVE_CHECK_OK");
