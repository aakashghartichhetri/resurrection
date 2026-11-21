package dev.resurrection.service;

import dev.resurrection.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionSafetyTest {
    @Test
    void injectionCannotRunWhenFlagDisabled() {
        var service = new WorkflowService(mock(WorkflowRepository.class), mock(EventRepository.class),
                mock(CheckpointRepository.class), mock(CommandRepository.class), mock(org.springframework.jdbc.core.JdbcTemplate.class), mock(ReplayTelemetry.class),
                mock(RecoveryApprovalRepository.class), mock(FaultAuditRepository.class), false);
        var exception = assertThrows(ResponseStatusException.class,
                () -> service.injectFault(java.util.UUID.randomUUID(), "CORRUPT_PROJECTION"));
        assertEquals(403, exception.getStatusCode().value());
    }
}
