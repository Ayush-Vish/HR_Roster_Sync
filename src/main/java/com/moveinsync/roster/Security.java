package com.moveinsync.roster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
    @Bean
    UserDetailsService users(@Value("${roster.password}") String password) {
        var users = new InMemoryUserDetailsManager();
        var encoded = new BCryptPasswordEncoder().encode(password);
        for (String tenant : new String[] {"acme", "globex"})
            for (String role : new String[] {"HR", "ADMIN", "OPERATOR"})
                users.createUser(
                        User.withUsername(tenant + "-" + role.toLowerCase())
                                .password(encoded)
                                .roles(role)
                                .authorities("ROLE_" + role, "TENANT_" + tenant)
                                .build());
        return users;
    }

    @Bean
    org.springframework.security.crypto.password.PasswordEncoder encoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filter(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/health")
                                        .permitAll()
                                        .requestMatchers("/actuator/**")
                                        .hasRole("OPERATOR")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/events",
                                                "/api/snapshots",
                                                "/api/snapshots/*/resume")
                                        .hasRole("HR")
                                        .requestMatchers(
                                                "/api/snapshots/*/approve", "/api/outbox/**")
                                        .hasRole("OPERATOR")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.PATCH,
                                                "/api/employees/*/transport")
                                        .hasRole("ADMIN")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/bookings")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    public static String tenant(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(Object::toString)
                .filter(a -> a.startsWith("TENANT_"))
                .map(a -> a.substring(7))
                .findFirst()
                .orElseThrow(
                        () ->
                                new org.springframework.security.access.AccessDeniedException(
                                        "Forbidden"));
    }
}
