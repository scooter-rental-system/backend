package com.scooterrentalkandy.spring.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.scooterrentalkandy.spring.services.EmailDeliveryException;
import com.scooterrentalkandy.spring.services.EmailSender;

/**
 * TEST-ONLY email provider double. It lives under src/test, so it is never packaged with the application. It
 * records what it is asked to send, or refuses it when told to; no network or real provider is involved. Import
 * it only in tests about email delivery: the default test context has no EmailSender, as production without a
 * provider. Call {@link #reset()} before each test: the bean is shared by the tests of one application context.
 */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String to, String subject, String body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;
    private volatile Runnable onSend = () -> { };

    @Override
    public void send(String to, String subject, String body) {
        onSend.run();
        if (failing) {
            throw new EmailDeliveryException("Test provider refused the message", null);
        }
        sent.add(new Sent(to, subject, body));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<Sent> sentTo(String to) {
        return sent.stream().filter(s -> s.to().equals(to)).toList();
    }

    /** Every following send is refused, as a provider that rejects the message or cannot be reached. */
    public void failing() {
        this.failing = true;
    }

    /** Runs at the start of each send, e.g. to look at the database from outside the caller's transaction. */
    public void onSend(Runnable hook) {
        this.onSend = hook;
    }

    public void reset() {
        sent.clear();
        failing = false;
        onSend = () -> { };
    }
}
