package com.example.codereview.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies the X-Hub-Signature-256 header GitHub sends with every webhook
 * delivery, so we never process a payload that didn't actually come from GitHub.
 * Uses a constant-time comparison to avoid timing side-channels.
 */
@Component
public class GitHubSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    public boolean isValid(String payloadBody, String signatureHeader, String webhookSecret) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // Fail closed: never accept unsigned traffic even if misconfigured.
            return false;
        }

        String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
        String expectedHex = computeHmacHex(payloadBody, webhookSecret);
        return constantTimeEquals(providedHex, expectedHex);
    }

    private String computeHmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to compute HMAC signature", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
