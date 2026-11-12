package desktimer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

/**
 * 面板动作键的改键表（M1-d2，范围 2）。
 *
 * 三条判据决定了这张表的行为，每条都有理由：
 *  - 改键**换掉的是主键**，默认那个字母就此失效 —— 否则"改了键"名不副实，两个键都能按；
 *  - 免字母备选键（Enter/Esc/Space/方向键）**保留**，因为它们正是中文输入法吞字母时的出路；
 *    但一旦某个备选键被别的动作**认领为主键**，它就不再归原动作 —— 一个键两个动作不可解释；
 *  - 表内不许出现重复键：重复直接拒并说清和谁撞了，改坏了也不会把动作"改没了"。
 */
class KeyMapTest {

  private static KeyEvent key(KeyCode code) {
    return new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", code, false, false, false, false);
  }

  @Test
  void defaultsAreTheShippedKeyLayout() {
    KeyMap m = KeyMap.from("");
    assertEquals(KeyCode.SPACE, m.primary("pause"));
    assertEquals(KeyCode.E, m.primary("finish"));
    assertEquals(KeyCode.X, m.primary("abort"));
    assertEquals(KeyCode.H, m.primary("hide"));
    assertEquals(KeyCode.P, m.primary("pin"));
    assertEquals(KeyCode.N, m.primary("note"));
    assertEquals(KeyCode.M, m.primary("dnd"));
    // M2-b 并发带来的三个：上一行/下一行，以及"开始待开始的休息"
    assertEquals(KeyCode.UP, m.primary("prev"));
    assertEquals(KeyCode.DOWN, m.primary("next"));
    assertEquals(KeyCode.R, m.primary("rest"));
    // M2-c：笔记卡一键开（追加在表尾，不进唤起梯子 —— 它是查阅，不是"当前该看的窗"）
    assertEquals(KeyCode.L, m.primary("notes"));
    assertEquals(KeyMap.ACTIONS, m.actions(), "动作清单是这张表的对外面：少一个就是漏配");
  }

  /**
   * ACTIONS 的次序就是设置窗里 Tab 的次序：**前七个不许挪位**，新动作只能追加到末尾。
   * M2-b 把 prev/next 插在 pause 后面时，"Tab 两下选结束"就变成了"选上一行"，
   * 打包自检因此把 `prev=F7` 存进了库 —— 位次是对外承诺，不是内部排列。
   */
  @Test
  void theFirstSevenActionsNeverMoveAndNewOnesAppend() {
    assertEquals(List.of("pause", "finish", "abort", "hide", "pin", "note", "dnd"),
        KeyMap.ACTIONS.subList(0, 7), "前七个的位次变了就是改了承诺");
    assertTrue(KeyMap.ACTIONS.subList(7, KeyMap.ACTIONS.size()).containsAll(
        List.of("prev", "next", "rest", "notes")), "并发与笔记带来的动作都在表尾");
  }

  /**
   * 表越长越要钉住这条：出厂状态里两个动作不许共用一个键。
   * 撞键的严重后果不在改键时，而在**出厂那一刻就撞** —— 那时没有任何一次 `set` 会拒它，
   * 用户按下那个键，两个动作里必然有一个永远按不出来。
   */
  @Test
  void shippedTableGivesNoTwoActionsTheSameKey() {
    KeyMap m = KeyMap.from("");
    Map<KeyCode, String> owner = new HashMap<>();
    for (String a : KeyMap.ACTIONS) {
      String clash = owner.put(m.primary(a), a);
      assertNull(clash, a + " 与 " + clash + " 出厂就撞键 " + m.primary(a));
    }
    for (String a : KeyMap.ACTIONS) {
      for (KeyCode alt : m.alternates(a)) {
        assertNull(owner.get(alt),
            a + " 的备选 " + alt + " 已经是 " + owner.get(alt) + " 的主键");
      }
    }
  }

  /** 设置窗与提示条都靠 label() 出中文：动作加了却忘了配名字，界面上就是一片空白。 */
  @Test
  void everyActionHasItsOwnChineseLabel() {
    for (String a : KeyMap.ACTIONS) {
      String l = KeyMap.label(a);
      assertNotNull(l, a + " 没有名字");
      assertFalse(l.isBlank(), a + " 的名字是空的");
      assertNotEquals(a, l, a + " 的 label 不许直接回吐 id");
    }
  }

  @Test
  void anEmptyStoredValueMeansDefaults() {
    assertEquals(KeyMap.from("").wire(), KeyMap.from(null).wire());
    assertEquals(KeyCode.E, KeyMap.from("finish=E").primary("finish"));
    // 出厂键写回表里等于没改：wire 必须空，否则重置一次就把库污染成"有覆盖"
    assertTrue(KeyMap.from("finish=E").wire().isEmpty());
  }

  @Test
  void rebindingMovesTheActionAndRetiresTheOldLetter() {
    KeyMap m = KeyMap.from("finish=F7");
    assertEquals(KeyCode.F7, m.primary("finish"));
    assertTrue(m.matches(key(KeyCode.F7), "finish"));
    assertFalse(m.matches(key(KeyCode.E), "finish"), "E 已经不是结束键了，留着它等于没改");
    assertTrue(m.matches(key(KeyCode.ENTER), "finish"), "免字母备选保留：输入法吞键时还得能结束");
  }

  @Test
  void aClaimedAlternateStopsBelongingToItsOldAction() {
    KeyMap m = KeyMap.from("hide=ENTER");
    assertTrue(m.matches(key(KeyCode.ENTER), "hide"));
    assertFalse(m.matches(key(KeyCode.ENTER), "finish"),
        "Enter 被「收起」认领为主键后不能再触发「结束」：同一记按键两个动作不可解释");
  }

  @Test
  void duplicateClaimsAreRefusedAndLeaveTheTableUntouched() {
    KeyMap m = KeyMap.from("finish=F7");
    String reason = m.set("abort", KeyCode.F7);
    assertTrue(reason != null && reason.contains("结束"), "要说清和谁撞了：" + reason);
    assertEquals(KeyCode.X, m.primary("abort"), "被拒的这次不许改动原绑定");
    assertTrue(m.matches(key(KeyCode.X), "abort"));
    assertNull(m.set("abort", KeyCode.F8), "换个不撞的就该成");
    assertTrue(m.matches(key(KeyCode.F8), "abort"));
  }

  @Test
  void modifierOnlyAndUnknownKeysCannotBeBound() {
    KeyMap m = KeyMap.from("");
    for (KeyCode code : new KeyCode[] {KeyCode.SHIFT, KeyCode.CONTROL, KeyCode.ALT,
        KeyCode.META, KeyCode.WINDOWS, KeyCode.UNDEFINED}) {
      assertTrue(m.set("pause", code) != null, code + " 不能当动作键");
      assertEquals(KeyCode.SPACE, m.primary("pause"), "被拒之后原绑定不动");
      m.reset("pause");
    }
  }

  /** 应用内改键的白名单比全局快捷键**宽**：这里不喂 Win32 的 RegisterHotKey 与 JNativeHook 两张表，
   *  所以 F13-F24、方向键、Home/End 这类都能用 —— 全局键不行不等于界面键也不行。 */
  @Test
  void inAppKeysAcceptMoreThanGlobalOnes() {
    KeyMap m = KeyMap.from("");
    for (KeyCode code : new KeyCode[] {KeyCode.F13, KeyCode.F24, KeyCode.LEFT,
        KeyCode.HOME, KeyCode.END, KeyCode.PAGE_UP,
        KeyCode.PAGE_DOWN, KeyCode.DIGIT7}) {
      assertNull(m.set("pause", code), code + " 应当可以绑");
      assertTrue(m.matches(key(code), "pause"));
      m.reset("pause");
    }
    // 方向键仍然是"应用内能绑、全局不能绑"的那类：把 prev 换到 HOME 就成功
    assertNull(m.set("prev", KeyCode.HOME));
    assertTrue(m.matches(key(KeyCode.HOME), "prev"));
    m.reset("prev");
    // 而想把 UP 抢给 pause 会被拒 —— 它出厂就是 prev 的主键，这正是"一个键不许两个动作"
    assertTrue(m.set("pause", KeyCode.UP).contains("选中上一行"), "撞出厂键要说清和谁撞");
    m.reset("pause");
  }

  /**
   * Esc 与 Tab 不给动作键：前者是每个窗口唯一的"收起/稍后"出口，后者是焦点切换。
   * 出口被某个动作占了，界面就变成"按下去关不掉" —— 比少一个可绑键严重得多。
   */
  @Test
  void windowExitKeysStayOutOfTheBindableSet() {
    KeyMap m = KeyMap.from("");
    assertTrue(m.set("hide", KeyCode.ESCAPE) != null, "Esc 必须留给关窗");
    assertTrue(m.set("finish", KeyCode.TAB) != null, "Tab 必须留给焦点切换");
    assertEquals(KeyCode.H, m.primary("hide"), "被拒不许动原绑定");
    assertTrue(m.matches(key(KeyCode.ESCAPE), "hide"), "Esc 仍然是收起：它是出口，不是动作键");
  }

  @Test
  void unknownActionIsRefusedRatherThanSilentlyIgnored() {
    KeyMap m = KeyMap.from("");
    assertTrue(m.set("nope", KeyCode.Q) != null, "不存在的动作 id 必须报错，否则改了半天没反应");
    assertFalse(m.matches(key(KeyCode.Q), "nope"));
  }

  @Test
  void resetRestoresOneActionAndResetAllClearsTheWholeTable() {
    KeyMap m = KeyMap.from("finish=F7;hide=F8");
    assertEquals("finish=F7;hide=F8", m.wire());
    m.reset("finish");
    assertEquals(KeyCode.E, m.primary("finish"));
    assertEquals("hide=F8", m.wire());
    m.resetAll();
    assertEquals("", m.wire());
    assertTrue(m.matches(key(KeyCode.E), "finish"));
    assertTrue(m.matches(key(KeyCode.H), "hide"));
  }

  /** wire 必须有序且稳定：它是写进 setting 表的字符串，顺序乱掉每次保存都是一次假改动。 */
  @Test
  void wireIsSortedAndRoundTrips() {
    KeyMap m = KeyMap.from("hide=F8;note=F9;abort=F10");
    assertEquals("abort=F10;hide=F8;note=F9", m.wire());
    KeyMap again = KeyMap.from(m.wire());
    assertEquals(m.wire(), again.wire());
    assertEquals(KeyCode.F10, again.primary("abort"));
  }

  @Test
  void aTornStoredValueFallsBackToDefaultsInsteadOfThrowing() {
    // 库里存坏（手改过、半截写入）不该让界面失去按键：能认多少认多少
    KeyMap m = KeyMap.from("finish=F7;nope=Q;hide=;abort=wat");
    assertEquals(KeyCode.F7, m.primary("finish"));
    assertEquals(KeyCode.X, m.primary("abort"), "认不了的键当没改，回默认");
    assertEquals("finish=F7", m.wire(), "坏的那几条不该被写回去");
    List<String> acts = m.actions();
    assertTrue(acts.contains("hide"));
  }
}
