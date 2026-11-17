package dev.resurrection.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CheckpointRepository extends JpaRepository<CheckpointEntity, Long> {
    List<CheckpointEntity> findByWorkflowIdOrderBySequenceAsc(UUID workflowId);
}
