package dev.resurrection.config;

import dev.resurrection.persistence.WorkflowRepository;
import dev.resurrection.service.WorkflowService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoSeed {
    @Bean
    @ConditionalOnProperty(name = "app.seed-demo", havingValue = "true", matchIfMissing = true)
    ApplicationRunner seed(WorkflowRepository repository, WorkflowService workflows) {
        return args -> {
            if (repository.count() > 0) return;
            var clean = workflows.create("Dispatch A-104 · North warehouse");
            workflows.advance(clean.id());
            var failed = workflows.create("Dispatch B-205 · Loading dock");
            workflows.advance(failed.id());
            workflows.injectFault(failed.id(), "FAIL_NEXT_STEP");
            workflows.advance(failed.id());
            var shipped = workflows.create("Dispatch C-317 · Express route");
            workflows.advance(shipped.id());
            workflows.advance(shipped.id());
            workflows.advance(shipped.id());
            var drift = workflows.create("Dispatch D-408 · Inventory sync");
            workflows.advance(drift.id());
            workflows.injectFault(drift.id(), "CORRUPT_PROJECTION");
        };
    }
}
