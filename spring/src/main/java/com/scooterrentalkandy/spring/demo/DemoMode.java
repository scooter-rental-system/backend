package com.scooterrentalkandy.spring.demo;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Viva demo mode: local admin actions that move a booking through its lifecycle without a payment provider. It exists
 * only when the Spring profile {@code demo} is explicitly active, and never together with a production profile.
 *
 * <p>Two independent protections: every demo bean is limited to {@link #PROFILES} (so under {@code prod} there is
 * nothing to call even if {@code demo} is also listed), and this guard, loaded whenever {@code demo} is active, stops
 * startup when demo mode meets production: a {@code prod}/{@code production} profile or a live Stripe key.
 */
@Configuration
@Profile("demo")
public class DemoMode {

    /** The profile expression every demo bean carries. */
    public static final String PROFILES = "demo & !prod & !production";

    private static final Logger log = LoggerFactory.getLogger(DemoMode.class);

    public DemoMode(Environment environment) {
        check(environment);
        log.warn("VIVA DEMO MODE is active (Spring profile 'demo'): admins can start and end rentals without a "
                + "payment. No real payment is processed. Never use this profile in production.");
    }

    /** Refuses demo mode in a production setting; the message never includes the key itself. */
    static void check(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("The 'demo' profile cannot run together with a production profile "
                    + "(prod/production). Viva demo mode is for local development only.");
        }
        String stripeKey = environment.getProperty("app.payments.stripe.secret-key", "").trim().toLowerCase(Locale.ROOT);
        if (stripeKey.startsWith("sk_live_") || stripeKey.startsWith("rk_live_")) {
            throw new IllegalStateException("The 'demo' profile cannot run with a live Stripe key "
                    + "(STRIPE_SECRET_KEY). Viva demo mode is for local development only.");
        }
    }
}
