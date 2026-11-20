package dev.resurrection.service;

import dev.resurrection.domain.*;
import dev.resurrection.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class WorkflowService {
    private final WorkflowRepository workflows;
    private final EventRepository events;
    private final CheckpointRepository checkpoints;
    private final CommandRepository commands;
    private final RecoveryApprovalRepository approvals;
    private final FaultAuditRepository faults;
    private final boolean faultsEnabled;

    public WorkflowService(WorkflowRepository workflows, EventRepository events,
                           CheckpointRepository checkpoints, CommandRepository commands,
                           RecoveryApprovalRepository approvals, FaultAuditRepository faults,
                           @Value("${app.faults-enabled:true}") boolean faultsEnabled) {
        this.workflows = workflows;
        this.events = events;
        this.checkpoints = checkpoints;
        this.commands = commands;
        this.approvals = approvals;
        this.faults = faults;
        this.faultsEnabled = faultsEnabled;
    }

    public record WorkflowView(UUID id, String name, String step, int stepNumber,
                               WorkflowStatus status, long lastSequence, boolean failNextStep,
                               Instant createdAt) { }
    public record EventView(long sequence, EventType type, String message, Instant createdAt,
                            String hash) { }
    public record ApprovalView(long id, String actor, String action, long approvedSequence,
                               int beforeStep, WorkflowStatus beforeStatus,
                               int afterStep, WorkflowStatus afterStatus, Instant approvedAt) { }
    public record FaultView(long id, String actor, String type, int beforeStep,
                            WorkflowStatus beforeStatus, Instant createdAt) { }
    public record Detail(WorkflowView workflow, List<EventView> events, int checkpointCount,
                         List<ApprovalView> approvals, List<FaultView> faultAudits) { }
    public record HealthView(UUID id, String name, String state, String reason,
                             String step, WorkflowStatus status, long lastSequence,
                             Instant createdAt) { }
    public record ActivityView(UUID workflowId, String workflowName, long sequence,
                               EventType type, String message, Instant createdAt) { }
    public record OperationsView(Instant checkedAt, long total, long active, long failed,
                                 long completed, long attention, long integrityErrors,
                                 long totalEvents, List<HealthView> workflows,
                                 List<ActivityView> recentActivity) { }
    public record Preview(boolean projectionMatches, String diagnosis, String action,
                          String expectedStep, WorkflowStatus expectedStatus, long expectedSequence,
                          boolean expectedFaultArmed, String expectedHash,
                          String projectionFingerprint, int verifiedCheckpoints,
                          String simulatedStep, WorkflowStatus simulatedStatus,
                          List<EventView> causalTrace) { }

    public boolean faultsEnabled() { return faultsEnabled; }

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
        var history = events.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(this::eventView).toList();
        var audits = approvals.findByWorkflowIdOrderByApprovedAtAscIdAsc(id).stream()
                .map(a -> new ApprovalView(a.id, a.actor, a.action, a.approvedSequence,
                        a.beforeStep, a.beforeStatus, a.afterStep, a.afterStatus, a.approvedAt)).toList();
        var injections = faults.findByWorkflowIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(a -> new FaultView(a.id, a.actor, a.type, a.beforeStep,
                        a.beforeStatus, a.createdAt)).toList();
        return new Detail(view(workflow), history,
                checkpoints.findByWorkflowIdOrderBySequenceAsc(id).size(), audits, injections);
    }

    /** A fleet-wide read checks stored projections against verified history, not just status flags. */
    @Transactional(readOnly = true)
    public OperationsView operations() {
        var all = workflows.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        Map<UUID, WorkflowEntity> byId = all.stream().collect(Collectors.toMap(w -> w.id, Function.identity()));
        var health = all.stream().map(w -> {
            try {
                var result = replay(w.id);
                var state = result.state();
                if (w.step != state.step() || w.status != state.status()
                        || w.lastSequence != result.lastSequence()
                        || w.failNextStep != result.expectedFaultArmed()) {
                    return new HealthView(w.id, w.name, "DRIFTED", "Stored state differs from verified event history",
                            state.stepName(), state.status(), result.lastSequence(), w.createdAt);
                }
                if (w.status == WorkflowStatus.FAILED) {
                    return new HealthView(w.id, w.name, "FAILED", "A transition failed; approval is available",
                            state.stepName(), state.status(), result.lastSequence(), w.createdAt);
                }
                if (result.expectedFaultArmed()) {
                    return new HealthView(w.id, w.name, "ARMED", "A simulated failure is queued for the next transition",
                            state.stepName(), state.status(), result.lastSequence(), w.createdAt);
                }
                return new HealthView(w.id, w.name, "HEALTHY", "Event history and stored state agree",
                        state.stepName(), state.status(), result.lastSequence(), w.createdAt);
            } catch (ReplayException e) {
                // Never attempt recovery if the underlying evidence cannot be verified.
                return new HealthView(w.id, w.name, "HISTORY_INVALID", e.getMessage(),
                        "UNVERIFIED", w.status, w.lastSequence, w.createdAt);
            }
        }).toList();
        var recent = events.findTop12ByOrderByCreatedAtDescIdDesc().stream()
                .map(e -> new ActivityView(e.workflowId,
                        byId.containsKey(e.workflowId) ? byId.get(e.workflowId).name : "Unknown workflow",
                        e.sequence, e.type, e.message, e.createdAt)).toList();
        long active = all.stream().filter(w -> w.status == WorkflowStatus.ACTIVE).count();
        long failed = all.stream().filter(w -> w.status == WorkflowStatus.FAILED).count();
        long completed = all.stream().filter(w -> w.status == WorkflowStatus.COMPLETED).count();
        long attention = health.stream().filter(h -> !h.state().equals("HEALTHY")).count();
        long invalid = health.stream().filter(h -> h.state().equals("HISTORY_INVALID")).count();
        long totalEvents = events.count();
        return new OperationsView(Instant.now(), all.size(), active, failed, completed,
                attention, invalid, totalEvents, health, recent);
    }

    /** A request key makes network retries safe even after the first response is lost. */
    @Transactional
    public WorkflowView advance(UUID id, UUID requestKey) {
        WorkflowEntity workflow = locked(id);
        if (requestKey != null) {
            var previous = commands.findById(requestKey);
            if (previous.isPresent()) {
                CommandEntity saved = previous.get();
                if (!saved.workflowId.equals(id) || !"ADVANCE".equals(saved.action))
                    throw conflict("Idempotency-Key was already used for another command");
                return new WorkflowView(id, workflow.name,
                        new WorkflowState(saved.step, saved.status).stepName(), saved.step,
                        saved.status, saved.resultingSequence, saved.failNextStep, workflow.createdAt);
            }
        }
        requireConsistent(workflow);
        if (workflow.status != WorkflowStatus.ACTIVE)
            throw conflict("Only ACTIVE workflows can advance");
        if (workflow.failNextStep) {
            workflow.failNextStep = false;
            append(workflow, EventType.STEP_FAILED,
                    "Injected failure before transition from " + currentState(workflow).stepName());
            workflow.status = WorkflowStatus.FAILED;
        } else {
            WorkflowState next = ReplayEngine.apply(currentState(workflow), EventType.ADVANCED);
            append(workflow, EventType.ADVANCED, "Committed next workflow transition");
            workflow.step = next.step();
            workflow.status = next.status();
        }
        maybeCheckpoint(workflow);
        if (requestKey != null) commands.save(new CommandEntity(requestKey, workflow));
        return view(workflow);
    }

    // The seed calls this overload through the Spring proxy, so it needs its own transaction.
    @Transactional
    public WorkflowView advance(UUID id) { return advance(id, null); }

    @Transactional
    public WorkflowView injectFault(UUID id, String type, String actor) {
        if (!faultsEnabled)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fault injection is disabled on this deployment");
        WorkflowEntity workflow = locked(id);
        requireConsistent(workflow);
        if (!"FAIL_NEXT_STEP".equals(type) && !"CORRUPT_PROJECTION".equals(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown fault type");
        // This audit lives outside the hash chain: corruption is meant to remain detectable.
        faults.save(new FaultAuditEntity(id, actor, type, workflow.step, workflow.status));
        if ("FAIL_NEXT_STEP".equals(type)) {
            if (workflow.status != WorkflowStatus.ACTIVE || workflow.failNextStep)
                throw conflict("A fault can be armed only once on an ACTIVE workflow");
            append(workflow, EventType.FAULT_ARMED, "Operator armed a one-time failure for the next step");
            workflow.failNextStep = true;
            maybeCheckpoint(workflow);
        } else {
            // This deliberately violates the projection, without adding an event.
            workflow.step = workflow.step == 0 ? 1 : 0;
        }
        return view(workflow);
    }

    // Seeded examples are already trusted operator-owned demo scenarios.
    @Transactional
    public WorkflowView injectFault(UUID id, String type) { return injectFault(id, type, "demo-seed"); }

    @Transactional(readOnly = true)
    public Preview preview(UUID id) {
        WorkflowEntity workflow = get(id);
        return previewFor(workflow, replay(workflow.id));
    }

    @Transactional
    public WorkflowView approveRecovery(UUID id, String action, long expectedSequence,
                                        String expectedHash, String projectionFingerprint,
                                        String actor) {
        WorkflowEntity workflow = locked(id);
        ReplayEngine.Result result = replay(id);
        Preview proposal = previewFor(workflow, result);
        if ("NONE".equals(action) || !proposal.action().equals(action)
                || proposal.expectedSequence() != expectedSequence
                || !proposal.expectedHash().equals(expectedHash)
                || !proposal.projectionFingerprint().equals(projectionFingerprint)) {
            throw conflict("Recovery approval is stale. Inspect again before approving.");
        }
        int beforeStep = workflow.step;
        WorkflowStatus beforeStatus = workflow.status;
        if ("REBUILD_PROJECTION".equals(action)) {
            workflow.step = result.state().step();
            workflow.status = result.state().status();
            workflow.lastSequence = result.lastSequence();
            // A pending fault is reconstructed from events, not quietly discarded.
            workflow.failNextStep = result.expectedFaultArmed();
            append(workflow, EventType.PROJECTION_REPAIRED,
                    "Operator " + actor + " approved restoring the projection from verified history");
        } else if ("RESUME_AFTER_FAILURE".equals(action)) {
            append(workflow, EventType.RECOVERED,
                    "Operator " + actor + " approved retrying the failed workflow");
            workflow.status = WorkflowStatus.ACTIVE;
            workflow.failNextStep = false;
        } else {
            throw conflict("Unsupported recovery action");
        }
        maybeCheckpoint(workflow);
        approvals.save(new RecoveryApprovalEntity(id, actor, action, expectedSequence,
                result.lastHash(), beforeStep, beforeStatus, workflow.step, workflow.status));
        return view(workflow);
    }

    private Preview previewFor(WorkflowEntity workflow, ReplayEngine.Result result) {
        WorkflowState expected = result.state();
        boolean matches = workflow.step == expected.step()
                && workflow.status == expected.status()
                && workflow.lastSequence == result.lastSequence()
                && workflow.failNextStep == result.expectedFaultArmed();
        String action;
        String diagnosis;
        if (!matches) {
            action = "REBUILD_PROJECTION";
            diagnosis = "Projection drift: stored " + currentState(workflow).stepName() + "/" + workflow.status
                    + " (fault armed=" + workflow.failNextStep + "), replayed " + expected.stepName() + "/"
                    + expected.status() + " (fault armed=" + result.expectedFaultArmed() + ").";
        } else if (expected.status() == WorkflowStatus.FAILED) {
            action = "RESUME_AFTER_FAILURE";
            diagnosis = "Verified STEP_FAILED in the causal timeline. The last committed step is "
                    + expected.stepName() + ". You can approve a retry.";
        } else {
            action = "NONE";
            diagnosis = "Event history, checkpoints, and the full stored projection are consistent.";
        }
        // Dry-run proposed state change without writing to the database.
        WorkflowState simulated = switch (action) {
            case "REBUILD_PROJECTION" -> ReplayEngine.apply(expected, EventType.PROJECTION_REPAIRED);
            case "RESUME_AFTER_FAILURE" -> ReplayEngine.apply(expected, EventType.RECOVERED);
            default -> expected;
        };
        var all = events.findByWorkflowIdOrderBySequenceAsc(workflow.id);
        var trace = all.subList(Math.max(0, all.size() - 6), all.size()).stream()
                .map(this::eventView).toList();
        return new Preview(matches, diagnosis, action, expected.stepName(), expected.status(),
                result.lastSequence(), result.expectedFaultArmed(), result.lastHash(),
                ReplayEngine.projectionFingerprint(workflow.id, workflow.step, workflow.status,
                        workflow.lastSequence, workflow.failNextStep), result.verifiedCheckpoints(),
                simulated.stepName(), simulated.status(), trace);
    }

    private void requireConsistent(WorkflowEntity workflow) {
        if (!previewFor(workflow, replay(workflow.id)).projectionMatches())
            throw conflict("Projection drift detected. Inspect and approve recovery before continuing.");
    }

    private ReplayEngine.Result replay(UUID id) {
        var history = events.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(EventEntity::toRecord).toList();
        var saved = checkpoints.findByWorkflowIdOrderBySequenceAsc(id).stream()
                .map(CheckpointEntity::toRecord).toList();
        return ReplayEngine.replay(id, history, saved);
    }

    private void append(WorkflowEntity workflow, EventType type, String message) {
        String previousHash = events.findFirstByWorkflowIdOrderBySequenceDesc(workflow.id)
                .map(event -> event.hash).orElse(ReplayEngine.GENESIS);
        long sequence = workflow.lastSequence + 1;
        var unsigned = new ReplayEngine.Event(workflow.id, sequence, type, message, previousHash, "");
        var event = new ReplayEngine.Event(workflow.id, sequence, type, message,
                previousHash, ReplayEngine.hashEvent(unsigned));
        events.save(new EventEntity(event));
        workflow.lastSequence = sequence;
    }

    private void maybeCheckpoint(WorkflowEntity workflow) {
        if (workflow.lastSequence % 2 != 0) return;
        String lastHash = events.findFirstByWorkflowIdOrderBySequenceDesc(workflow.id)
                .orElseThrow().hash;
        var unsigned = new ReplayEngine.Checkpoint(workflow.lastSequence,
                workflow.step, workflow.status, lastHash, "");
        checkpoints.save(new CheckpointEntity(workflow.id, new ReplayEngine.Checkpoint(
                unsigned.sequence(), unsigned.step(), unsigned.status(), lastHash,
                ReplayEngine.checkpointChecksum(workflow.id, unsigned))));
    }

    private EventView eventView(EventEntity event) {
        return new EventView(event.sequence, event.type, event.message, event.createdAt, event.hash);
    }

    private WorkflowState currentState(WorkflowEntity w) { return new WorkflowState(w.step, w.status); }

    private WorkflowView view(WorkflowEntity w) {
        return new WorkflowView(w.id, w.name, currentState(w).stepName(), w.step,
                w.status, w.lastSequence, w.failNextStep, w.createdAt);
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
