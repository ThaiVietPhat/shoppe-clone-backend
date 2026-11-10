package com.shopee.monolith.modules.payment.service;

import com.shopee.monolith.modules.payment.config.VNPayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * VNPay HMAC-SHA512 signing over sorted, URL-encoded query parameters.
 * Verification compares hashes in constant time and never trusts the payload
 * before the signature check passes.
 */
@Component
@RequiredArgsConstructor
public class VNPaySignatureVerifier {

    private static final String HMAC_SHA512 = "HmacSHA512";
    private static final String SECURE_HASH_PARAM = "vnp_SecureHash";
    private static final String SECURE_HASH_TYPE_PARAM = "vnp_SecureHashType";

    private final VNPayProperties properties;

    public String sign(Map<String, String> params) {
        return hmacSha512(properties.getHashSecret(), buildHashData(params));
    }

    public boolean verify(Map<String, String> params) {
        String providedHash = params.get(SECURE_HASH_PARAM);
        if (providedHash == null || providedHash.isBlank()) {
            return false;
        }
        String expected = sign(params);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                providedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public String buildHashData(Map<String, String> params) {
        SortedMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove(SECURE_HASH_PARAM);
        sorted.remove(SECURE_HASH_TYPE_PARAM);

        StringBuilder hashData = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            if (hashData.length() > 0) {
                hashData.append('&');
            }
            hashData.append(entry.getKey())
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return hashData.toString();
    }

    private String hmacSha512(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA512));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute VNPay HMAC-SHA512 signature", e);
        }
    }
}
