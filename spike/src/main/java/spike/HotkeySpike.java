package spike;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyAdapter;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * T-03 全局快捷键实测：listener 进程挂全局钩子，sender 进程用 Robot 注入 Ctrl+Alt+F12。
 * listener 没有窗口，所以命中即证明"焦点不在本进程时也能收到"。
 * 注意：注入事件带 LLKHF_INJECTED 标记，若钩子过滤注入会假阴性 —— 物理键仍需人工复核一次。
 */
public final class HotkeySpike {

  private static volatile boolean hit = false;

  public static void main(String[] args) throws Exception {
    String role = arg(args, "--role", "listen");
    int secs = Integer.parseInt(arg(args, "--secs", "40"));

    if (role.equals("send")) {
      Thread.sleep(4000);
      Robot r = new Robot();
      r.keyPress(KeyEvent.VK_CONTROL);
      r.keyPress(KeyEvent.VK_ALT);
      r.keyPress(KeyEvent.VK_F12);
      r.keyRelease(KeyEvent.VK_F12);
      r.keyRelease(KeyEvent.VK_ALT);
      r.keyRelease(KeyEvent.VK_CONTROL);
      System.out.println("SENT ctrl+alt+f12 ts=" + System.currentTimeMillis());
      return;
    }

    Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.OFF);
    try {
      GlobalScreen.registerNativeHook();
    } catch (NativeHookException e) {
      System.out.println("HOOK_REGISTER_FAILED " + e.getMessage());
      System.exit(2);
    }
    System.out.println("HOOK_REGISTERED ts=" + System.currentTimeMillis());
    GlobalScreen.addNativeKeyListener(new NativeKeyAdapter() {
      @Override
      public void nativeKeyPressed(NativeKeyEvent e) {
        int m = e.getModifiers();
        boolean ctrl = (m & NativeKeyEvent.CTRL_MASK) != 0;
        boolean alt = (m & NativeKeyEvent.ALT_MASK) != 0;
        if (ctrl && alt && e.getKeyCode() == NativeKeyEvent.VC_F12) {
          hit = true;
          System.out.println("HOTKEY_HIT keyCode=" + e.getKeyCode() + " ts=" + System.currentTimeMillis());
        }
      }
    });

    long deadline = System.currentTimeMillis() + secs * 1000L;
    while (System.currentTimeMillis() < deadline && !hit) {
      Thread.sleep(200);
    }
    System.out.println(hit ? "RESULT=PASS" : "RESULT=NO_HIT");
    GlobalScreen.unregisterNativeHook();
    System.exit(hit ? 0 : 1);
  }

  private static String arg(String[] args, String key, String fallback) {
    for (String a : args) {
      if (a.startsWith(key + "=")) {
        return a.substring(key.length() + 1);
      }
    }
    return fallback;
  }
}
