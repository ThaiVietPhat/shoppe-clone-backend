package com.shopee.monolith.modules.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.notification")
@Validated
@Data
public class NotificationProperties {

    @NotBlank
    @URL(message = "Verification URL must be a valid URL")
    private String verificationUrl;

    @NotBlank
    @URL(message = "Password reset URL must be a valid URL")
    private String passwordResetUrl;

    @NotBlank
    @Email(message = "Sender must be a valid email address")
    private String sender;

    @Valid
    @NotNull
    private RetryProperties retry = new RetryProperties();

    @Data
    public static class RetryProperties {
        @Min(1)
        private int batchSize = 50;

        @NotNull
        private Duration fixedDelay = Duration.ofMinutes(5);
    }

    @jakarta.validation.constraints.AssertTrue(message = "Retry fixed delay must be positive")
    public boolean isFixedDelayPositive() {
        return retry != null && retry.getFixedDelay() != null
                && !retry.getFixedDelay().isNegative() && !retry.getFixedDelay().isZero();
    }
}
