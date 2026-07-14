package com.shopee.monolith.modules.notification.service;

public interface EmailService {
    void sendVerificationEmail(String to, String verificationLink);

    void sendPasswordResetEmail(String to, String resetLink);
}
