package desktimer.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * 一个窗口内动作键的绑定表（M1-d2，范围 2）。
 *
 * 三条规则是这张表的全部语义，改之前先想清楚为什么：
 *  1. **改键换掉主键**，默认那个字母随之失效 —— 留着它就不是"改键"，而是多一个键；
 *  2. **免字母备选键保留**（Enter/Esc/Space/方向键），因为这套界面存在的理由就是
 *     中文输入法会吞字母键；但某个备选键被别的动作认领为主键后就不再归原动作 ——
 *     同一记按键触发两个动作是不可解释的；
 *  3. **不许重复认领**：撞了就拒，并说清和谁撞；被拒的那次不改动任何原绑定 ——
 *     改坏了不能把动作"改没了"，用户还得靠它把键换回来。
 *
 * 应用内可绑的键比全局快捷键**宽**：这里不喂 Win32 RegisterHotKey 与 JNativeHook 那两张表，
 * 所以 F13-F24、Home/End、翻页键都能用（{@link Keys#toSpec} 拒它们是为了全局那条路）。
 */
public final class KeyMap {

  /**
   * 动作 id 就是 setting 里写的名字，顺序即设置窗里的展示顺序，**也是 Tab 的次序**。
   *
   * 所以新动作一律追加到末尾：前面七个的位次是对外承诺过的（自检与用户都按"Tab 两下是结束"操作），
   * 插在中间会静默改掉每一行的落点 —— M2-b 加 prev/next/rest 时就是这么把自检弄红的。
   */
  public static final List<String> ACTIONS = List.of("pause", "finish", "abort", "hide", "pin",
      "note", "dnd", "prev", "next", "rest", "notes");

  private static final Map<String, KeyCode> DEFAULTS = defaults();
  /** 免字母备选：只在没被别人认领为主键时生效。 */
  private static final Map<String, List<KeyCode>> ALTERNATES = alternates();
  private static final Set<KeyCode> BINDABLE = bindable();

  private final Map<String, KeyCode> primary = new LinkedHashMap<>();

  private KeyMap() {
    ACTIONS.forEach(a -> primary.put(a, DEFAULTS.get(a)));
  }

  /**
   * 读库里存的覆盖串（形如 {@code abort=F10;hide=F8}）。
   * 认不了的部分一律当没改：手改过库、半截写入都不该让界面失去按键。
   */
  public static KeyMap from(String storedWire) {
    KeyMap m = new KeyMap();
    if (storedWire == null || storedWire.isBlank()) {
      return m;
    }
    for (String part : storedWire.split(";")) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String action = part.substring(0, eq).trim();
      String name = part.substring(eq + 1).trim();
      if (!m.primary.containsKey(action) || name.isEmpty()) {
        continue;
      }
      KeyCode code;
      try {
        code = KeyCode.valueOf(name);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (!BINDABLE.contains(code) || m.claimedByOther(action, code)) {
        continue;
      }
      m.primary.put(action, code);
    }
    return m;
  }

  public List<String> actions() {
    return ACTIONS;
  }

  public KeyCode primary(String action) {
    return primary.getOrDefault(action, null);
  }

  /**
   * 这个动作的免字母备选键（出厂那批，不随改键变化）。
   * 公开它是为了让"出厂表不许撞键"能被断言 —— 撞键的恶果不在改键时，而在出厂就撞的那一刻，
   * 那时没有任何一次 {@link #set} 会拒它，用户按下那个键，两个动作里必然有一个永远按不出来。
   */
  public List<KeyCode> alternates(String action) {
    return ALTERNATES.getOrDefault(action, List.of());
  }

  /** 这一记按键该不该算这个动作：主键，或没被别人认领的备选键。 */
  public boolean matches(KeyEvent e, String action) {
    KeyCode wanted = primary.get(action);
    if (wanted == null || e.getCode() == null) {
      return false;
    }
    if (e.getCode() == wanted) {
      return true;
    }
    // 字母键还看事件带的字母：输入法吞键时 code 可能是 UNDEFINED（见 Keys）
    if (Keys.any(e, wanted)) {
      return true;
    }
    for (KeyCode alt : ALTERNATES.getOrDefault(action, List.of())) {
      if (e.getCode() == alt && !claimedAsPrimaryByOther(action, alt)) {
        return true;
      }
    }
    return false;
  }

  /** 返回 null 表示改成了；否则是要显示给用户的原因。 */
  public String set(String action, KeyCode code) {
    if (!primary.containsKey(action)) {
      return "不认识的动作「" + action + "」，改键没生效";
    }
    if (code == null || !BINDABLE.contains(code)) {
      return "这个键不能当动作键：只认字母、数字、F1-F24、方向键、空格/回车和 Home/End/翻页"
          + "（Esc 与 Tab 留给关窗和焦点切换）";
    }
    String clash = ownerOf(code, action);
    if (clash != null) {
      return "「" + code.getName() + "」已经是" + label(clash) + "的键，换一个或先把那个改掉";
    }
    primary.put(action, code);
    return null;
  }

  public void reset(String action) {
    if (DEFAULTS.containsKey(action)) {
      primary.put(action, DEFAULTS.get(action));
    }
  }

  public void resetAll() {
    ACTIONS.forEach(a -> primary.put(a, DEFAULTS.get(a)));
  }

  /** 只写"与出厂不同"的那些，按动作名排序：每次都一样的串才不会造成假改动。 */
  public String wire() {
    Map<String, String> sorted = new TreeMap<>();
    primary.forEach((action, code) -> {
      if (code != DEFAULTS.get(action)) {
        sorted.put(action, code.name());
      }
    });
    List<String> parts = new ArrayList<>();
    sorted.forEach((action, name) -> parts.add(action + "=" + name));
    return String.join(";", parts);
  }

  private String ownerOf(KeyCode code, String exceptAction) {
    for (Map.Entry<String, KeyCode> e : primary.entrySet()) {
      if (!e.getKey().equals(exceptAction) && e.getValue() == code) {
        return e.getKey();
      }
    }
    return null;
  }

  private boolean claimedByOther(String action, KeyCode code) {
    return ownerOf(code, action) != null;
  }

  private boolean claimedAsPrimaryByOther(String action, KeyCode code) {
    return ownerOf(code, action) != null;
  }

  /** 界面要给用户看的名字（也是报错信息里的名字），和设置窗、提示条同源。 */
  public static String label(String action) {
    return switch (action) {
      case "pause" -> "暂停/继续";
      case "prev" -> "选中上一行";
      case "next" -> "选中下一行";
      case "rest" -> "开始休息";
      case "notes" -> "翻笔记";
      case "finish" -> "结束";
      case "abort" -> "放弃";
      case "hide" -> "收起";
      case "pin" -> "钉住置顶";
      case "note" -> "记一笔/另开";
      case "dnd" -> "勿扰一小时";
      default -> action;
    };
  }

  private static Map<String, KeyCode> defaults() {
    Map<String, KeyCode> m = new LinkedHashMap<>();
    m.put("pause", KeyCode.SPACE);
    m.put("prev", KeyCode.UP);
    m.put("next", KeyCode.DOWN);
    m.put("finish", KeyCode.E);
    m.put("abort", KeyCode.X);
    m.put("hide", KeyCode.H);
    m.put("pin", KeyCode.P);
    m.put("note", KeyCode.N);
    m.put("dnd", KeyCode.M);
    m.put("rest", KeyCode.R);
    m.put("notes", KeyCode.L);
    return m;
  }

  private static Map<String, List<KeyCode>> alternates() {
    Map<String, List<KeyCode>> m = new LinkedHashMap<>();
    m.put("finish", List.of(KeyCode.ENTER));
    m.put("hide", List.of(KeyCode.ESCAPE));
    m.put("note", List.of(KeyCode.RIGHT));
    return m;
  }

  private static Set<KeyCode> bindable() {
    Set<KeyCode> s = new java.util.HashSet<>();
    for (KeyCode c : KeyCode.values()) {
      String n = c.name();
      boolean letter = n.length() == 1 && n.charAt(0) >= 'A' && n.charAt(0) <= 'Z';
      boolean digit = n.startsWith("DIGIT") && n.length() == 6;
      boolean function = n.matches("F\\d{1,2}");
      if (letter || digit || function) {
        s.add(c);
      }
    }
    // Esc 与 Tab 刻意不放进来：前者是每个窗口唯一的"收起/稍后"出口，后者是焦点切换。
    // 把出口让给某个动作键，界面就会变成"按下去关不掉"。
    s.addAll(List.of(KeyCode.SPACE, KeyCode.ENTER, KeyCode.UP,
        KeyCode.DOWN, KeyCode.LEFT, KeyCode.RIGHT, KeyCode.HOME, KeyCode.END, KeyCode.PAGE_UP,
        KeyCode.PAGE_DOWN));
    return s;
  }
}
