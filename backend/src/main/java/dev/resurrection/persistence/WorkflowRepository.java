package dev.resurrection.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
    // Database row lock keeps sequence assignment and repair decisions serialized.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.id = :id")
    Optional<WorkflowEntity> findForUpdate(@Param("id") UUID id);
}
