package dev.resurrection.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RecoveryApprovalRepository extends JpaRepository<RecoveryApprovalEntity, Long> {
    List<RecoveryApprovalEntity> findByWorkflowIdOrderByApprovedAtAscIdAsc(UUID workflowId);
}
