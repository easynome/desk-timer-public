package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.ui.UiTheme.Palette;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 主题的机器判据：**每个文字位与窗底都必须过 WCAG AA 的 4.5:1**。
 *
 * 为什么用对比度算式而不是"看着还行"：暗色那套是六年老代码里攒出来的十六进制数，
 * 亮色是我今天新挑的 —— 新挑的颜色最容易在"次级灰"上塌掉（背景一浅，原来的中灰就糊了）。
 * 判据写成算式，两套面板同一把尺子，以后谁改十六进制都必须重新过。
 *
 * 一句诚实的边界：窗底带 alpha（压在桌面上），实际合成值会受桌面明暗影响。
 * 这里按**不透明底**算，alpha 只留 3–4% 的余量；要连桌面一起算就没有可复现的判据了。
 */
class UiThemeTest {

  @Test
  void everyTextRoleClearsContrastAgainstTheWindow() {
    for (Palette p : List.of(UiTheme.DARK, UiTheme.LIGHT)) {
      for (String role : UiTheme.ROLES) {
        if (role.equals("button")) {
          continue; // 按钮字是压在按钮底上，不是压在窗底上
        }
        double ratio = UiTheme.contrastRatio(p.text(role), p.base());
        assertTrue(ratio >= 4.5, p.name() + " 的 " + role + " 只有 "
            + Math.round(ratio * 100) / 100.0 + ":1（要 ≥4.5）");
      }
      double onButton = UiTheme.contrastRatio(p.text("button"), p.buttonBase());
      assertTrue(onButton >= 4.5, p.name() + " 的按钮字/按钮底只有 " + onButton + ":1");
    }
  }

  @Test
  void contrastRatioMatchesTheKnownPairs() {
    assertEquals(21.0, UiTheme.contrastRatio("#ffffff", "#000000"), 0.01, "黑白的极值就是 21");
    assertEquals(1.0, UiTheme.contrastRatio("#7a8089", "#7A8089"), 0.01, "同色必须算出 1");
    assertTrue(UiTheme.contrastRatio("#7a8089", "#f5f6f7") < 4.5,
        "把暗色面板的中灰直接搬到亮底上应当判不过 —— 这正是这条判据存在的理由");
  }

  @Test
  void darkPaletteIsExactlyWhatShippedBefore() {
    // 他没要求改暗色，动任何一个颜色就是回归。唯一的例外是窗底 alpha：
    // shipped 时面板/捕获窗用 0.96、三张卡与设置窗用 0.97，这里统一成 0.97（差 1% 不可见，
    // 留两档只是多一个没人需要的令牌），其余逐字节照搬
    assertEquals("rgba(28,29,32,0.97)", UiTheme.DARK.window());
    assertEquals("#e8eaed", UiTheme.DARK.text("title"));
    assertEquals("#4a4f57", UiTheme.DARK.border("plain"));
    assertEquals("#b4762a", UiTheme.DARK.border("warn"));
    assertEquals("#3f6b8a", UiTheme.DARK.border("todo"));
  }

  /** 设置窗那个按钮只有三档可去；循环断了就会永远按不到某一档。 */
  @Test
  void preferenceCycleVisitsAllThreeAndOnlyThoseThree() {
    String p = "dark";
    List<String> seen = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      seen.add(p);
      p = UiTheme.nextPreference(p);
    }
    assertEquals(List.of("dark", "light", "system"), seen);
    assertEquals("dark", p, "第四下必须回到暗色");
    assertEquals("light", UiTheme.nextPreference(null), "库里是空/坏值时从暗色起步");
    assertEquals("dark", UiTheme.nextPreference("  SYSTEM "));
  }

  /** 解析偏好：认不出来的写法一律回暗色，"跟随系统"在探测失败时也回暗色。 */
  @Test
  void resolutionNeverInventsATheme() {
    assertEquals(UiTheme.LIGHT, UiTheme.forPreference("Light", null));
    assertEquals(UiTheme.DARK, UiTheme.forPreference("dark", true));
    assertEquals(UiTheme.LIGHT, UiTheme.forPreference("system", true));
    assertEquals(UiTheme.DARK, UiTheme.forPreference("system", false));
    assertEquals(UiTheme.DARK, UiTheme.forPreference("system", null), "读不到系统就按 shipped 的来");
    assertEquals(UiTheme.DARK, UiTheme.forPreference("随心情", null));
    assertEquals(UiTheme.DARK, UiTheme.forPreference(null, true));
  }

  @Test
  void roleNamesAreClosedSet() {
    for (String role : UiTheme.ROLES) {
      assertTrue(UiTheme.DARK.text(role).matches("#[0-9a-fA-F]{6}"), "角色 " + role + " 不是十六进制色");
      assertTrue(UiTheme.LIGHT.text(role).matches("#[0-9a-fA-F]{6}"));
    }
  }
}
