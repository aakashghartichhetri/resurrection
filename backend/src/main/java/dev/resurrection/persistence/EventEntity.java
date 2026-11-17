package dev.resurrection.persistence;

import dev.resurrection.domain.EventType;
import dev.resurrection.domain.ReplayEngine;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_events", uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "sequence_number"}))
public class EventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "workflow_id", nullable = false) public UUID workflowId;
    @Column(name = "sequence_number", nullable = false) public long sequence;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) public EventType type;
    @Column(nullable = false, length = 500) public String message;
    @Column(nullable = false, length = 100) public String previousHash;
    @Column(nullable = false, length = 100) public String hash;
    @Column(nullable = false) public Instant createdAt;

    protected EventEntity() { }

    public EventEntity(ReplayEngine.Event event) {
        workflowId = event.workflowId();
        sequence = event.sequence();
        type = event.type();
        message = event.message();
        previousHash = event.previousHash();
        hash = event.hash();
        createdAt = Instant.now();
    }

    public ReplayEngine.Event toRecord() {
        return new ReplayEngine.Event(workflowId, sequence, type, message, previousHash, hash);
    }
}
