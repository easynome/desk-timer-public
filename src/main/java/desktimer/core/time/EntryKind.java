package desktimer.core.time;

import java.util.Arrays;

/**
 * time_entry.kind 的取值域，必须与 schema 的 CHECK 一致（有测试钉住）。
 */
public enum EntryKind {

  COUNT_UP("count_up"),
  COUNT_DOWN("count_down"),
  POMODORO_WORK("pomodoro_work"),
  REST_SHORT("rest_short"),
  REST_LONG("rest_long");

  private final String wire;

  EntryKind(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static EntryKind parse(String value) {
    return Arrays.stream(values())
        .filter(k -> k.wire.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知 kind: " + value));
  }
}
