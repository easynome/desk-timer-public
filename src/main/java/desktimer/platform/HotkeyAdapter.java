package desktimer.platform;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyAdapter;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局快捷键。两个世界分工：
 *  - 投递：JNativeHook 的低级钩子（应用不在前台也能收到）。
 *  - 占用探测：Win32 {@code RegisterHotKey}。钩子本身是被动监听、不独占，
 *    所以"键已被别人占了"这件事只有注册式 API 报得出来。探测完立刻注销，
 *    不真的持有它，避免自己写消息循环。
 */
public final class HotkeyAdapter implements AutoCloseable {

  public record Registration(boolean ok, String spec, String reason) {
  }

  public interface User32 extends Library {
    User32 INSTANCE = Native.load("user32", User32.class);

    boolean RegisterHotKey(Pointer hWnd, int id, int fsModifiers, int vk);

    boolean UnregisterHotKey(Pointer hWnd, int id);
  }

  private static final int PROBE_ID = 0x5DE0;

  private NativeKeyAdapter listener;
  private KeySpec current;
  private boolean hookInstalled;

  /**
   * 装上一个组合。可以反复调用来换键 —— 顺序是**先探测再摘旧的**：
   * 探测不过就把上一条监听原样留着，所以设置窗里改坏了也永远不会出现"没有快捷键"的空档。
   */
  public Registration register(KeySpec spec, Runnable onHit) {
    String blocked = probeOccupancy(spec);
    if (blocked != null) {
      return new Registration(false, spec.canonical(), blocked);
    }
    try {
      installHook();
    } catch (NativeHookException e) {
      return new Registration(false, spec.canonical(), "全局钩子安装失败：" + e.getMessage());
    }
    detach();
    listener = new NativeKeyAdapter() {
      @Override
      public void nativeKeyPressed(NativeKeyEvent e) {
        if (spec.matches(e.getKeyCode(), e.getModifiers())) {
          onHit.run();
        }
      }
    };
    GlobalScreen.addNativeKeyListener(listener);
    current = spec;
    return new Registration(true, spec.canonical(), null);
  }

  /** 当前真正挂着的那个组合；没装上过就是 null。设置窗显示的要这个，不是用户上次存的那个。 */
  public KeySpec current() {
    return current;
  }

  public boolean isHookInstalled() {
    return hookInstalled;
  }

  private void installHook() throws NativeHookException {
    if (hookInstalled) {
      return;
    }
    Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);
    GlobalScreen.registerNativeHook();
    hookInstalled = true;
  }

  private void detach() {
    if (listener != null) {
      GlobalScreen.removeNativeKeyListener(listener);
      listener = null;
    }
    current = null;
  }

  /** 返回 null 表示"没被占用"或"探测不了"；探测失败不该阻断功能，所以宁可放行。 */
  private static String probeOccupancy(KeySpec spec) {
    if (!Platform.isWindows()) {
      return null;
    }
    try {
      User32 u32 = User32.INSTANCE;
      if (u32.RegisterHotKey(null, PROBE_ID, spec.win32Modifiers(), spec.win32Vk())) {
        u32.UnregisterHotKey(null, PROBE_ID);
        return null;
      }
      return "快捷键已被其他程序占用（RegisterHotKey 拒绝），请改键";
    } catch (Throwable t) {
      return null;
    }
  }

  @Override
  public void close() {
    try {
      detach();
      if (hookInstalled) {
        GlobalScreen.unregisterNativeHook();
        hookInstalled = false;
      }
    } catch (NativeHookException e) {
      System.out.println("HOTKEY_CLOSE_FAILED " + e.getMessage());
    }
  }
}
