package dev.resurrection.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<EventEntity, Long> {
    List<EventEntity> findByWorkflowIdOrderBySequenceAsc(UUID workflowId);
    Optional<EventEntity> findFirstByWorkflowIdOrderBySequenceDesc(UUID workflowId);
}
