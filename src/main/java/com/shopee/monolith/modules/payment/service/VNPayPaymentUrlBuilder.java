package com.shopee.monolith.modules.payment.service;

import com.shopee.monolith.modules.payment.config.VNPayProperties;
import com.shopee.monolith.modules.payment.entity.PaymentAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the signed VNPay sandbox redirect URL for a pending payment attempt.
 * vnp_TxnRef is the payment attempt ID; vnp_Amount is the snapshot amount in
 * minor units (x100) so the webhook can verify it back against the attempt.
 */
@Component
@RequiredArgsConstructor
public class VNPayPaymentUrlBuilder {

    private static final DateTimeFormatter VNP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final VNPayProperties properties;
    private final VNPaySignatureVerifier signatureVerifier;

    public String buildPaymentUrl(PaymentAttempt attempt, String clientIp) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", properties.getTmnCode());
        params.put("vnp_Amount", attempt.getAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_CurrCode", attempt.getCurrency());
        params.put("vnp_IpAddr", clientIp);
        params.put("vnp_TxnRef", attempt.getId().toString());
        params.put("vnp_OrderInfo", "Checkout " + attempt.getCheckoutSessionId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", properties.getReturnUrl());
        params.put("vnp_CreateDate", VNP_DATE_FORMAT.format(attempt.getCreatedAt()));
        params.put("vnp_ExpireDate", VNP_DATE_FORMAT.format(attempt.getExpiresAt()));

        String secureHash = signatureVerifier.sign(params);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(entry.getKey())
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII));
        }
        query.append("&vnp_SecureHash=").append(secureHash);
        return properties.getPayUrl() + "?" + query;
    }
}
