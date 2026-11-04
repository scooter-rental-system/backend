package com.scooterrentalkandy.spring.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.PasswordResetToken;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.exception.RateLimitedException;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.PasswordResetTokenRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.SlidingWindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Forgot password (BE-041). A request emails a one-time link with a random 256-bit token; only its SHA-256 hash is
 * stored. The link expires after {@link #TOKEN_LIFETIME}, works once, and a newer request or a completed reset ends
 * every earlier open link. The public answer to a request is the same whether or not the email has an account.
 *
 * <p>The reset email goes straight to the configured EmailSender (Amazon SES) after the request commits. Unlike other
 * notifications its body is never stored, because it carries the link. With no email provider or no frontend URL
 * configured, nothing can be delivered: the request is answered the same way and a warning names the missing setting.
 * The token is never logged.
 *
 * <p>Already-issued JWTs are not revoked by a reset (there is no server-side session store); they expire on their own.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    public static final String REQUEST_ACCEPTED =
            "If an account exists for that email, password reset instructions have been sent.";
    public static final String RESET_DONE = "Your password has been reset successfully.";
    static final String INVALID = "This password reset link is not valid. Please request a new one.";
    static final String EXPIRED = "This password reset link has expired. Please request a new one.";
    static final String ALREADY_USED =
            "This password reset link has already been used or replaced by a newer one. Please request a new one.";
    static final String EMAIL_SUBJECT = "Scooter Rent Kandy password reset";
    static final String TOO_MANY_REQUESTS =
            "Too many password reset requests from this device. Please try again in 15 minutes.";
    static final String RESET_CONFIRMATION_SUBJECT = "Your password was reset";

    /** Per client address: requests per window, then 429. */
    static final int MAX_REQUESTS_PER_ADDRESS = 5;
    /** Per email address: reset emails per window; more requests are answered the same way but send nothing. */
    static final int MAX_EMAILS_PER_ACCOUNT = 3;
    static final Duration RATE_WINDOW = Duration.ofMinutes(15);

    private static final int TOKEN_BYTES = 32;

    /** Published inside the request transaction; the email is sent only after it commits. */
    public record ResetEmailRequested(UUID userId, String to, String link) {
    }

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;
    private final NotificationRepository notificationLog;
    private final ObjectProvider<EmailSender> emailSender;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate newTransaction;
    private final Clock clock;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();
    private final SlidingWindowCounter perAddress;
    private final SlidingWindowCounter perAccount;

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, NotificationService notifications,
                                NotificationRepository notificationLog, ObjectProvider<EmailSender> emailSender,
                                ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                Clock clock, @Value("${app.frontend-url:}") String frontendUrl) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.notificationLog = notificationLog;
        this.emailSender = emailSender;
        this.events = events;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.frontendUrl = normalizeBaseUrl(frontendUrl);
        this.perAddress = new SlidingWindowCounter(MAX_REQUESTS_PER_ADDRESS, RATE_WINDOW, clock);
        this.perAccount = new SlidingWindowCounter(MAX_EMAILS_PER_ACCOUNT, RATE_WINDOW, clock);
        if (this.frontendUrl == null) {
            log.warn("APP_FRONTEND_URL is not set: password reset emails cannot include a link and are not sent");
        }
    }

    /**
     * Starts a reset for the email if it belongs to an active account. Always answers {@link #REQUEST_ACCEPTED}; only
     * the per-address limit is visible (429), and it does not depend on the email.
     */
    @Transactional
    public String requestReset(String rawEmail, String clientAddress) {
        if (!perAddress.tryAcquire(clientAddress)) {
            throw new RateLimitedException(TOO_MANY_REQUESTS, RATE_WINDOW);
        }
        String email = AuthService.normalizeEmail(rawEmail);
        Optional<User> found = users.findByEmailIgnoreCase(email).filter(u -> Boolean.TRUE.equals(u.getIsActive()));
        if (found.isEmpty() || !perAccount.tryAcquire(email)) {
            return REQUEST_ACCEPTED;
        }
        User user = found.get();
        if (frontendUrl == null || emailSender.getIfAvailable() == null) {
            log.warn("Password reset requested for user {} but {} is not configured; no email sent", user.getUserId(),
                    frontendUrl == null ? "APP_FRONTEND_URL" : "email delivery (NOTIFICATIONS_EMAIL_PROVIDER)");
            return REQUEST_ACCEPTED;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        tokens.invalidateOpen(user.getUserId(), now);
        String token = newToken();
        PasswordResetToken t = new PasswordResetToken();
        t.setUser(user);
        t.setTokenHash(hash(token));
        t.setCreatedAt(now);
        t.setExpiresAt(now.plus(TOKEN_LIFETIME));
        tokens.save(t);
        events.publishEvent(new ResetEmailRequested(user.getUserId(), user.getEmail(),
                frontendUrl + "/reset-password?token=" + token));
        return REQUEST_ACCEPTED;
    }

    /** Sets a new password from a valid, unexpired, unused link, then ends every open link of the account. */
    @Transactional
    public String resetPassword(String token, String newPassword) {
        PasswordResetToken t = tokens.findByTokenHash(hash(token)).orElseThrow(() -> ApiException.badRequest(INVALID));
        LocalDateTime now = LocalDateTime.now(clock);
        if (t.getUsedAt() != null) {
            throw ApiException.badRequest(ALREADY_USED);
        }
        if (!now.isBefore(t.getExpiresAt())) {
            throw ApiException.badRequest(EXPIRED);
        }
        User user = t.getUser();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw ApiException.badRequest(INVALID);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
        tokens.invalidateOpen(user.getUserId(), now);
        notifications.notify(user, RESET_CONFIRMATION_SUBJECT, "Your Scooter Rent Kandy password was reset with a "
                + "reset link. If this was not you, contact us straight away.");
        return RESET_DONE;
    }

    /** After the request commits: sends the link by email and records the delivery without the body. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendResetEmail(ResetEmailRequested request) {
        EmailSender sender = emailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        Notification.Status status;
        try {
            sender.send(request.to(), EMAIL_SUBJECT, emailBody(request.link()));
            status = Notification.Status.SENT;
        } catch (RuntimeException ex) {
            // Never the address, the link or the body.
            log.warn("Password reset email to user {} was not delivered: {}", request.userId(),
                    ex instanceof EmailDeliveryException ? ex.getMessage() : ex.getClass().getSimpleName());
            status = Notification.Status.FAILED;
        }
        recordDelivery(request.userId(), status);
    }

    static String emailBody(String link) {
        return "Hello,\n\n"
                + "A password reset was requested for your Scooter Rent Kandy account. To choose a new password, open "
                + "this link within " + TOKEN_LIFETIME.toMinutes() + " minutes:\n\n" + link + "\n\n"
                + "The link works once. If you did not request a reset, you can ignore this email: your password "
                + "stays the same.\n\nScooter Rent Kandy";
    }

    private void recordDelivery(UUID userId, Notification.Status status) {
        try {
            newTransaction.executeWithoutResult(tx -> {
                Notification n = new Notification();
                n.setUser(users.getReferenceById(userId));
                n.setChannel(Notification.Channel.EMAIL);
                n.setSubject(EMAIL_SUBJECT);
                n.setMessage("Password reset link emailed (the link is not stored).");
                n.setStatus(status);
                n.setCreatedAt(LocalDateTime.now(clock));
                notificationLog.save(n);
            });
        } catch (RuntimeException ex) {
            log.error("Could not record the {} password reset email for user {}", status, userId, ex);
        }
    }

    /** 32 random bytes, URL-safe Base64 without padding (43 characters). */
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 of the token: what is stored and looked up. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** An http(s) base URL without a trailing slash, or null when unset or not http(s). */
    static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            log.warn("APP_FRONTEND_URL must start with http:// or https://; password reset emails are not sent");
            return null;
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Forgets the request counters (used between automated tests). */
    public void clearRateLimits() {
        perAddress.clear();
        perAccount.clear();
    }
}
