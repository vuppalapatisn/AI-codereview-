package com.example.codereview.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class GitHubSignatureVerifierTest {

    private final GitHubSignatureVerifier verifier = new GitHubSignatureVerifier();

    @Test
    void acceptsCorrectlySignedPayload() throws Exception {
        String secret = "test-secret";
        String payload = "{\"action\":\"opened\"}";
        String signature = "sha256=" + hmacHex(payload, secret);

        assertTrue(verifier.isValid(payload, signature, secret));
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        String secret = "test-secret";
        String originalPayload = "{\"action\":\"opened\"}";
        String signature = "sha256=" + hmacHex(originalPayload, secret);

        String tamperedPayload = "{\"action\":\"closed\"}";
        assertFalse(verifier.isValid(tamperedPayload, signature, secret));
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        String payload = "{\"action\":\"opened\"}";
        String signature = "sha256=" + hmacHex(payload, "correct-secret");

        assertFalse(verifier.isValid(payload, signature, "wrong-secret"));
    }

    @Test
    void rejectsMissingPrefix() {
        assertFalse(verifier.isValid("payload", "deadbeef", "secret"));
    }

    @Test
    void rejectsBlankWebhookSecret() {
        assertFalse(verifier.isValid("payload", "sha256=deadbeef", ""));
    }

    @Test
    void rejectsNullSignatureHeader() {
        assertFalse(verifier.isValid("payload", null, "secret"));
    }

    private String hmacHex(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
