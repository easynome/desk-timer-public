package desktimer.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 窗口配色的唯一来源。以前这三十来处 {@code setStyle} 各自手抄十六进制数，
 * 于是"换个主题"等于改六个文件，而且没人能保证亮色那套的文字还读得清。
 *
 * 两条规矩：
 *  - <b>角色名而不是颜色名</b>（title/muted/dim/accent/row/current/button）：
 *    调用方说的是"这是提示条"，不是"这是 #7a8089"。新增角色要同时给两套值，
 *    {@code UiThemeTest} 会拿 WCAG 4.5:1 逐角色量一遍，量不过就不许合。
 *  - 暗色是** shipped 值照搬**（只有窗底 alpha 从 0.96/0.97 两档统一成 0.97 ——
 *    差 1% 的透明度肉眼看不出来，留两档就只是多一个令牌）。他没要求改暗色，动它就是回归。
 */
public final class UiTheme {

  /** 文字位的名字。加一个就得在两套面板里各给一个值。 */
  public static final List<String> ROLES = List.of("title", "muted", "dim", "accent", "row",
      "current", "item", "button");

  /**
   * 一套面板：窗底 + 三种边框 + 若干文字位。
   * {@code base} 是窗底的不透明版本，只用来算对比度（判据要可复现，不能压在桌面上算）。
   */
  public static final class Palette {

    private final String name;
    private final String window;
    private final String base;
    private final String button;
    private final Map<String, String> border;
    private final Map<String, String> text;

    Palette(String name, String window, String base, String button,
        Map<String, String> border, Map<String, String> text) {
      this.name = name;
      this.window = window;
      this.base = base;
      this.button = button;
      this.border = border;
      this.text = text;
    }

    public String name() {
      return name;
    }

    /** 无边框窗的底 + 边框（{@code accent}：plain 普通 / warn 对账与钉住 / todo 待办）。 */
    public String window(String accent) {
      return "-fx-background-color: " + window + "; -fx-border-color: " + border(accent)
          + "; -fx-border-width: 1;";
    }

    public String border(String accent) {
      String v = border.get(accent);
      if (v == null) {
        throw new IllegalArgumentException("没有这种边框语义：" + accent + "（认识：" + border.keySet() + "）");
      }
      return v;
    }

    /** 一行标签的样式。字号是布局的一部分，所以留在调用方；颜色不留。 */
    public String label(int px, String role) {
      return label(px, role, false);
    }

    public String label(int px, String role, boolean monospace) {
      StringBuilder sb = new StringBuilder("-fx-font-size: ").append(px).append("px;");
      if (monospace) {
        sb.append(" -fx-font-family: 'Consolas','monospace';");
      }
      return sb.append(" -fx-text-fill: ").append(text(role)).append(';').toString();
    }

    /** 设置窗那三个按钮（它们是"能点的"，所以字与底都要过判据）。 */
    public String buttonStyle() {
      return "-fx-font-size: 12px; -fx-text-fill: " + text("button")
          + "; -fx-background-color: " + button + "; -fx-background-radius: 3; -fx-padding: 4 10;";
    }

    public String text(String role) {
      String v = text.get(role);
      if (v == null) {
        throw new IllegalArgumentException("没有这个文字位：" + role + "（认识：" + ROLES + "）");
      }
      return v;
    }

    public String window() {
      return window;
    }

    public String base() {
      return base;
    }

    public String buttonBase() {
      return button;
    }
  }

  private static Map<String, String> borders(String plain, String warn, String todo) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("plain", plain);
    m.put("warn", warn);
    m.put("todo", todo);
    return m;
  }

  private static Map<String, String> text(String title, String muted, String dim, String accent,
      String row, String current, String item, String button) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("title", title);
    m.put("muted", muted);
    m.put("dim", dim);
    m.put("accent", accent);
    m.put("row", row);
    m.put("current", current);
    m.put("item", item);
    m.put("button", button);
    return m;
  }

  /** shipped 的那一套，逐个字节照搬，除两处：窗底 alpha 统一成 0.97，hint 的灰由
   * {@code #7a8089}（4.23:1，判据不过）提到 {@code #868d96}（5.03:1，同色系）。
   * {@code row} 是面板列表里没选中的行，{@code item} 是卡片/设置列表里没选中的行 ——
   * shipped 时两者本来不同（面板那行更靠边、字号一致但亮度分层），照搬不动。 */
  public static final Palette DARK = new Palette("dark",
      "rgba(28,29,32,0.97)", "#1c1d20", "#33363b",
      borders("#4a4f57", "#b4762a", "#3f6b8a"),
      text("#e8eaed", "#9aa0a6", "#868d96", "#ffd08a", "#8b9199", "#ffd08a", "#c8ccd0", "#e8eaed"));

  /**
   * 亮色：窗底压白、文字换深灰，边框跟着降饱和。
   * 每个值都得过 {@code UiThemeTest} 的 4.5:1，尤其是 muted/dim —— 浅底上的中灰最容易塌。
   */
  public static final Palette LIGHT = new Palette("light",
      "rgba(245,246,247,0.97)", "#f5f6f7", "#dfe1e4",
      borders("#b8bec7", "#8a5a10", "#22577a"),
      text("#1b1f24", "#4a5158", "#5c636b", "#7a4a00", "#3f454c", "#7a4a00", "#2b3037",
          "#1b1f24"));

  /**
   * 偏好字符串 → 面板。认不出来的写法（包括空串与被改坏的存量）一律回暗色 ——
   * 那是这台工具 shipped 的样子，也是"读不到系统设置"时的安全退路。
   *
   * {@code systemLight} 允许是 null（非 Windows、或注册表读不到）：这时 system 也回暗色，
   * 不猜、不抛。
   */
  public static Palette forPreference(String pref, Boolean systemLight) {
    if (pref == null) {
      return DARK;
    }
    switch (pref.trim().toLowerCase()) {
      case "light":
        return LIGHT;
      case "system":
        return Boolean.TRUE.equals(systemLight) ? LIGHT : DARK;
      default:
        return DARK;
    }
  }

  /**
   * 设置窗那个按钮的三档循环：暗 → 亮 → 跟随系统 → 暗。
   * 放在这里而不是设置窗里：它是"选哪套配色"的判断，不是窗口装饰，而且它能在不起 JavaFX
   * 的情况下被单测跑干净（循环断了就会永远按不到某一档）。
   */
  public static String nextPreference(String pref) {
    String p = pref == null ? "dark" : pref.trim().toLowerCase();
    if (p.equals("light")) {
      return "system";
    }
    return p.equals("system") ? "dark" : "light";
  }

  /** WCAG 2.x 的对比度算式：(L1+0.05)/(L2+0.05)，L 是相对亮度。 */
  public static double contrastRatio(String hexA, String hexB) {
    double a = luminance(hexA);
    double b = luminance(hexB);
    double hi = Math.max(a, b);
    double lo = Math.min(a, b);
    return (hi + 0.05) / (lo + 0.05);
  }

  private static double luminance(String hex) {
    String h = hex.startsWith("#") ? hex.substring(1) : hex;
    if (h.length() == 3) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 3; i++) {
        sb.append(h.charAt(i)).append(h.charAt(i));
      }
      h = sb.toString();
    }
    if (h.length() != 6) {
      throw new IllegalArgumentException("不是十六进制颜色：" + hex);
    }
    return 0.2126 * channel(Integer.parseInt(h.substring(0, 2), 16))
        + 0.7152 * channel(Integer.parseInt(h.substring(2, 4), 16))
        + 0.0722 * channel(Integer.parseInt(h.substring(4, 6), 16));
  }

  /** sRGB → 线性：先除 255，再按那两条分段曲线走（少了这步算出来的比值全是错的）。 */
  private static double channel(int raw) {
    double c = raw / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private UiTheme() {
  }
}
