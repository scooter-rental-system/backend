package com.scooterrentalkandy.spring.security;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the HMAC key that signs JWTs, failing closed. There is no built-in secret anywhere:
 * <ul>
 *   <li>production ({@code prod} profile): JWT_SECRET must be set, at least 32 bytes (256 bits) and not a placeholder,
 *       or startup fails;</li>
 *   <li>elsewhere (local development): an unset JWT_SECRET gets a random key for this run only, so nothing usable is
 *       committed; tokens then end when the server restarts. A set but too short secret still fails.</li>
 * </ul>
 * Error messages name the setting, never its value.
 */
final class JwtSecretPolicy {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretPolicy.class);

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_BYTES = 32;

    /** The fallback that application.properties used to ship; it is public, so it can never sign real tokens. */
    static final String FORMER_DEFAULT = "local-dev-secret-change-me-please-0123456789abcdef";

    /** Fragments of example or test values that must not reach production. */
    private static final List<String> PLACEHOLDER_MARKERS = List.of("change-me", "changeme", "not-for-production",
            "test-only", "placeholder", "example", "your-secret");

    private JwtSecretPolicy() {
    }

    static SecretKey resolve(String secret, boolean production) {
        if (secret == null || secret.isBlank()) {
            if (production) {
                throw new IllegalStateException("JWT_SECRET is not set. Production requires a random secret of at "
                        + "least " + MIN_BYTES + " bytes in the environment.");
            }
            log.warn("JWT_SECRET is not set: using a random signing key for this run only. Sign-ins end when the "
                    + "server restarts; set JWT_SECRET to keep them.");
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            return Keys.hmacShaKeyFor(random);
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException("JWT_SECRET is too short: it must be at least " + MIN_BYTES
                    + " bytes (256 bits).");
        }
        if (production && isPlaceholder(secret)) {
            throw new IllegalStateException("JWT_SECRET is a known or example value. Production requires a random "
                    + "secret of at least " + MIN_BYTES + " bytes.");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    static boolean isPlaceholder(String secret) {
        if (FORMER_DEFAULT.equals(secret)) {
            return true;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains);
    }
}
