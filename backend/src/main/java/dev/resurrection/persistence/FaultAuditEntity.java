package dev.resurrection.persistence;

import dev.resurrection.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Fault injection is recorded separately so a deliberately corrupted projection stays detectable. */
@Entity
@Table(name = "fault_audits")
public class FaultAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "workflow_id", nullable = false) public UUID workflowId;
    @Column(nullable = false, length = 100) public String actor;
    @Column(nullable = false, length = 40) public String type;
    @Column(name = "before_step", nullable = false) public int beforeStep;
    @Enumerated(EnumType.STRING) @Column(name = "before_status", nullable = false, length = 20)
    public WorkflowStatus beforeStatus;
    @Column(name = "created_at", nullable = false) public Instant createdAt;

    protected FaultAuditEntity() { }

    public FaultAuditEntity(UUID workflowId, String actor, String type, int beforeStep,
                            WorkflowStatus beforeStatus) {
        this.workflowId = workflowId;
        this.actor = actor;
        this.type = type;
        this.beforeStep = beforeStep;
        this.beforeStatus = beforeStatus;
        this.createdAt = Instant.now();
    }
}
