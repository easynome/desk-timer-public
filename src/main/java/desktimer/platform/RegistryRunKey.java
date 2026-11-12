package desktimer.platform;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;

/**
 * HKCU 的 Run 键读写（开机自启，免管理员）。DESIGN.md 第 385 行选的就是这条机制：
 * 用户级、不碰机器级路径、一次备份 zip 也带不走它（它是系统里的状态，不是库里的）。
 *
 * 三条实测出来的规矩（少了任何一条都会静默失败，见 docs/dev/freeze.md）：
 *  - W 后缀函数的名字参数必须用 {@link WString}。JNA 默认把 Java String 当 ANSI 送，
 *    写进去的键名与读的时候不是同一个，表现就是"SET 返回 0 但 QUERY 说找不到"。
 *  - 删值要 WRITE 权限：只拿 READ 打开的句柄调 RegDeleteValueW 返回 5（ACCESS_DENIED）。
 *  - 注册表就是"自启开没开"的唯一事实源，不在 setting 表里另存一份 —— 用户可以在
 *    任务管理器/别的工具里改掉它，程序里的副本只会说谎。
 *
 * 包级构造器允许把键路径换成一个自测用的临时键，正式路径不暴露给自检。
 */
public final class RegistryRunKey {

  public static final String RUN_SUBKEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

  private static final int KEY_READ = 0x20019;
  private static final int KEY_ALL = 0xF003F;
  private static final int REG_SZ = 1;
  private static final Pointer HKCU = new Pointer(0x80000001L);

  /** 只用真实导出名声明（这里是 W 版），JNA 按名字找函数。 */
  private interface Advapi32 extends com.sun.jna.Library {
    int RegCreateKeyExW(Pointer root, WString sub, int reserved, WString cls, int options,
        int access, Pointer sa, PointerByReference out, IntByReference disposition);

    int RegOpenKeyExW(Pointer root, WString sub, int options, int access, PointerByReference out);

    int RegSetValueExW(Pointer key, WString name, int reserved, int type, byte[] data, int len);

    int RegQueryValueExW(Pointer key, WString name, int reserved, IntByReference type,
        byte[] data, IntByReference len);

    int RegDeleteValueW(Pointer key, WString name);

    int RegDeleteKeyW(Pointer root, WString sub);

    int RegCloseKey(Pointer key);
  }

  private static final Advapi32 ADV = Native.load("advapi32", Advapi32.class);

  private final String subKey;
  private final String valueName;

  public RegistryRunKey(String valueName) {
    this(RUN_SUBKEY, valueName);
  }

  /** 自检与单测走这条路：真实路径不碰，只在临时键上证明读写删都成立。 */
  RegistryRunKey(String subKey, String valueName) {
    this.subKey = subKey;
    this.valueName = valueName;
  }

  /** 现在挂着的值（带引号的启动命令）；没挂返回 null。读不到不等于出错 —— 键不存在就是 null。 */
  public String current() {
    PointerByReference phk = new PointerByReference();
    if (ADV.RegOpenKeyExW(HKCU, new WString(subKey), 0, KEY_READ, phk) != 0) {
      return null;
    }
    Pointer key = phk.getValue();
    try {
      IntByReference type = new IntByReference();
      IntByReference len = new IntByReference(0);
      if (ADV.RegQueryValueExW(key, new WString(valueName), 0, type, null, len) != 0) {
        return null;
      }
      byte[] buf = new byte[Math.max(2, len.getValue())];
      IntByReference len2 = new IntByReference(buf.length);
      if (ADV.RegQueryValueExW(key, new WString(valueName), 0, new IntByReference(), buf, len2)
          != 0) {
        return null;
      }
      int chars = Math.max(0, (len2.getValue() / 2) - 1);
      return new String(buf, 0, chars * 2, StandardCharsets.UTF_16LE);
    } finally {
      ADV.RegCloseKey(key);
    }
  }

  /** 写入自启命令；exePath 用引号包住（Run 值里的路径可能带空格）。 */
  public void enable(String exePath) {
    String quoted = exePath.contains("\"") ? exePath : "\"" + exePath + "\"";
    byte[] data = (quoted + (char) 0).getBytes(StandardCharsets.UTF_16LE);
    PointerByReference phk = new PointerByReference();
    int rc = ADV.RegCreateKeyExW(HKCU, new WString(subKey), 0, null, 0, KEY_ALL, null, phk,
        new IntByReference());
    if (rc != 0) {
      throw new IllegalStateException("RegCreateKeyExW rc=" + rc + " subKey=" + subKey);
    }
    Pointer key = phk.getValue();
    try {
      int set = ADV.RegSetValueExW(key, new WString(valueName), 0, REG_SZ, data, data.length);
      if (set != 0) {
        throw new IllegalStateException("RegSetValueExW rc=" + set + " value=" + valueName);
      }
    } finally {
      ADV.RegCloseKey(key);
    }
  }

  /** 关掉自启：只删自己那一个值，Run 键里别人的东西一律不碰。 */
  public void disable() {
    PointerByReference phk = new PointerByReference();
    if (ADV.RegOpenKeyExW(HKCU, new WString(subKey), 0, KEY_ALL, phk) != 0) {
      return;
    }
    Pointer key = phk.getValue();
    try {
      int rc = ADV.RegDeleteValueW(key, new WString(valueName));
      if (rc != 0 && rc != 2) {
        throw new IllegalStateException("RegDeleteValueW rc=" + rc + " value=" + valueName);
      }
    } finally {
      ADV.RegCloseKey(key);
    }
  }

  /** 自检收尾用：把整个临时键删掉（正式路径永不调用）。 */
  void deleteWholeKey() {
    ADV.RegDeleteKeyW(HKCU, new WString(subKey));
  }

  /**
   * 当前值是否指向这个 exe（比较时把两边的引号都去掉，Run 值写没写引号都算通过）。
   */
  public boolean pointsTo(String exePath) {
    String cur = current();
    if (cur == null) {
      return false;
    }
    String bare = cur.replace("\"", "").trim();
    return bare.equalsIgnoreCase(exePath.replace("\"", "").trim());
  }
}
