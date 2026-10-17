package desktimer.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static desktimer.diagnostics.RuntimeDiagnostics.Operation.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeDiagnosticsTest {
  @Test void rotationIsBoundedAndFailureDoesNotLeakPayload(@TempDir Path dir) throws Exception {
    var previous = Thread.getDefaultUncaughtExceptionHandler();
    try (var log = new RuntimeDiagnostics(dir, 256, 3)) {
      for (int i = 0; i < 100; i++) RuntimeDiagnostics.failure(EXPORT, new IllegalStateException("secret task C:/private note"));
      assertTrue(RuntimeDiagnostics.status().contains("EXPORT"));
      RuntimeDiagnostics.ok(EXPORT);
      assertFalse(RuntimeDiagnostics.status().contains("EXPORT="));
    }
    assertSame(previous, Thread.getDefaultUncaughtExceptionHandler());
    try (var files = Files.list(dir)) {
      var paths = files.filter(p -> p.toString().endsWith(".log")).toList();
      assertEquals(3, paths.size());
      String all = "";
      for (var path : paths) {
        assertTrue(Files.size(path) < 512, "rotation may exceed limit by one bounded record");
        all += Files.readString(path);
      }
      assertTrue(all.contains("EXPORT recovered"));
      assertFalse(all.contains("secret")); assertFalse(all.contains("private"));
    }
  }

  @Test void unwritableLogTargetDoesNotPreventStartup(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("file"), "occupied");
    try (var log = RuntimeDiagnostics.open(file)) {
      RuntimeDiagnostics.ok(STARTUP);
      assertTrue(RuntimeDiagnostics.status().contains("不可用"));
    }
  }

  @Test void uncaughtExceptionsAreRedactedAndHealthyHeartbeatsStayQuiet(@TempDir Path dir) throws Exception {
    try (var log = RuntimeDiagnostics.open(dir)) {
      for (int i = 0; i < 100; i++) RuntimeDiagnostics.ok(HEARTBEAT);
      Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), new RuntimeException("private content"));
    }
    String text = Files.readString(dir.resolve("runtime-0.log"));
    assertTrue(text.contains("UNCAUGHT failed"));
    assertFalse(text.contains("private")); assertFalse(text.contains("HEARTBEAT"));
  }
}
