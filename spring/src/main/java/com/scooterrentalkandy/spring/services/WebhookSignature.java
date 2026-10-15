package com.scooterrentalkandy.spring.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stripe-format webhook signatures: header {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.payload">}. */
final class WebhookSignature {

    private static final long TOLERANCE_SECONDS = 300;

    private WebhookSignature() {
    }

    static String sign(String payload, String secret, Clock clock) {
        long t = clock.instant().getEpochSecond();
        return "t=" + t + ",v1=" + hmac(t + "." + payload, secret);
    }

    static void verify(String payload, String header, String secret, Clock clock) {
        if (header == null) {
            throw new IllegalArgumentException("Missing signature");
        }
        String t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                t = kv[1];
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            throw new IllegalArgumentException("Malformed signature");
        }
        long age;
        try {
            age = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(t));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed signature");
        }
        if (age > TOLERANCE_SECONDS) {
            throw new IllegalArgumentException("Signature timestamp outside tolerance");
        }
        byte[] expected = hmac(t + "." + payload, secret).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Signature mismatch");
        }
    }

    private static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
