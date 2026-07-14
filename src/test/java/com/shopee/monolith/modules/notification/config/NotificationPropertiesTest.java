package com.shopee.monolith.modules.notification.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private NotificationProperties createValidProperties() {
        NotificationProperties properties = new NotificationProperties();
        properties.setVerificationUrl("http://localhost:3000/verify-email");
        properties.setPasswordResetUrl("http://localhost:3000/reset-password");
        properties.setSender("no-reply@shoppe.local");
        properties.getRetry().setBatchSize(50);
        properties.getRetry().setFixedDelay(Duration.ofMinutes(5));
        return properties;
    }

    @Test
    void whenPropertiesAreValidShouldPassValidation() {
        NotificationProperties properties = createValidProperties();
        Set<ConstraintViolation<NotificationProperties>> violations = validator.validate(properties);
        assertTrue(violations.isEmpty());
    }

    @Test
    void whenVerificationUrlIsInvalidShouldFailValidation() {
        NotificationProperties properties = createValidProperties();
        properties.setVerificationUrl("invalid-url");

        Set<ConstraintViolation<NotificationProperties>> violations = validator.validate(properties);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("verificationUrl")));
    }

    @Test
    void whenSenderIsInvalidEmailShouldFailValidation() {
        NotificationProperties properties = createValidProperties();
        properties.setSender("invalid-email");

        Set<ConstraintViolation<NotificationProperties>> violations = validator.validate(properties);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("sender")));
    }

    @Test
    void whenRetryBatchSizeIsZeroOrLessShouldFailValidation() {
        NotificationProperties properties = createValidProperties();
        properties.getRetry().setBatchSize(0);

        Set<ConstraintViolation<NotificationProperties>> violations = validator.validate(properties);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("retry.batchSize")));

        properties.getRetry().setBatchSize(-5);
        violations = validator.validate(properties);
        assertFalse(violations.isEmpty());
    }

    @Test
    void whenRetryFixedDelayIsNullShouldFailValidation() {
        NotificationProperties properties = createValidProperties();
        properties.getRetry().setFixedDelay(null);

        Set<ConstraintViolation<NotificationProperties>> violations = validator.validate(properties);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("retry.fixedDelay")));
    }

    @Test
    void whenRetryFixedDelayIsZeroOrNegativeShouldFailValidation() {
        NotificationProperties properties = createValidProperties();

        properties.getRetry().setFixedDelay(Duration.ZERO);
        Set<ConstraintViolation<NotificationProperties>> violations1 = validator.validate(properties);
        assertFalse(violations1.isEmpty());
        assertTrue(violations1.stream().anyMatch(v -> v.getMessage().contains("Retry fixed delay must be positive")));

        properties.getRetry().setFixedDelay(Duration.ofSeconds(-30));
        Set<ConstraintViolation<NotificationProperties>> violations2 = validator.validate(properties);
        assertFalse(violations2.isEmpty());
        assertTrue(violations2.stream().anyMatch(v -> v.getMessage().contains("Retry fixed delay must be positive")));
    }
}
