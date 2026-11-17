package dev.resurrection.persistence;

import dev.resurrection.domain.ReplayEngine;
import dev.resurrection.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "workflow_checkpoints", uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "sequence_number"}))
public class CheckpointEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "workflow_id", nullable = false) public UUID workflowId;
    @Column(name = "sequence_number", nullable = false) public long sequence;
    @Column(nullable = false) public int step;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public WorkflowStatus status;
    @Column(nullable = false, length = 100) public String lastEventHash;
    @Column(nullable = false, length = 100) public String checksum;

    protected CheckpointEntity() { }

    public CheckpointEntity(UUID workflowId, ReplayEngine.Checkpoint snapshot) {
        this.workflowId = workflowId;
        this.sequence = snapshot.sequence();
        this.step = snapshot.step();
        this.status = snapshot.status();
        this.lastEventHash = snapshot.lastEventHash();
        this.checksum = snapshot.checksum();
    }

    public ReplayEngine.Checkpoint toRecord() {
        return new ReplayEngine.Checkpoint(sequence, step, status, lastEventHash, checksum);
    }
}
