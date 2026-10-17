package desktimer.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import desktimer.core.time.EntryKind;
import desktimer.core.time.EntryStatus;
import desktimer.core.time.Recovery.Cause;
import desktimer.core.time.Recovery.EndPrecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 代码里的枚举取值域必须和已冻结的 schema CHECK 一致。
 * 漂移的后果是写入被 CHECK 拒绝，而且是在运行期、在用户按下回车的时候。
 */
class SchemaContractTest {

  private static String ddl;

  @BeforeAll
  static void load() throws Exception {
    ddl = Migrations.readResource("/schema/001_init.sql");
  }

  @Test
  void allStatusesAreDeclared() {
    for (EntryStatus s : EntryStatus.values()) {
      assertTrue(ddl.contains("'" + s.wire() + "'"), "schema 里没有 status=" + s.wire());
    }
  }

  @Test
  void allKindsAreDeclared() {
    for (EntryKind k : EntryKind.values()) {
      assertTrue(ddl.contains("'" + k.wire() + "'"), "schema 里没有 kind=" + k.wire());
    }
  }

  @Test
  void allCausesAreDeclared() {
    for (Cause c : Cause.values()) {
      assertTrue(ddl.contains("'" + c.name().toLowerCase() + "'"), "schema 里没有 cause=" + c);
    }
  }

  @Test
  void allEndPrecisionsAreDeclared() {
    for (EndPrecision p : EndPrecision.values()) {
      assertTrue(ddl.contains("'" + p.wire() + "'"), "schema 里没有 end_precision=" + p.wire());
    }
  }

  @Test
  void supersededLineageColumnExists() {
    assertTrue(ddl.contains("superseded_by"), "合并/拆分的血缘列不能少");
    assertTrue(ddl.contains("overlap_waived"), "放行重叠的列不能少");
  }
}
