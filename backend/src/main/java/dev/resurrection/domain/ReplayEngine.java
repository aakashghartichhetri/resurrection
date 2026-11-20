package dev.resurrection.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Pure Java: no database, clock, or network. Same history always gives the same state. */
public final class ReplayEngine {
    public static final String GENESIS = "GENESIS";

    private ReplayEngine() { }

    public record Event(UUID workflowId, long sequence, EventType type, String message,
                        String previousHash, String hash) { }

    public record Checkpoint(long sequence, int step, WorkflowStatus status,
                             String lastEventHash, String checksum) { }

    public record Result(WorkflowState state, long lastSequence, String lastHash,
                         int verifiedCheckpoints, boolean expectedFaultArmed) { }

    public static Result replay(UUID workflowId, List<Event> events, List<Checkpoint> checkpoints) {
        WorkflowState state = null;
        String previousHash = GENESIS;
        long sequence = 0;
        int checkpointIndex = 0;
        boolean faultArmed = false;
        for (Event event : events) {
            sequence++;
            if (!workflowId.equals(event.workflowId()) || event.sequence() != sequence
                    || !previousHash.equals(event.previousHash())
                    || !hashEvent(event).equals(event.hash())) {
                throw new ReplayException("Event history integrity check failed at sequence " + sequence);
            }
            // Failure injection is itself a state machine; a replay cannot invent a failure.
            if (event.type() == EventType.FAULT_ARMED) {
                if (faultArmed || state == null || state.status() != WorkflowStatus.ACTIVE)
                    throw new ReplayException("Invalid FAULT_ARMED at sequence " + sequence);
                faultArmed = true;
            } else if (event.type() == EventType.STEP_FAILED) {
                if (!faultArmed)
                    throw new ReplayException("STEP_FAILED has no preceding armed fault at sequence " + sequence);
                faultArmed = false;
            } else if (event.type() == EventType.ADVANCED && faultArmed) {
                throw new ReplayException("ADVANCED bypasses an armed fault at sequence " + sequence);
            }
            state = apply(state, event.type());
            previousHash = event.hash();
            // Every second event must have a checkpoint. A deleted checkpoint is an integrity failure,
            // not a reason to silently trust a shorter snapshot history.
            if (sequence % 2 == 0) {
                if (checkpointIndex >= checkpoints.size()
                        || checkpoints.get(checkpointIndex).sequence() != sequence) {
                    throw new ReplayException("Missing checkpoint at sequence " + sequence);
                }
                Checkpoint saved = checkpoints.get(checkpointIndex++);
                if (!saved.checksum().equals(checkpointChecksum(workflowId, saved))
                        || saved.step() != state.step() || saved.status() != state.status()
                        || !saved.lastEventHash().equals(previousHash)) {
                    throw new ReplayException("Checkpoint integrity check failed at sequence " + sequence);
                }
            }
        }
        if (state == null || checkpointIndex != checkpoints.size()) {
            throw new ReplayException("Event history or checkpoint sequence is incomplete");
        }
        return new Result(state, sequence, previousHash, checkpointIndex, faultArmed);
    }

    public static WorkflowState apply(WorkflowState current, EventType type) {
        if (type == EventType.CREATED) {
            if (current != null) throw new ReplayException("Duplicate CREATED event");
            return new WorkflowState(0, WorkflowStatus.ACTIVE);
        }
        if (current == null) throw new ReplayException("First event must be CREATED");
        return switch (type) {
            case ADVANCED -> {
                if (current.status() != WorkflowStatus.ACTIVE || current.step() >= 3)
                    throw new ReplayException("Invalid ADVANCED transition");
                int next = current.step() + 1;
                yield new WorkflowState(next, next == 3 ? WorkflowStatus.COMPLETED : WorkflowStatus.ACTIVE);
            }
            case STEP_FAILED -> {
                if (current.status() != WorkflowStatus.ACTIVE)
                    throw new ReplayException("Invalid STEP_FAILED transition");
                yield new WorkflowState(current.step(), WorkflowStatus.FAILED);
            }
            case RECOVERED -> {
                if (current.status() != WorkflowStatus.FAILED)
                    throw new ReplayException("Invalid RECOVERED transition");
                yield new WorkflowState(current.step(), WorkflowStatus.ACTIVE);
            }
            // Operator commands and repair audit records do not change business state.
            case FAULT_ARMED, PROJECTION_REPAIRED -> current;
            case CREATED -> throw new ReplayException("Duplicate CREATED event");
        };
    }

    public static String hashEvent(Event event) {
        String encodedMessage = Base64.getEncoder().encodeToString(event.message().getBytes(StandardCharsets.UTF_8));
        return sha256(event.workflowId() + "\n" + event.sequence() + "\n" + event.type()
                + "\n" + encodedMessage + "\n" + event.previousHash());
    }

    public static String checkpointChecksum(UUID id, Checkpoint checkpoint) {
        return sha256(id + "\n" + checkpoint.sequence() + "\n" + checkpoint.step()
                + "\n" + checkpoint.status() + "\n" + checkpoint.lastEventHash());
    }

    /** Fingerprint invalidates previews when the stored projection changes. */
    public static String projectionFingerprint(UUID workflowId, int step, WorkflowStatus status,
                                               long sequence, boolean faultArmed) {
        return sha256(workflowId + "\n" + step + "\n" + status + "\n" + sequence + "\n" + faultArmed);
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", error);
        }
    }
}
