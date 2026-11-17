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
            var clean = workflows.create("Demo: Parcel A-104");
            workflows.advance(clean.id());
            var failed = workflows.create("Demo: Parcel B-205");
            workflows.advance(failed.id());
            workflows.injectFault(failed.id(), "FAIL_NEXT_STEP");
            workflows.advance(failed.id());
        };
    }
}
