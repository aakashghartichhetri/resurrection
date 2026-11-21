package dev.resurrection.service;

import dev.resurrection.domain.EventType;
import dev.resurrection.domain.ReplayEngine;
import dev.resurrection.domain.ReplayException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayTelemetryTest {
    @Test
    void recordsSuccessfulAndRejectedReplaysWithoutWorkflowIdTags() {
        UUID id = UUID.randomUUID();
        var unsigned = new ReplayEngine.Event(id, 1, EventType.CREATED, "created",
                ReplayEngine.GENESIS, "");
        var valid = new ReplayEngine.Event(id, 1, EventType.CREATED, "created",
                ReplayEngine.GENESIS, ReplayEngine.hashEvent(unsigned));
        try (var registry = new SimpleMeterRegistry()) {
            var telemetry = new ReplayTelemetry(registry);
            assertEquals(1, telemetry.verify(id, List.of(valid), List.of()).lastSequence());
            assertThrows(ReplayException.class, () -> telemetry.verify(id,
                    List.of(new ReplayEngine.Event(id, 2, EventType.CREATED, "created",
                            ReplayEngine.GENESIS, valid.hash())), List.of()));
            assertEquals(1d, registry.get("resurrection.replay.count")
                    .tag("outcome", "verified").counter().count());
            assertEquals(1d, registry.get("resurrection.replay.count")
                    .tag("outcome", "invalid").counter().count());
            assertEquals(1L, registry.get("resurrection.replay.duration")
                    .tag("outcome", "invalid").timer().count());
        }
    }
}
