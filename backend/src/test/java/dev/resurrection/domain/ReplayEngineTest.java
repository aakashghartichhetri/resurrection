package dev.resurrection.domain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReplayEngineTest {
    private final UUID id = UUID.randomUUID();

    @Test
    void replayIsDeterministicAndVerifiesCheckpoint() {
        var events = history(EventType.CREATED, EventType.ADVANCED, EventType.FAULT_ARMED,
                EventType.STEP_FAILED, EventType.RECOVERED, EventType.ADVANCED);
        var saved = checkpoints(events);
        var first = ReplayEngine.replay(id, events, saved);
        var second = ReplayEngine.replay(id, events, saved);
        assertEquals(first, second);
        assertEquals(new WorkflowState(2, WorkflowStatus.ACTIVE), first.state());
        assertEquals(3, first.verifiedCheckpoints());
        assertEquals(6, first.lastSequence());
    }

    @Test
    void refusesToReplayTamperedEvent() {
        var events = new ArrayList<>(history(EventType.CREATED, EventType.ADVANCED));
        ReplayEngine.Event original = events.get(1);
        events.set(1, new ReplayEngine.Event(id, original.sequence(), original.type(),
                "Altered message", original.previousHash(), original.hash()));
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of()));
    }

    @Test
    void refusesMissingEventsAndInvalidStateTransitions() {
        var events = new ArrayList<>(history(EventType.CREATED, EventType.ADVANCED));
        events.removeFirst();
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of()));
        assertThrows(ReplayException.class, () -> ReplayEngine.apply(
                new WorkflowState(3, WorkflowStatus.COMPLETED), EventType.ADVANCED));
    }

    @Test
    void refusesDeletedCheckpointEvenWhenEventsHaveValidHashes() {
        var events = history(EventType.CREATED, EventType.ADVANCED, EventType.FAULT_ARMED,
                EventType.STEP_FAILED);
        var saved = checkpoints(events);
        assertEquals(2, ReplayEngine.replay(id, events, saved).verifiedCheckpoints());
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of(saved.get(0))));
    }

    @Test
    void refusesCorruptedCheckpoint() {
        var events = history(EventType.CREATED, EventType.ADVANCED);
        var good = checkpoint(events, 2);
        var broken = new ReplayEngine.Checkpoint(good.sequence(), 3, good.status(),
                good.lastEventHash(), good.checksum());
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of(broken)));
    }

    @Test
    void reconstructsPendingFaultAcrossACheckpoint() {
        var events = history(EventType.CREATED, EventType.ADVANCED, EventType.FAULT_ARMED);
        assertTrue(ReplayEngine.replay(id, events, checkpoints(events)).expectedFaultArmed());
    }

    @Test
    void rejectsFailureWithoutAnArmedFault() {
        var events = history(EventType.CREATED, EventType.STEP_FAILED);
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of()));
    }

    @Test
    void rejectsAdvancingPastAnArmedFault() {
        var events = history(EventType.CREATED, EventType.FAULT_ARMED, EventType.ADVANCED);
        assertThrows(ReplayException.class, () -> ReplayEngine.replay(id, events, List.of()));
    }

    private List<ReplayEngine.Event> history(EventType... types) {
        var result = new ArrayList<ReplayEngine.Event>();
        String prev = ReplayEngine.GENESIS;
        for (EventType type : types) {
            var unsigned = new ReplayEngine.Event(id, result.size() + 1, type, "message", prev, "");
            var event = new ReplayEngine.Event(id, unsigned.sequence(), type, "message", prev,
                    ReplayEngine.hashEvent(unsigned));
            result.add(event);
            prev = event.hash();
        }
        return result;
    }

    private List<ReplayEngine.Checkpoint> checkpoints(List<ReplayEngine.Event> events) {
        var snapshots = new ArrayList<ReplayEngine.Checkpoint>();
        for (int after = 2; after <= events.size(); after += 2) {
            snapshots.add(checkpoint(events, after));
        }
        return snapshots;
    }

    private ReplayEngine.Checkpoint checkpoint(List<ReplayEngine.Event> events, int after) {
        WorkflowState state = null;
        for (var event : events.subList(0, after)) {
            state = ReplayEngine.apply(state, event.type());
        }
        var unsigned = new ReplayEngine.Checkpoint(after, state.step(), state.status(),
                events.get(after - 1).hash(), "");
        return new ReplayEngine.Checkpoint(after, state.step(), state.status(), unsigned.lastEventHash(),
                ReplayEngine.checkpointChecksum(id, unsigned));
    }
}
