package desktimer.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 提示音的两条契约：**首选哪个事件**，以及**取不到声音时不许抛异常**。
 *
 * 第一条是承诺而非实现细节：他选的是"新短信通知"，之后换音、静音都在 设置 → 声音 里做，
 * 代码不该再动。所以把它钉在这里 —— 谁改了别名顺序，这条先红。
 *
 * 第二条为什么必须在真 winmm 上跑：调用点（到点提醒）不在 try 里。到点这一步抛异常，
 * 面板不会亮、也不会响，等于整条提醒链子断了；而加载失败恰好只发生在真机上。
 * 这里不断言"响的是哪一声"（他把主题音全关时 PlaySound 会解析不到），只断言落在契约内。
 */
class NotifySoundTest {

  @Test
  void imNotificationIsThePreferredEvent() {
    assertEquals("Notification.IM", NotifySound.ALIASES[0],
        "提示音绑死在\"新短信通知\"事件上，用户在声音设置里换音即可");
    assertEquals(2, NotifySound.ALIASES.length, "退路只有一档，再加要先想清楚静音时该不该响");
    assertTrue(Arrays.asList(NotifySound.ALIASES).contains("SystemExclamation"),
        "第二档是\"警告\"声");
    assertFalse(List.of(NotifySound.ALIASES).contains(""), "空别名会把 PlaySound 变成\"停止播放\"");
  }

  @Test
  void playReturnsALabelInsideTheContractAndNeverThrows() {
    Assumptions.assumeTrue(isWindows(), "非 Windows 上没有 winmm，跳过");
    String label = NotifySound.play();
    assertTrue(Arrays.asList(NotifySound.ALIASES).contains(label) || "beep".equals(label) || "silent".equals(label),
        "实际走的路只可能是别名或最后的蜂鸣，实得=" + label);
    if (!"beep".equals(label)) {
      assertEquals("", NotifySound.lastError(), "走到蜂鸣以外的路就是成功，得留空原因");
    } else {
      assertNotNull(NotifySound.lastError());
      assertFalse(NotifySound.lastError().isEmpty(), "退回蜂鸣必须说明为什么，不许静默");
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
