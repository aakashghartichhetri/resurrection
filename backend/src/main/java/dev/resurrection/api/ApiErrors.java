package dev.resurrection.api;

import dev.resurrection.domain.ReplayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ReplayException.class)
    public ResponseEntity<Map<String, String>> unsafeHistory(ReplayException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                "Recovery blocked: " + error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> httpError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("error", error.getReason() == null ? "Request failed" : error.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
