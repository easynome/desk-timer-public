package desktimer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 版本号只许有一个来源，而打包脚本必须与它一致。
 *
 * 起因：版本串原本散在三处（{@code App} 里两份字面量 + {@code package.ps1} 的
 * {@code --app-version}）。准备打 v0.9 tag 时发现 zip 清单里会写 {@code app_version:"0.1.0"} ——
 * 一份备份的清单说自己是 0.1.0 而 exe 是 0.9.0，这种不自洽以后没法解释。
 * 现在常量收成一个，脚本那一头由这条测试逐字读回来比。
 */
class AppVersionPinnedTest {

  @Test
  void packagedAppVersionMatchesTheConstant() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    String script = Files.readString(root.resolve("package.ps1"));
    Matcher m = Pattern.compile("\"--app-version\",\\s*\"([^\"]+)\"").matcher(script);
    assertTrue(m.find(), "package.ps1 里必须还能找到 --app-version 那一项");
    assertEquals(App.APP_VERSION, m.group(1),
        "exe 的版本与代码里写的版本不一致：备份清单会自相矛盾");
  }

  @Test
  void versionLooksLikeAMajorMinorPatch() {
    assertTrue(App.APP_VERSION.matches("\\d+\\.\\d+\\.\\d+"),
        "jpackage 只接受数字版本号，实得=" + App.APP_VERSION);
  }
}
