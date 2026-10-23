package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.exception.GitHubAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubWebhookSignatureVerifierTest {

    private static final String TEST_SECRET = "super-secret-webhook-key-12345";
    private static final byte[] SAMPLE_PAYLOAD = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);

    private GitHubProperties properties;
    private GitHubWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        properties = new GitHubProperties();
        properties.setWebhookSecret(TEST_SECRET);
        verifier = new GitHubWebhookSignatureVerifier(properties);
    }

    @Test
    void verify_validSignature_returnsTrue() {
        String signature = verifier.calculateSignature(SAMPLE_PAYLOAD);

        assertThat(signature).startsWith("sha256=");
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, signature)).isTrue();
        assertThat(verifier.verify(SAMPLE_PAYLOAD, signature)).isTrue();
        assertThat(verifier.isValid(SAMPLE_PAYLOAD, signature)).isTrue();
        assertThatCode(() -> verifier.assertValid(SAMPLE_PAYLOAD, signature)).doesNotThrowAnyException();
    }

    @Test
    void verify_invalidSignature_returnsFalse() {
        // Valid format, but wrong HMAC hash
        String wrongSecretSignature = GitHubWebhookSignatureVerifier.calculateSignature(SAMPLE_PAYLOAD, "wrong-secret-key");

        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, wrongSecretSignature)).isFalse();
        assertThatThrownBy(() -> verifier.assertValid(SAMPLE_PAYLOAD, wrongSecretSignature))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("Invalid GitHub webhook signature");
    }

    @Test
    void verify_modifiedPayload_returnsFalse() {
        String signatureForOriginal = verifier.calculateSignature(SAMPLE_PAYLOAD);

        byte[] tamperedPayload = "{\"action\":\"opened\",\"number\":43}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verifySignature(tamperedPayload, signatureForOriginal)).isFalse();
        assertThatThrownBy(() -> verifier.assertValid(tamperedPayload, signatureForOriginal))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("Invalid GitHub webhook signature");
    }

    @Test
    void verify_emptyPayload_verifiesCorrectly() {
        byte[] emptyPayload = new byte[0];
        String signature = verifier.calculateSignature(emptyPayload);

        assertThat(verifier.verifySignature(emptyPayload, signature)).isTrue();
        // Null payload is treated as empty bytes
        assertThat(verifier.verifySignature(null, signature)).isTrue();
    }

    @Test
    void verify_missingOrBlankSignatureHeader_returnsFalse() {
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, null)).isFalse();
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "")).isFalse();
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "   ")).isFalse();
    }

    @Test
    void verify_malformedSignatureHeader_returnsFalse() {
        String validSig = verifier.calculateSignature(SAMPLE_PAYLOAD);
        String hexOnly = validSig.substring("sha256=".length());

        // Missing sha256= prefix
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, hexOnly)).isFalse();

        // Wrong prefix
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "sha1=" + hexOnly)).isFalse();
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "md5=" + hexOnly)).isFalse();

        // Prefix only
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "sha256=")).isFalse();

        // Digest too short (63 chars instead of 64)
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "sha256=" + hexOnly.substring(0, 63))).isFalse();

        // Digest too long (65 chars)
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "sha256=" + hexOnly + "a")).isFalse();

        // Invalid hex characters (z is not a valid hex character)
        String invalidHex = hexOnly.substring(0, 63) + "z";
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, "sha256=" + invalidHex)).isFalse();
    }

    @Test
    void verify_caseInsensitivePrefixAndHex_succeeds() {
        String signature = verifier.calculateSignature(SAMPLE_PAYLOAD);
        String hex = signature.substring("sha256=".length());

        // Upper case prefix: SHA256=
        String upperPrefix = "SHA256=" + hex;
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, upperPrefix)).isTrue();

        // Upper case hex digest
        String upperHex = "sha256=" + hex.toUpperCase();
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, upperHex)).isTrue();

        // Mixed case prefix and hex with surrounding whitespace
        String mixed = "  ShA256=" + hex.toUpperCase() + "  ";
        assertThat(verifier.verifySignature(SAMPLE_PAYLOAD, mixed)).isTrue();
    }

    @Test
    void verify_missingSecretConfiguration_failsClosed() {
        // 1. Unset secret in properties
        GitHubProperties emptyProps = new GitHubProperties();
        GitHubWebhookSignatureVerifier unconfiguredVerifier = new GitHubWebhookSignatureVerifier(emptyProps);

        assertThat(unconfiguredVerifier.isConfigured()).isFalse();
        String signature = GitHubWebhookSignatureVerifier.calculateSignature(SAMPLE_PAYLOAD, TEST_SECRET);

        // Verification must fail closed (return false, never true)
        assertThat(unconfiguredVerifier.verifySignature(SAMPLE_PAYLOAD, signature)).isFalse();
        assertThatThrownBy(() -> unconfiguredVerifier.assertValid(SAMPLE_PAYLOAD, signature))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("GitHub webhook secret is not configured");

        assertThatThrownBy(() -> unconfiguredVerifier.calculateSignature(SAMPLE_PAYLOAD))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Webhook secret is not configured");

        // 2. Blank secret in constructor
        GitHubWebhookSignatureVerifier blankVerifier = new GitHubWebhookSignatureVerifier("   ");
        assertThat(blankVerifier.isConfigured()).isFalse();
        assertThat(blankVerifier.verifySignature(SAMPLE_PAYLOAD, signature)).isFalse();

        // 3. Null secret in constructor
        GitHubWebhookSignatureVerifier nullVerifier = new GitHubWebhookSignatureVerifier((String) null);
        assertThat(nullVerifier.isConfigured()).isFalse();
        assertThat(nullVerifier.verifySignature(SAMPLE_PAYLOAD, signature)).isFalse();
    }

    @Test
    void properties_toString_neverLogsSecret() {
        String str = properties.toString();
        assertThat(str).doesNotContain(TEST_SECRET);
        assertThat(str).contains("hasWebhookSecret=true");
    }
}
