package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.exception.GitHubAuthenticationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Service for verifying incoming GitHub webhook request signatures.
 *
 * GitHub signs webhook payloads using HMAC-SHA256 with the configured webhook secret
 * and includes the result in the `X-Hub-Signature-256` HTTP header formatted as `sha256=<hex_digest>`.
 *
 * Security Considerations:
 * - Uses constant-time comparison (MessageDigest.isEqual) to prevent timing side-channel attacks.
 * - Operates directly on the exact raw request-body bytes to avoid encoding mismatches.
 * - Fails closed if the webhook secret is missing, blank, or unconfigured.
 * - Never logs the webhook secret, received signature, or raw payload.
 */
@Service
public class GitHubWebhookSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    public static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final int EXPECTED_HEX_LENGTH = 64; // SHA-256 is 32 bytes = 64 hex characters

    private final GitHubProperties properties;

    @Autowired
    public GitHubWebhookSignatureVerifier(GitHubProperties properties) {
        this.properties = properties != null ? properties : new GitHubProperties();
    }

    public GitHubWebhookSignatureVerifier(String webhookSecret) {
        GitHubProperties props = new GitHubProperties();
        props.setWebhookSecret(webhookSecret);
        this.properties = props;
    }

    /**
     * Checks if a webhook secret is configured.
     *
     * @return true if a non-blank webhook secret is configured, false otherwise.
     */
    public boolean isConfigured() {
        return properties != null && properties.hasWebhookSecret();
    }

    /**
     * Verifies the HMAC-SHA256 signature of the raw request payload against the X-Hub-Signature-256 header.
     *
     * Fails closed:
     * - Returns false if the verifier is unconfigured (secret missing or blank).
     * - Returns false if the signature header is null, blank, or malformed.
     * - Returns false if the signature does not match the payload HMAC.
     *
     * @param payloadBytes    the raw body bytes of the incoming webhook request
     * @param signatureHeader the value of the X-Hub-Signature-256 header
     * @return true if the signature is valid and authentic, false otherwise
     */
    public boolean verifySignature(byte[] payloadBytes, String signatureHeader) {
        if (!isConfigured()) {
            // Fail closed: an unconfigured secret must never treat incoming requests as valid
            return false;
        }

        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String trimmedHeader = signatureHeader.trim();
        if (!trimmedHeader.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            return false;
        }

        String hexSignature = trimmedHeader.substring(SIGNATURE_PREFIX.length()).trim();
        if (hexSignature.length() != EXPECTED_HEX_LENGTH) {
            return false;
        }

        byte[] receivedHashBytes;
        try {
            receivedHashBytes = HexFormat.of().parseHex(hexSignature);
        } catch (IllegalArgumentException ex) {
            // Malformed hex characters
            return false;
        }

        byte[] expectedHashBytes = computeHmacSha256(payloadBytes, properties.getWebhookSecret());

        return MessageDigest.isEqual(expectedHashBytes, receivedHashBytes);
    }

    /**
     * Alias for {@link #verifySignature(byte[], String)}.
     */
    public boolean verify(byte[] payloadBytes, String signatureHeader) {
        return verifySignature(payloadBytes, signatureHeader);
    }

    /**
     * Alias for {@link #verifySignature(byte[], String)}.
     */
    public boolean isValid(byte[] payloadBytes, String signatureHeader) {
        return verifySignature(payloadBytes, signatureHeader);
    }

    /**
     * Asserts that the webhook signature is valid, throwing {@link GitHubAuthenticationException}
     * if the signature is missing, invalid, or if the verifier is unconfigured.
     *
     * @param payloadBytes    the raw body bytes of the incoming webhook request
     * @param signatureHeader the value of the X-Hub-Signature-256 header
     * @throws GitHubAuthenticationException if verification fails
     */
    public void assertValid(byte[] payloadBytes, String signatureHeader) {
        if (!isConfigured()) {
            throw new GitHubAuthenticationException(401, "GitHub webhook secret is not configured");
        }
        if (!verifySignature(payloadBytes, signatureHeader)) {
            throw new GitHubAuthenticationException(401, "Invalid GitHub webhook signature");
        }
    }

    /**
     * Calculates the expected X-Hub-Signature-256 header value for the given payload using the configured secret.
     *
     * @param payloadBytes the raw body bytes
     * @return signature string formatted as "sha256=<hex_digest>"
     */
    public String calculateSignature(byte[] payloadBytes) {
        if (!isConfigured()) {
            throw new IllegalStateException("Webhook secret is not configured");
        }
        return calculateSignature(payloadBytes, properties.getWebhookSecret());
    }

    /**
     * Calculates the expected X-Hub-Signature-256 header value for the given payload and secret.
     *
     * @param payloadBytes the raw body bytes
     * @param secret       the webhook secret
     * @return signature string formatted as "sha256=<hex_digest>"
     */
    public static String calculateSignature(byte[] payloadBytes, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Webhook secret must not be null or blank");
        }
        byte[] hash = computeHmacSha256(payloadBytes, secret);
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(hash);
    }

    private static byte[] computeHmacSha256(byte[] payloadBytes, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256_ALGORITHM
            );
            mac.init(secretKey);
            return mac.doFinal(payloadBytes != null ? payloadBytes : new byte[0]);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Failed to initialize HMAC-SHA256: " + ex.getMessage(), ex);
        }
    }
}
