package com.scooterrentalkandy.spring.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;

/** Viva demo mode never runs in production: the startup guard and the profile expression of the demo beans. */
class DemoModeGuardTests {

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void demoAloneIsAllowed() {
        assertThatCode(() -> DemoMode.check(profiles("demo"))).doesNotThrowAnyException();
        MockEnvironment withTestKey = profiles("demo");
        withTestKey.setProperty("app.payments.stripe.secret-key", "sk_test_placeholder");
        assertThatCode(() -> DemoMode.check(withTestKey)).doesNotThrowAnyException();
    }

    @Test
    void demoWithAProductionProfileIsRefused() {
        for (String production : new String[] {"prod", "production"}) {
            assertThatThrownBy(() -> DemoMode.check(profiles("demo", production)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot run together with a production profile");
        }
    }

    @Test
    void demoWithALiveStripeKeyIsRefusedWithoutShowingTheKey() {
        for (String key : new String[] {"sk_live_placeholder", "rk_live_placeholder"}) {
            MockEnvironment env = profiles("demo");
            env.setProperty("app.payments.stripe.secret-key", key);
            assertThatThrownBy(() -> DemoMode.check(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("live Stripe key")
                    .hasMessageNotContaining(key);
        }
    }

    @Test
    void theDemoBeansAreNeverActiveUnderAProductionProfile() {
        assertThat(profiles("demo").acceptsProfiles(Profiles.of(DemoMode.PROFILES))).isTrue();
        assertThat(profiles("demo", "prod").acceptsProfiles(Profiles.of(DemoMode.PROFILES))).isFalse();
        assertThat(profiles("demo", "production").acceptsProfiles(Profiles.of(DemoMode.PROFILES))).isFalse();
        assertThat(profiles("prod").acceptsProfiles(Profiles.of(DemoMode.PROFILES))).isFalse();
        assertThat(profiles().acceptsProfiles(Profiles.of(DemoMode.PROFILES))).isFalse();
    }

    @Test
    void startupFailsWhenDemoAndProdAreBothActive() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoMode.class)
                .withPropertyValues("spring.profiles.active=demo,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("cannot run together with a production profile");
                });
    }

    @Test
    void withoutTheDemoProfileTheGuardIsNotEvenLoaded() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoMode.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DemoMode.class);
                });
    }
}
