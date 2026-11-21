package dev.resurrection.service;

import dev.resurrection.domain.ReplayEngine;
import dev.resurrection.domain.ReplayException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Low-cardinality replay measurements: never use a workflow ID as a metric tag. */
@Component
public class ReplayTelemetry {
    private final MeterRegistry metrics;

    public ReplayTelemetry(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public ReplayEngine.Result verify(UUID id, List<ReplayEngine.Event> events,
                                      List<ReplayEngine.Checkpoint> checkpoints) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "verified";
        try {
            return ReplayEngine.replay(id, events, checkpoints);
        } catch (ReplayException error) {
            outcome = "invalid";
            throw error;
        } finally {
            sample.stop(Timer.builder("resurrection.replay.duration")
                    .description("Time to replay and validate one workflow history")
                    .tag("outcome", outcome).register(metrics));
            metrics.counter("resurrection.replay.count", "outcome", outcome).increment();
        }
    }
}
