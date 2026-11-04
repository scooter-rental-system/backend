package com.scooterrentalkandy.spring.services;


import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Human-friendly unique identifiers, e.g. BK-20261002-7KQ3XZ. */
public final class References {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private References() {
    }

    public static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append('-')
                .append(LocalDate.now().format(DATE)).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
