package dev.resurrection.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                // This local demo uses explicit Basic credentials, not browser cookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health",
                                "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .anyRequest().hasRole("OPERATOR"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder encoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean
    UserDetailsService users(@Value("${app.username}") String username,
                             @Value("${app.password}") String password,
                             @Value("${app.production:false}") boolean production,
                             PasswordEncoder encoder) {
        if (production && (username.isBlank() || username.length() > 100 || password.length() < 20
                || "dev-only-change-me".equals(password))) {
            throw new IllegalStateException("Production requires a non-empty username and a unique 20+ character password");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("OPERATOR").build());
    }
}
