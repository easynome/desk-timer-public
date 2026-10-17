package desktimer.core.time;

import java.util.Arrays;

/**
 * time_entry.status 的取值域。
 * wire() 必须与 schema 的 CHECK 约束一致，有测试把两边钉在一起（防漂移）。
 */
public enum EntryStatus {

  RUNNING("running"),
  PAUSED("paused"),
  FINISHED("finished"),
  ABORTED("aborted"),
  INTERRUPTED("interrupted"),
  SUPERSEDED("superseded");

  private final String wire;

  EntryStatus(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static EntryStatus parse(String value) {
    return Arrays.stream(values())
        .filter(s -> s.wire.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知 status: " + value));
  }
}
