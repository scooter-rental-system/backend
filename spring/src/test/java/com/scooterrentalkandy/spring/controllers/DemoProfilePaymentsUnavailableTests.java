package com.scooterrentalkandy.spring.controllers;

import org.springframework.test.context.ActiveProfiles;

/**
 * Every {@link PaymentsUnavailableTests} check again with the viva {@code demo} profile added: demo mode changes
 * nothing about payments. There is still no gateway, payment operations answer 503 and nothing is recorded.
 */
@ActiveProfiles("demo")
class DemoProfilePaymentsUnavailableTests extends PaymentsUnavailableTests {
}
