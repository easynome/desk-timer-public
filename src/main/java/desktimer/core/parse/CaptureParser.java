package desktimer.core.parse;

import desktimer.core.time.EntryKind;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快速捕获的确定性解析器。不调用任何模型服务：同一串输入必须永远得到同一个计划。
 *
 * 语法：
 *   输入   := [时长 | 时刻] 任务名? (#标签)* (@项目)?
 *   时长   := 数量+单位 对，可连写：1h30m / 2小时30分 / 90分钟 / 25s
 *   单位   := h|H|小时|时 | m|分钟|分 | s|秒
 *   时刻   := HH:MM  含义是"倒计时到今天的这个时刻"，已过则算到明天
 *   番茄   := p|P|ｐ 前缀（后面必须跟空格/数字/行尾，所以 "Portal 上线" 不被误伤），
 *             时长可省＝25 分；前缀后的**裸数字按分钟**（p40 / p 40 整理发布清单）= PRD 的第二档
 *
 * 裸数字这条只在番茄分支里生效：普通行 "40 写周报" 仍是"疑似漏单位"（默认 25 分 + needs_review）。
 *
 * 失败的定义：解析器其实总能出一个计划，只有"看着像时长但单位不认识"
 * （如 25x）才算失败 —— 此时按默认 25 分钟开始并标 needs_review，
 * 绝不弹窗追问：捕获路径上任何打断都是失败。
 */
public final class CaptureParser {

  public static final long DEFAULT_TARGET_MS = Duration.ofMinutes(25).toMillis();
  public static final String BLANK_TASK = "未命名计时";

  private static final Pattern TAG = Pattern.compile("#(\\S+)");
  private static final Pattern PROJECT = Pattern.compile("@(\\S+)");
  private static final Pattern CLOCK = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})(?!\\d)");
  private static final Pattern DURATION_LEAD =
      Pattern.compile("^\\s*((?:\\d+(?:\\.\\d+)?\\s*(?:h|H|小时|时|m|分钟|分|s|秒)\\s*)+)");
  private static final Pattern UNIT_PAIR =
      Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(h|H|小时|时|m|分钟|分|s|秒)");
  /** 数字 + 非数字短尾：像时长但单位不认识。 */
  private static final Pattern SUSPECT_UNIT =
      Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*[A-Za-z\\u4e00-\\u9fa5]{1,3}");
  /** 番茄行前缀后的裸数字（`p40`、`p 40 整理发布清单`）：数字 + 空白或到此为止。 */
  private static final Pattern BARE_MINUTES =
      Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)(?=\\s*$|\\s)");

  public record Plan(
      EntryKind kind,
      Long targetMs,
      String task,
      List<String> tags,
      String project,
      boolean needsReview,
      String reason) {

    public boolean isCountDown() {
      return targetMs != null;
    }
  }

  /**
   * 随手记的计划。body 是**去掉末尾标签/项目之后**剩下的正文 ——
   * 标签同时留在 tags 里，正文再带一份就会在 daily 时间轴和搜索里重复。
   */
  public record NotePlan(String body, List<String> tags, String project) {

    public boolean isBlank() {
      return body.isEmpty() && tags.isEmpty() && project == null;
    }
  }

  /**
   * 笔记行以 `!` 或全角 `！` 开头（中文输入法下 Shift+1 出来就是全角，只认半角等于这条语法不存在）。
   */
  public boolean isNote(String input) {
    String t = input == null ? "" : input.trim();
    return t.startsWith("!") || t.startsWith("！");
  }

  /**
   * 笔记语法与计时行**故意不同**：正文是散文，`#` 可能长在词里（`C#代码`），
   * 所以标签/项目只从末尾向前扫**连续段**，遇到普通词就停。
   * 计时行沿用已冻结的全局抽取（见 {@link #parse}），两套语义各自可解释。
   * 换行/制表/连续空白一律折叠成单个空格：正文里不留换行，也不把两段粘成一段。
   */
  public NotePlan parseNote(String input) {
    String raw = input == null ? "" : input.trim();
    if (isNote(raw)) {
      raw = raw.substring(1);
    }
    Sigilled s = scanTrailing(raw);
    return new NotePlan(s.text(), s.tags(), s.project());
  }

  /**
   * 待办行以 `+` 或全角 `＋` 开头。标题的 sigil 规则**与随手记共用一套**（只从末尾向前扫连续段），
   * 不是计时行那套全局抽取：一条待办是短语，`#` 会长在词里（"修 C# 编译"）。
   */
  public boolean isTodo(String input) {
    String t = input == null ? "" : input.trim();
    return t.startsWith("+") || t.startsWith("＋");
  }

  public record TodoPlan(String title, List<String> tags, String project) {

    public boolean isBlank() {
      return title.isEmpty() && tags.isEmpty() && project == null;
    }
  }

  public TodoPlan parseTodo(String input) {
    String raw = input == null ? "" : input.trim();
    if (isTodo(raw)) {
      raw = raw.substring(1);
    }
    Sigilled s = scanTrailing(raw);
    return new TodoPlan(s.text(), s.tags(), s.project());
  }

  /** 末尾连续段抽取 + 空白折叠：笔记与待办同一条实现，改这里等于同时改两边（这是刻意的）。 */
  private static Sigilled scanTrailing(String raw) {
    String trimmed = raw.trim();
    String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    List<String> tags = new ArrayList<>();
    String project = null;
    int end = words.length;
    while (end > 0) {
      String w = words[end - 1];
      if (startsWithAny(w, "@", "＠") && w.length() > 1) {
        if (project == null) {
          project = w.substring(1).trim();
        }
        end--;
      } else if (startsWithAny(w, "#", "＃") && w.length() > 1) {
        String tag = w.substring(1).trim();
        if (!tag.isEmpty() && !tags.contains(tag)) {
          tags.add(0, tag);
        }
        end--;
      } else {
        break;
      }
    }
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < end; i++) {
      if (body.length() > 0) {
        body.append(' ');
      }
      body.append(words[i]);
    }
    return new Sigilled(body.toString().trim(), tags, project);
  }

  private record Sigilled(String text, List<String> tags, String project) {
  }

  private static boolean startsWithAny(String word, String... sigils) {
    for (String s : sigils) {
      if (word.startsWith(s)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 只认"整行就是一个时长"：25m / 1h30m / 90分钟 / 1.5h。
   *
   * 待办卡补录时间用它，而不是复用 {@link #parse} —— 那条路会把 "14:20" 解释成
   * "从现在到 14:20 还有多久"（倒计时），拿它往回补就变成一段未来时间。
   * 上限沿用 S9 定下的 12 小时：一次补录超过半天，多半是敲错了。
   */
  public static Long durationOnly(String text) {
    String t = text == null ? "" : text.trim();
    if (t.isEmpty() || t.contains(":")) {
      return null;
    }
    Matcher m = DURATION_LEAD.matcher(t);
    if (!m.find() || !m.group(1).trim().equals(t)) {
      return null;
    }
    long ms = sumUnits(m.group(1));
    if (ms <= 0 || ms > Duration.ofHours(TimeText.MAX_ROLL_HOURS).toMillis()) {
      return null;
    }
    return ms;
  }

  /**
   * 番茄行以 `p` / `P` / `ｐ` 开头，**后面必须跟空格、数字或到此为止** ——
   * 否则 "Portal 上线" 这种以 p 开头的任务名会被误伤。
   */
  public boolean isPomodoro(String input) {
    String t = input == null ? "" : input.trim();
    if (t.isEmpty()) {
      return false;
    }
    char c = t.charAt(0);
    if (c != 'p' && c != 'P' && c != 'ｐ') {
      return false;
    }
    if (t.length() == 1) {
      return true;
    }
    char n = t.charAt(1);
    return n == ' ' || n == '　' || Character.isDigit(n);
  }

  /** 剥掉 `p` 前缀，留下时长与任务文字。 */
  private static String pomodoroBody(String trimmed) {
    return trimmed.substring(1).trim();
  }

  /**
   * 番茄行里前缀后紧跟的**裸数字按分钟**算（`p40` / `p 40 整理发布清单`），这是 PRD 那两档预设
   * （25 / 40）的一键入口：第二档不必打单位。已经带单位的（`1.5 小时`、`40m`）一律不动，
   * 免得把 "1.5 小时" 改写成 "1.5m 小时"。这条规则**只在番茄分支里**：普通行缺单位仍然
   * 走原来的"疑似漏单位 ⇒ 默认 25 分 + needs_review"，含义不许被这边带偏。
   */
  private static String bareMinutes(String body) {
    if (DURATION_LEAD.matcher(body).lookingAt()) {
      return body;
    }
    Matcher m = BARE_MINUTES.matcher(body);
    return m.find() ? m.group(1) + "m" + body.substring(m.end()) : body;
  }

  /**
   * 剥掉前缀后交给 {@link #parse}（番茄本来就是计时行，sigil 规则也沿用那一套），
   * 再把 kind 换成 pomodoro_work。没写时长就用出厂预设 25 分钟 ——
   * **不许退化成正计时**：番茄没有目标时长就没有"到点"，第 4 轮该长休也无从判断。
   */
  public Plan parsePomodoro(String input) {
    String raw = input == null ? "" : input.trim();
    if (isPomodoro(raw)) {
      raw = bareMinutes(pomodoroBody(raw));
    }
    Plan p = parse(raw);
    long ms = p.targetMs() == null ? DEFAULT_TARGET_MS : p.targetMs();
    return new Plan(EntryKind.POMODORO_WORK, ms, p.task(), p.tags(), p.project(),
        p.needsReview(), p.reason());
  }

  public Plan parse(String input) {
    return parse(input, OffsetDateTime.now());
  }

  public Plan parse(String input, OffsetDateTime now) {
    String raw = input == null ? "" : input.trim();
    List<String> tags = takeAll(raw, TAG);
    raw = TAG.matcher(raw).replaceAll(" ").trim();
    String project = first(raw, PROJECT);
    raw = PROJECT.matcher(raw).replaceAll(" ").trim();

    Matcher clock = CLOCK.matcher(raw);
    if (clock.find()) {
      String rest = raw.substring(clock.end()).trim();
      long ms = millisUntilClockTime(clock.group(1), clock.group(2), now);
      return plan(EntryKind.COUNT_DOWN, ms, taskOf(rest), tags, project, false, null);
    }

    Matcher duration = DURATION_LEAD.matcher(raw);
    if (duration.find()) {
      long ms = sumUnits(duration.group(1));
      String rest = raw.substring(duration.end()).trim();
      if (ms > 0) {
        return plan(kindFor(ms), ms, taskOf(rest), tags, project, false, null);
      }
    }

    Matcher suspect = SUSPECT_UNIT.matcher(raw);
    if (suspect.find()) {
      String bad = suspect.group().trim();
      String rest = raw.substring(suspect.end()).trim();
      return plan(EntryKind.COUNT_DOWN, DEFAULT_TARGET_MS, taskOf(rest), tags, project, true,
          "看不懂时长单位「" + bad + "」，按默认 25 分钟开始");
    }

    return plan(EntryKind.COUNT_UP, null, taskOf(raw), tags, project, false, null);
  }

  private static Plan plan(EntryKind kind, Long ms, String task, List<String> tags,
      String project, boolean needsReview, String reason) {
    return new Plan(kind, ms, task, tags, project, needsReview, reason);
  }

  private static EntryKind kindFor(long ms) {
    return ms > 0 ? EntryKind.COUNT_DOWN : EntryKind.COUNT_UP;
  }

  /** 抽掉标签/项目后会留下空洞，折叠成单空格：`写   周报` 和 `写 周报` 在下游是两组。 */
  private static String taskOf(String rest) {
    String t = rest == null ? "" : rest.trim().replaceAll("\\s+", " ");
    return t.isEmpty() ? BLANK_TASK : t;
  }

  private static long sumUnits(String chunk) {
    Matcher m = UNIT_PAIR.matcher(chunk);
    long total = 0;
    while (m.find()) {
      double value = Double.parseDouble(m.group(1));
      String unit = m.group(2);
      total += switch (unit) {
        case "h", "H", "小时", "时" -> (long) (value * 3_600_000L);
        case "m", "分钟", "分" -> (long) (value * 60_000L);
        default -> (long) (value * 1_000L);
      };
    }
    return total;
  }

  /** HH:MM 的含义是"倒计到今天的这个时刻"；已经过了就倒计到明天。 */
  private static long millisUntilClockTime(String hh, String mm, OffsetDateTime now) {
    LocalTime at = LocalTime.of(Integer.parseInt(hh), Integer.parseInt(mm));
    OffsetDateTime target = now.toLocalDate().atTime(at).atOffset(now.getOffset());
    if (!target.isAfter(now)) {
      target = target.plusDays(1);
    }
    return Duration.between(now, target).toMillis();
  }

  private static List<String> takeAll(String text, Pattern p) {
    List<String> out = new ArrayList<>();
    Matcher m = p.matcher(text);
    while (m.find()) {
      String v = m.group(1).trim();
      if (!v.isEmpty() && !out.contains(v)) {
        out.add(v);
      }
    }
    return out;
  }

  private static String first(String text, Pattern p) {
    Matcher m = p.matcher(text);
    return m.find() ? m.group(1).trim() : null;
  }
}
