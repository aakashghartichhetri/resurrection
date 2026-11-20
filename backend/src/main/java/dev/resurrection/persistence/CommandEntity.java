package dev.resurrection.persistence;

import dev.resurrection.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** An immutable result for a client-provided idempotency key. */
@Entity
@Table(name = "workflow_commands")
public class CommandEntity {
    @Id public UUID id;
    @Column(name = "workflow_id", nullable = false) public UUID workflowId;
    @Column(nullable = false, length = 40) public String action;
    @Column(nullable = false) public int step;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public WorkflowStatus status;
    @Column(name = "resulting_sequence", nullable = false) public long resultingSequence;
    @Column(name = "fail_next_step", nullable = false) public boolean failNextStep;
    @Column(name = "created_at", nullable = false) public Instant createdAt;

    protected CommandEntity() { }

    public CommandEntity(UUID id, WorkflowEntity workflow) {
        this.id = id;
        workflowId = workflow.id;
        action = "ADVANCE";
        step = workflow.step;
        status = workflow.status;
        resultingSequence = workflow.lastSequence;
        failNextStep = workflow.failNextStep;
        createdAt = Instant.now();
    }
}
