package dev.resurrection.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlate an operator's API failure with logs without recording credentials or request bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                if (request.getRequestURI().startsWith("/api/")) {
                    log.info("HTTP {} {} status={} elapsedMs={}", request.getMethod(),
                            request.getRequestURI(), response.getStatus(),
                            (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
    }
}
