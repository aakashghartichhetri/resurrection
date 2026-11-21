package dev.resurrection.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runnable without Maven or JUnit when only the replay safety boundary needs checking. */
public final class ReplaySelfCheck {
    private static final UUID ID = UUID.fromString("4e100fa0-2c5d-4ddd-b7a8-ff0211ce8891");

    public static void main(String[] args) {
        var history = new ArrayList<ReplayEngine.Event>();
        String previous = ReplayEngine.GENESIS;
        for (EventType type : List.of(EventType.CREATED, EventType.ADVANCED,
                EventType.FAULT_ARMED, EventType.STEP_FAILED, EventType.RECOVERED)) {
            var event = new ReplayEngine.Event(ID, history.size() + 1, type, "example", previous, "");
            var signed = new ReplayEngine.Event(ID, event.sequence(), event.type(), event.message(),
                    event.previousHash(), ReplayEngine.hashEvent(event));
            history.add(signed);
            previous = signed.hash();
        }
        var checkpoints = new ArrayList<ReplayEngine.Checkpoint>();
        for (int sequence : List.of(2, 4)) {
            WorkflowState state = null;
            for (var event : history.subList(0, sequence)) state = ReplayEngine.apply(state, event.type());
            var unsigned = new ReplayEngine.Checkpoint(sequence, state.step(), state.status(),
                    history.get(sequence - 1).hash(), "");
            checkpoints.add(new ReplayEngine.Checkpoint(sequence, state.step(), state.status(),
                    unsigned.lastEventHash(), ReplayEngine.checkpointChecksum(ID, unsigned)));
        }
        var first = ReplayEngine.replay(ID, history, checkpoints);
        check(first.equals(ReplayEngine.replay(ID, history, checkpoints)), "non-deterministic replay");
        check(first.state().equals(new WorkflowState(1, WorkflowStatus.ACTIVE)), "wrong state");
        expectFailure(() -> ReplayEngine.replay(ID, history, checkpoints.subList(0, 1)));
        var tampered = new ArrayList<>(history);
        var event = history.get(2);
        tampered.set(2, new ReplayEngine.Event(ID, event.sequence(), event.type(), "changed",
                event.previousHash(), event.hash()));
        expectFailure(() -> ReplayEngine.replay(ID, tampered, checkpoints));
        var reordered = new ArrayList<>(history);
        java.util.Collections.swap(reordered, 1, 2);
        expectFailure(() -> ReplayEngine.replay(ID, reordered, checkpoints));
        System.out.println("PASS: deterministic replay, correct state, missing checkpoint, tampering, reordering");
    }

    private static void check(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }

    private static void expectFailure(Runnable operation) {
        try { operation.run(); }
        catch (ReplayException expected) { return; }
        throw new AssertionError("Unsafe replay was accepted");
    }
}
