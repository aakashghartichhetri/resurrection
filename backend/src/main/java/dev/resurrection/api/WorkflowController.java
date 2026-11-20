package dev.resurrection.api;

import dev.resurrection.service.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService service;
    public WorkflowController(WorkflowService service) { this.service = service; }

    public record NewWorkflow(@NotBlank @Size(max = 100) String name) { }
    public record Fault(@NotBlank String type) { }
    public record Approval(@Pattern(regexp = "REBUILD_PROJECTION|RESUME_AFTER_FAILURE") String action,
                           @Min(1) long expectedSequence,
                           @NotBlank String expectedHash,
                           @NotBlank String projectionFingerprint) { }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @GetMapping("/settings")
    public Map<String, Object> settings() { return Map.of("faultInjectionEnabled", service.faultsEnabled()); }

    @GetMapping("/workflows")
    public List<WorkflowService.WorkflowView> list() { return service.list(); }

    @GetMapping("/operations")
    public WorkflowService.OperationsView operations() { return service.operations(); }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowService.WorkflowView create(@Valid @RequestBody NewWorkflow request) {
        return service.create(request.name());
    }

    @GetMapping("/workflows/{id}")
    public WorkflowService.Detail detail(@PathVariable UUID id) { return service.detail(id); }

    @PostMapping("/workflows/{id}/advance")
    public WorkflowService.WorkflowView advance(@PathVariable UUID id,
                    @RequestHeader(value = "Idempotency-Key", required = false) UUID requestKey) {
        return service.advance(id, requestKey);
    }

    @PostMapping("/workflows/{id}/faults")
    public WorkflowService.WorkflowView fault(@PathVariable UUID id, @Valid @RequestBody Fault request,
                                               Authentication authentication) {
        return service.injectFault(id, request.type(), authentication.getName());
    }

    @GetMapping("/workflows/{id}/recovery-preview")
    public WorkflowService.Preview preview(@PathVariable UUID id) { return service.preview(id); }

    @PostMapping("/workflows/{id}/recover")
    public WorkflowService.WorkflowView recover(@PathVariable UUID id, @Valid @RequestBody Approval request,
                                                Authentication authentication) {
        return service.approveRecovery(id, request.action(), request.expectedSequence(),
                request.expectedHash(), request.projectionFingerprint(), authentication.getName());
    }
}
