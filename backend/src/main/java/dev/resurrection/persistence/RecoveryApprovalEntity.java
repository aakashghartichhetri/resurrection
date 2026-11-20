package dev.resurrection.persistence;

import dev.resurrection.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_approvals")
public class RecoveryApprovalEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "workflow_id", nullable = false) public UUID workflowId;
    @Column(nullable = false, length = 100) public String actor;
    @Column(nullable = false, length = 40) public String action;
    @Column(name = "approved_sequence", nullable = false) public long approvedSequence;
    @Column(name = "history_hash", nullable = false, length = 100) public String historyHash;
    @Column(name = "before_step", nullable = false) public int beforeStep;
    @Enumerated(EnumType.STRING) @Column(name = "before_status", nullable = false, length = 20)
    public WorkflowStatus beforeStatus;
    @Column(name = "after_step", nullable = false) public int afterStep;
    @Enumerated(EnumType.STRING) @Column(name = "after_status", nullable = false, length = 20)
    public WorkflowStatus afterStatus;
    @Column(name = "approved_at", nullable = false) public Instant approvedAt;

    protected RecoveryApprovalEntity() { }

    public RecoveryApprovalEntity(UUID workflowId, String actor, String action, long approvedSequence,
                                  String historyHash, int beforeStep, WorkflowStatus beforeStatus,
                                  int afterStep, WorkflowStatus afterStatus) {
        this.workflowId = workflowId;
        this.actor = actor;
        this.action = action;
        this.approvedSequence = approvedSequence;
        this.historyHash = historyHash;
        this.beforeStep = beforeStep;
        this.beforeStatus = beforeStatus;
        this.afterStep = afterStep;
        this.afterStatus = afterStatus;
        this.approvedAt = Instant.now();
    }
}
