package desktimer.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransitionsTest {

  @Test
  void normalLifecycleIsLegal() {
    assertTrue(Transitions.canMove(EntryStatus.RUNNING, EntryStatus.PAUSED));
    assertTrue(Transitions.canMove(EntryStatus.PAUSED, EntryStatus.RUNNING));
    assertTrue(Transitions.canMove(EntryStatus.RUNNING, EntryStatus.FINISHED));
    assertTrue(Transitions.canMove(EntryStatus.FINISHED, EntryStatus.SUPERSEDED));
  }

  @Test
  void interruptedIsNotFinalItGoesThroughReconciliation() {
    assertTrue(Transitions.canMove(EntryStatus.RUNNING, EntryStatus.INTERRUPTED));
    assertTrue(Transitions.canMove(EntryStatus.INTERRUPTED, EntryStatus.FINISHED));
    assertTrue(Transitions.canMove(EntryStatus.INTERRUPTED, EntryStatus.ABORTED));
  }

  @Test
  void resurrectingFinishedOrTouchingFinalStatesIsIllegal() {
    assertFalse(Transitions.canMove(EntryStatus.FINISHED, EntryStatus.RUNNING));
    assertFalse(Transitions.canMove(EntryStatus.ABORTED, EntryStatus.FINISHED));
    assertFalse(Transitions.canMove(EntryStatus.RUNNING, EntryStatus.SUPERSEDED));
    assertTrue(Transitions.allowedFrom(EntryStatus.SUPERSEDED).isEmpty());
  }

  @Test
  void requireThrowsWithBothStatesInMessage() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> Transitions.require(EntryStatus.FINISHED, EntryStatus.RUNNING));
    assertTrue(ex.getMessage().contains("finished"), ex.getMessage());
    assertTrue(ex.getMessage().contains("running"), ex.getMessage());
  }

  @Test
  void wireValuesRoundTrip() {
    for (EntryStatus s : EntryStatus.values()) {
      assertEquals(s, EntryStatus.parse(s.wire()));
    }
    assertThrows(IllegalArgumentException.class, () -> EntryStatus.parse("done"));
  }
}
