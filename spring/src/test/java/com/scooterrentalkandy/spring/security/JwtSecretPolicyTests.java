package com.scooterrentalkandy.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** BE-035: the JWT signing secret fails closed in production and is never a built-in value. */
class JwtSecretPolicyTests {

    /** 48 random-looking characters, built at runtime so no secret-like literal sits in the source. */
    private static final String STRONG = UUID.randomUUID().toString() + UUID.randomUUID().toString().substring(0, 12);

    @Test
    void productionRefusesAMissingSecret() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(null, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is not set");
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("   ", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void refusesASecretShorterThan256BitsInEveryEnvironment() {
        String shortSecret = "x".repeat(JwtSecretPolicy.MIN_BYTES - 1);
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(shortSecret, true)).hasMessageContaining("too short");
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(shortSecret, false)).hasMessageContaining("too short");
    }

    @Test
    void productionRefusesTheFormerDefaultAndExampleValues() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(JwtSecretPolicy.FORMER_DEFAULT, true))
                .hasMessageContaining("known or example value");
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("test-only-jwt-secret-not-for-production-0123456789", true))
                .hasMessageContaining("known or example value");
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("please-CHANGE-ME-" + STRONG, true))
                .hasMessageContaining("known or example value");
    }

    @Test
    void errorMessagesNeverContainTheSecret() {
        String leaked = "change-me-" + STRONG;
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(leaked, true)).message().doesNotContain(STRONG);
    }

    @Test
    void productionAcceptsALongRandomSecret() {
        assertThat(JwtSecretPolicy.resolve(STRONG, true)).isNotNull();
    }

    @Test
    void developmentWithoutASecretUsesADifferentRandomKeyEachRun() {
        assertThat(JwtSecretPolicy.resolve(null, false).getEncoded())
                .isNotEqualTo(JwtSecretPolicy.resolve(null, false).getEncoded());
    }

    @Test
    void developmentStillAcceptsTheTestProfileSecret() {
        assertThat(JwtSecretPolicy.resolve("test-only-jwt-secret-not-for-production-0123456789", false)).isNotNull();
    }

    @Test
    void jwtServiceFailsClosedUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new JwtService(props(""), prod)).hasMessageContaining("JWT_SECRET is not set");
        assertThatThrownBy(() -> new JwtService(props(JwtSecretPolicy.FORMER_DEFAULT), prod))
                .hasMessageContaining("known or example value");
    }

    @Test
    void tokensSignedWithOneRandomDevelopmentKeyAreRejectedByAnother() {
        MockEnvironment dev = new MockEnvironment();
        JwtService first = new JwtService(props(""), dev);
        JwtService second = new JwtService(props(""), dev);
        String token = first.issue(user());
        assertThat(first.parse(token)).map(Claims::getSubject).isPresent();
        assertThat(second.parse(token)).isEqualTo(Optional.empty());
    }

    @Test
    void aConfiguredSecretSignsAndVerifiesTokensAcrossInstances() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        String token = new JwtService(props(STRONG), prod).issue(user());
        assertThat(new JwtService(props(STRONG), prod).parse(token)).isPresent();
    }

    private static AppProperties props(String secret) {
        return new AppProperties(new AppProperties.Jwt(secret, 60), null, null, null, null, null, null);
    }

    private static User user() {
        User u = new User();
        u.setUserId(UUID.randomUUID());
        u.setEmail("jwt.policy@example.test");
        u.setRole(Role.USER);
        return u;
    }
}
