package com.scooterrentalkandy.spring.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7)).ifPresent(claims -> {
                Role role = parseRole(claims.get("role", String.class));
                if (role == null) {
                    // Issued before the role model changed (e.g. CUSTOMER): treat as unauthenticated.
                    return;
                }
                UUID userId = parseId(claims.getSubject());
                if (userId == null) {
                    // Issued before user ids became UUIDs (numeric subject): treat as unauthenticated.
                    return;
                }
                // The signed subject identifies the account; whether it may act, and as what, is the account's
                // current state. Deleted or deactivated users are unauthenticated, and a changed role or email
                // applies to tokens issued before the change.
                users.findById(userId)
                        .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                        .ifPresent(user -> {
                            AuthUser principal = new AuthUser(user.getUserId(), user.getEmail(), user.getRole());
                            var auth = new UsernamePasswordAuthenticationToken(principal, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        });
            });
        }
        chain.doFilter(request, response);
    }

    private static UUID parseId(String subject) {
        try {
            return subject == null ? null : UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Role parseRole(String value) {
        try {
            return value == null ? null : Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
