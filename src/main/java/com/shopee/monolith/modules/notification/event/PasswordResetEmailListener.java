package com.shopee.monolith.modules.notification.event;

import com.shopee.monolith.common.security.EventPayloadCryptoService;
import com.shopee.monolith.modules.notification.config.NotificationProperties;
import com.shopee.monolith.modules.notification.service.EmailService;
import com.shopee.monolith.modules.user.event.PasswordResetRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetEmailListener {

    private final EmailService emailService;
    private final EventPayloadCryptoService cryptoService;
    private final NotificationProperties properties;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PasswordResetRequestedEvent event) {
        log.info("Received PasswordResetRequestedEvent for userId: {}", event.userId());
        try {
            String rawToken = cryptoService.decrypt(event.encryptedResetToken());

            String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
            String resetLink = UriComponentsBuilder.fromUriString(properties.getPasswordResetUrl())
                    .queryParam("token", encodedToken)
                    .build(true)
                    .toUriString();

            emailService.sendPasswordResetEmail(event.email(), resetLink);
            log.info("Successfully processed password reset email for userId: {}", event.userId());
        } catch (Exception e) {
            log.error("Failed to process password reset email for userId: {}", event.userId());
            throw new RuntimeException("Password reset email delivery failed for user " + event.userId(), e);
        }
    }
}
