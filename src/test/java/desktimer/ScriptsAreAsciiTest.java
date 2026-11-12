package desktimer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 脚本必须是纯 ASCII：Windows PowerShell 5.1 按 ANSI/GBK 读 .ps1，
 * UTF-8 的中文（甚至一个全角破折号）会让整段脚本在**解析期**就失败，
 * 报的是"字符串缺少终止符""缺少 }"这种完全不指向根因的错。
 *
 * 这条纪律我在同一个项目里踩过三次（run-app.ps1、measure.ps1、package.ps1），
 * 所以从"记住"改成"测试盯着"。写这条测试时 package.ps1 已经修好，
 * 它的红是手工撞出来的那三次 —— 见 decisions.md 2026-10-07。
 */
class ScriptsAreAsciiTest {

  @Test
  void everyScriptIsPureAscii() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    List<String> offenders = new ArrayList<>();
    List<Path> checked = new ArrayList<>();
    try (var stream = Files.walk(root, 3)) {
      for (Path p : stream.filter(Files::isRegularFile).toList()) {
        String name = p.getFileName().toString();
        if (!(name.endsWith(".ps1") || name.endsWith(".cmd") || name.endsWith(".bat"))) {
          continue;
        }
        if (p.startsWith(root.resolve("target"))) {
          continue;   // 构建产物里可能有临时脚本
        }
        checked.add(p);
        String body = Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i < body.length(); i++) {
          if (body.charAt(i) > 0x7F) {
            offenders.add(p.getFileName() + ":" + (i + 1));
            break;
          }
        }
      }
    }
    assertFalse(checked.isEmpty(), "一个脚本都没扫到，说明这条测试在空转");
    assertTrue(offenders.isEmpty(), "这些脚本含非 ASCII 字节，PowerShell 5.1 会解析失败：" + offenders);
  }
}
