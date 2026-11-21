package dev.resurrection.api;

import dev.resurrection.domain.ReplayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import org.slf4j.MDC;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ReplayException.class)
    public ResponseEntity<Map<String, String>> unsafeHistory(ReplayException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(
                "Recovery blocked: " + error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> httpError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(errorBody(error.getReason() == null ? "Request failed" : error.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        String requestId = MDC.get("requestId");
        return requestId == null ? Map.of("error", message)
                : Map.of("error", message, "requestId", requestId);
    }
}
