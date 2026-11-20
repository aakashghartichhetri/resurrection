package dev.resurrection.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FaultAuditRepository extends JpaRepository<FaultAuditEntity, Long> {
    List<FaultAuditEntity> findByWorkflowIdOrderByCreatedAtAscIdAsc(UUID workflowId);
}
