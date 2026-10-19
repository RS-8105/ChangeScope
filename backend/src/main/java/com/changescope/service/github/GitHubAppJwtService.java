package com.changescope.service.github;

import com.changescope.exception.GitHubAuthenticationException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Service for loading GitHub App private keys and generating signed RS256 JWT tokens.
 * Complies with GitHub App authentication specifications:
 * - RS256 algorithm
 * - iat (issued at) set 60 seconds in the past for clock drift
 * - exp (expiration) set to 9 minutes in the future (within GitHub's 10-minute maximum)
 * - iss (issuer) set to GitHub App ID
 */
@Service
public class GitHubAppJwtService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Creates a signed RS256 JWT for GitHub App authentication.
     *
     * @param appId      the GitHub App ID
     * @param privateKey the RSA private key
     * @param now        the current instant
     * @return signed JWT string
     */
    public String createJwt(String appId, PrivateKey privateKey, Instant now) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("App ID must not be null or blank");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("Private key must not be null");
        }
        if (now == null) {
            now = Instant.now();
        }

        long iat = now.minusSeconds(60).getEpochSecond();
        long exp = now.plusSeconds(9 * 60).getEpochSecond();

        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}", iat, exp, appId);

        String encodedHeader = URL_ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;

        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = signature.sign();
            return signingInput + "." + URL_ENCODER.encodeToString(signatureBytes);
        } catch (Exception ex) {
            throw new GitHubAuthenticationException(500, "Failed to sign GitHub App JWT: " + ex.getMessage());
        }
    }

    /**
     * Parses an RSA private key from either inline PEM string or a file path.
     * Supports both PKCS#8 (BEGIN PRIVATE KEY) and PKCS#1 (BEGIN RSA PRIVATE KEY) formats.
     *
     * @param pemOrPath inline PEM text or filesystem path
     * @return RSA PrivateKey
     */
    public PrivateKey parsePrivateKey(String pemOrPath) {
        if (pemOrPath == null || pemOrPath.isBlank()) {
            throw new IllegalArgumentException("Private key configuration must not be null or blank");
        }

        String rawContent = pemOrPath.trim();
        // Check if it's a file path
        if (!rawContent.contains("-----BEGIN") && (rawContent.contains("/") || rawContent.contains("\\") || rawContent.endsWith(".pem"))) {
            try {
                Path path = Path.of(rawContent);
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    rawContent = Files.readString(path, StandardCharsets.UTF_8).trim();
                }
            } catch (IOException ex) {
                throw new GitHubAuthenticationException(500, "Failed to read private key file: " + ex.getMessage());
            }
        }

        boolean isPkcs1 = rawContent.contains("BEGIN RSA PRIVATE KEY");

        // Clean PEM delimiters and whitespace
        String cleanBase64 = rawContent
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] derBytes;
        try {
            derBytes = Base64.getDecoder().decode(cleanBase64);
        } catch (IllegalArgumentException ex) {
            throw new GitHubAuthenticationException(500, "Invalid Base64 encoding in private key PEM");
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            if (isPkcs1) {
                byte[] pkcs8Bytes = wrapPkcs1ToPkcs8(derBytes);
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
            } else {
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(derBytes));
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new GitHubAuthenticationException(500, "Failed to parse RSA private key: " + ex.getMessage());
        }
    }

    /**
     * Wraps PKCS#1 RSA private key bytes into a PKCS#8 ASN.1 PrivateKeyInfo structure.
     */
    private byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        // AlgorithmIdentifier for rsaEncryption: 1.2.840.113549.1.1.1
        byte[] rsaAlgorithmIdentifier = new byte[] {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // Version = 0 (INTEGER 0)
            byte[] version = new byte[] { 0x02, 0x01, 0x00 };

            // OCTET STRING containing the PKCS#1 bytes
            ByteArrayOutputStream octetStream = new ByteArrayOutputStream();
            octetStream.write(0x04); // OCTET STRING tag
            writeDerLength(octetStream, pkcs1Bytes.length);
            octetStream.write(pkcs1Bytes);
            byte[] octetString = octetStream.toByteArray();

            // Total sequence length
            int totalPayloadLen = version.length + rsaAlgorithmIdentifier.length + octetString.length;
            out.write(0x30); // SEQUENCE tag
            writeDerLength(out, totalPayloadLen);
            out.write(version);
            out.write(rsaAlgorithmIdentifier);
            out.write(octetString);
        } catch (IOException ex) {
            throw new GitHubAuthenticationException(500, "Failed to wrap PKCS#1 key into PKCS#8 structure: " + ex.getMessage());
        }
        return out.toByteArray();
    }

    private void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xff);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
    }
}
