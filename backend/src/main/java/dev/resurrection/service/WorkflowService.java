package dev.resurrection.service;

import dev.resurrection.domain.*;
import dev.resurrection.persistence.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkflowService {
    private final WorkflowRepository workflows;
    private final EventRepository events;
    private final CheckpointRepository checkpoints;

    public WorkflowService(WorkflowRepository workflows, EventRepository events,
                           CheckpointRepository checkpoints) {
        this.workflows = workflows;
        this.events = events;
        this.checkpoints = checkpoints;
    }

    public record WorkflowView(UUID id, String name, String step, int stepNumber,
                               WorkflowStatus status, long lastSequence, boolean failNextStep,
                               Instant createdAt) { }
    public record EventView(long sequence, EventType type, String message, Instant createdAt,
                            String hash) { }
    public record Detail(WorkflowView workflow, List<EventView> events, int checkpointCount) { }
    public record Preview(boolean projectionMatches, String diagnosis, String action,
                          String expectedStep, WorkflowStatus expectedStatus, long expectedSequence,
                          int verifiedCheckpoints, String simulatedStep, WorkflowStatus simulatedStatus) { }

    @Transactional
    public WorkflowView create(String name) {
        WorkflowEntity workflow = workflows.saveAndFlush(new WorkflowEntity(name.trim()));
        append(workflow, EventType.CREATED, "Workflow created");
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list() {
        return workflows.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        WorkflowEntity workflow = get(id);
        List<EventView> history = events.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(e -> new EventView(e.sequence, e.type, e.message, e.createdAt, e.hash)).toList();
        return new Detail(view(workflow), history, checkpoints.findByWorkflowIdOrderBySequenceAsc(id).size());
    }

    @Transactional
    public WorkflowView advance(UUID id) {
        WorkflowEntity workflow = locked(id);
        requireConsistent(workflow);
        if (workflow.status != WorkflowStatus.ACTIVE)
            throw conflict("Only ACTIVE workflows can advance");
        if (workflow.failNextStep) {
            workflow.failNextStep = false;
            append(workflow, EventType.STEP_FAILED,
                    "Injected failure before transition from " + new WorkflowState(workflow.step, workflow.status).stepName());
            workflow.status = WorkflowStatus.FAILED;
        } else {
            append(workflow, EventType.ADVANCED, "Committed next workflow transition");
            WorkflowState next = ReplayEngine.apply(new WorkflowState(workflow.step, workflow.status), EventType.ADVANCED);
            workflow.step = next.step();
            workflow.status = next.status();
        }
        // Checkpoints are written after the new state, in the same DB transaction.
        maybeCheckpoint(workflow);
        return view(workflow);
    }

    @Transactional
    public WorkflowView injectFault(UUID id, String type) {
        WorkflowEntity workflow = locked(id);
        requireConsistent(workflow);
        if (!"FAIL_NEXT_STEP".equals(type) && !"CORRUPT_PROJECTION".equals(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown fault type");
        if ("FAIL_NEXT_STEP".equals(type)) {
            if (workflow.status != WorkflowStatus.ACTIVE || workflow.failNextStep)
                throw conflict("Fault can only be armed once on an ACTIVE workflow");
            append(workflow, EventType.FAULT_ARMED, "Operator armed a one-time failure for the next step");
            workflow.failNextStep = true;
            maybeCheckpoint(workflow);
        } else {
            // Deliberately emulate a buggy out-of-band database update. No event is added.
            workflow.step = workflow.step == 0 ? 1 : 0;
        }
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id) {
        WorkflowEntity workflow = get(id);
        return previewFor(workflow, replay(workflow.id));
    }

    @Transactional
    public WorkflowView approveRecovery(UUID id, String action, long expectedSequence) {
        WorkflowEntity workflow = locked(id);
        ReplayEngine.Result result = replay(id);
        Preview proposal = previewFor(workflow, result);
        if (!proposal.action().equals(action) || "NONE".equals(action)
                || proposal.expectedSequence() != expectedSequence)
            throw conflict("Recovery preview is outdated or does not match the approved action");

        if ("REBUILD_PROJECTION".equals(action)) {
            workflow.step = result.state().step();
            workflow.status = result.state().status();
            workflow.lastSequence = result.lastSequence();
            workflow.failNextStep = false;
            append(workflow, EventType.PROJECTION_REPAIRED,
                    "Engineer approved restoring stored state from verified event history");
        } else if ("RESUME_AFTER_FAILURE".equals(action)) {
            append(workflow, EventType.RECOVERED,
                    "Engineer approved resuming after the injected failure");
            workflow.status = WorkflowStatus.ACTIVE;
        } else {
            throw conflict("Unsupported recovery action");
        }
        maybeCheckpoint(workflow);
        return view(workflow);
    }

    private Preview previewFor(WorkflowEntity workflow, ReplayEngine.Result result) {
        WorkflowState expected = result.state();
        boolean matches = workflow.step == expected.step()
                && workflow.status == expected.status()
                && workflow.lastSequence == result.lastSequence();
        String action;
        String diagnosis;
        if (!matches) {
            action = "REBUILD_PROJECTION";
            diagnosis = "Stored projection differs from verified event history: stored "
                    + new WorkflowState(workflow.step, workflow.status).stepName() + "/" + workflow.status
                    + ", expected " + expected.stepName() + "/" + expected.status();
        } else if (expected.status() == WorkflowStatus.FAILED) {
            action = "RESUME_AFTER_FAILURE";
            diagnosis = "The event log identifies an injected failed transition. The last committed step is "
                    + expected.stepName() + "; a human can approve a retry.";
        } else {
            action = "NONE";
            diagnosis = "Event history, checkpoints, and stored projection are consistent.";
        }
        // Dry-run the one proposed action in pure Java before any approval can write.
        WorkflowState simulated = switch (action) {
            case "REBUILD_PROJECTION" -> ReplayEngine.apply(expected, EventType.PROJECTION_REPAIRED);
            case "RESUME_AFTER_FAILURE" -> ReplayEngine.apply(expected, EventType.RECOVERED);
            default -> expected;
        };
        return new Preview(matches, diagnosis, action, expected.stepName(), expected.status(),
                result.lastSequence(), result.verifiedCheckpoints(),
                simulated.stepName(), simulated.status());
    }

    private void requireConsistent(WorkflowEntity workflow) {
        Preview proposal = previewFor(workflow, replay(workflow.id));
        if (!proposal.projectionMatches())
            throw conflict("Projection drift detected. Preview and approve recovery before continuing.");
    }

    private ReplayEngine.Result replay(UUID id) {
        List<ReplayEngine.Event> history = events.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(EventEntity::toRecord).toList();
        List<ReplayEngine.Checkpoint> saved = checkpoints.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(CheckpointEntity::toRecord).toList();
        return ReplayEngine.replay(id, history, saved);
    }

    private void append(WorkflowEntity workflow, EventType type, String message) {
        String previousHash = events.findFirstByWorkflowIdOrderBySequenceDesc(workflow.id)
                .map(event -> event.hash).orElse(ReplayEngine.GENESIS);
        long sequence = workflow.lastSequence + 1;
        ReplayEngine.Event unsigned = new ReplayEngine.Event(workflow.id, sequence, type, message, previousHash, "");
        ReplayEngine.Event event = new ReplayEngine.Event(workflow.id, sequence, type, message,
                previousHash, ReplayEngine.hashEvent(unsigned));
        events.save(new EventEntity(event));
        workflow.lastSequence = sequence;
    }

    private void maybeCheckpoint(WorkflowEntity workflow) {
        if (workflow.lastSequence % 2 != 0) return;
        String lastHash = events.findFirstByWorkflowIdOrderBySequenceDesc(workflow.id)
                .orElseThrow().hash;
        ReplayEngine.Checkpoint unsigned = new ReplayEngine.Checkpoint(workflow.lastSequence,
                workflow.step, workflow.status, lastHash, "");
        checkpoints.save(new CheckpointEntity(workflow.id, new ReplayEngine.Checkpoint(
                unsigned.sequence(), unsigned.step(), unsigned.status(), lastHash,
                ReplayEngine.checkpointChecksum(workflow.id, unsigned))));
    }

    private WorkflowView view(WorkflowEntity workflow) {
        return new WorkflowView(workflow.id, workflow.name,
                new WorkflowState(workflow.step, workflow.status).stepName(), workflow.step,
                workflow.status, workflow.lastSequence, workflow.failNextStep, workflow.createdAt);
    }

    private WorkflowEntity get(UUID id) {
        return workflows.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    }

    private WorkflowEntity locked(UUID id) {
        return workflows.findForUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found"));
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
