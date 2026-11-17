package dev.resurrection.persistence;

import dev.resurrection.domain.WorkflowStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {
    @Id public UUID id;
    @Column(nullable = false, length = 100) public String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) public WorkflowStatus status;
    @Column(nullable = false) public int step;
    @Column(nullable = false) public long lastSequence;
    @Column(nullable = false) public boolean failNextStep;
    @Column(nullable = false) public Instant createdAt;

    protected WorkflowEntity() { }

    public WorkflowEntity(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.status = WorkflowStatus.ACTIVE;
        this.step = 0;
        this.lastSequence = 0;
        this.createdAt = Instant.now();
    }
}
