package dev.resurrection.domain;

public record WorkflowState(int step, WorkflowStatus status) {
    public String stepName() {
        return switch (step) {
            case 0 -> "CREATED";
            case 1 -> "VALIDATED";
            case 2 -> "PACKED";
            case 3 -> "SHIPPED";
            default -> throw new IllegalStateException("Unknown step: " + step);
        };
    }
}
