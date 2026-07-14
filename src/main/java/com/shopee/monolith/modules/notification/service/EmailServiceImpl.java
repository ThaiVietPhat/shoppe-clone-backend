package com.shopee.monolith.modules.notification.service;

import com.shopee.monolith.modules.notification.config.NotificationProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    @Override
    public void sendVerificationEmail(String to, String verificationLink) {
        log.info("Sending verification email to recipient");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(properties.getSender());
            helper.setTo(to);
            helper.setSubject("Please verify your email address");

            String escapedLink = HtmlUtils.htmlEscape(verificationLink);
            String htmlContent = "<p>Thank you for registering. Please verify your email by clicking the link below:</p>"
                    + "<a href=\"" + escapedLink + "\">Verify Email</a>";
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Verification email sent successfully");
        } catch (Exception e) {
            log.error("Failed to send verification email");
            throw new IllegalStateException("Email delivery failed", e);
        }
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        log.info("Sending password reset email to recipient");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(properties.getSender());
            helper.setTo(to);
            helper.setSubject("Reset your password");

            String escapedLink = HtmlUtils.htmlEscape(resetLink);
            String htmlContent = "<p>We received a request to reset your password. Click the link below to choose a new password:</p>"
                    + "<a href=\"" + escapedLink + "\">Reset Password</a>"
                    + "<p>If you did not request this, you can safely ignore this email.</p>";
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password reset email sent successfully");
        } catch (Exception e) {
            log.error("Failed to send password reset email");
            throw new IllegalStateException("Email delivery failed", e);
        }
    }
}
