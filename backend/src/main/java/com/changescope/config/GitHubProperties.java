package com.changescope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Typed configuration properties for GitHub API integration.
 * Supports configurable API base URL (including GitHub Enterprise or mock servers),
 * GitHub App authentication (App ID, private key PEM string or file path, installation ID),
 * and fallback personal access token (PAT) for local development.
 *
 * Authentication Precedence:
 * 1. GitHub App (appId + privateKey / privateKeyPath) - Generates short-lived JWT, exchanges for cached installation token.
 * 2. Personal Access Token (token) - Static token used if GitHub App is not configured.
 * 3. Anonymous / Unauthenticated - No bearer authorization sent if neither is configured.
 */
@Configuration
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /**
     * Base URL for the GitHub REST API. Defaults to https://api.github.com.
     */
    private String apiBaseUrl = "https://api.github.com";

    /**
     * Optional personal access token (PAT) for local development.
     */
    private String token;

    /**
     * GitHub App ID.
     */
    private String appId;

    /**
     * GitHub App private key in PEM format (inline string).
     */
    private String privateKey;

    /**
     * Path to GitHub App private key PEM file on disk.
     */
    private String privateKeyPath;

    /**
     * Optional default GitHub App installation ID.
     */
    private Long installationId;

    /**
     * Buffer in seconds before actual expiry to trigger token refresh. Defaults to 60 seconds.
     */
    private long tokenExpiryBufferSeconds = 60;

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            this.apiBaseUrl = apiBaseUrl.stripTrailing().replaceAll("/+$", "");
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = (token != null && !token.isBlank()) ? token.trim() : null;
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = (appId != null && !appId.isBlank()) ? appId.trim() : null;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = (privateKey != null && !privateKey.isBlank()) ? privateKey.trim() : null;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = (privateKeyPath != null && !privateKeyPath.isBlank()) ? privateKeyPath.trim() : null;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        this.installationId = installationId;
    }

    public long getTokenExpiryBufferSeconds() {
        return tokenExpiryBufferSeconds;
    }

    public void setTokenExpiryBufferSeconds(long tokenExpiryBufferSeconds) {
        this.tokenExpiryBufferSeconds = tokenExpiryBufferSeconds;
    }

    public boolean hasPrivateKey() {
        return (privateKey != null && !privateKey.isBlank()) || (privateKeyPath != null && !privateKeyPath.isBlank());
    }

    public boolean hasAppConfig() {
        return appId != null && !appId.isBlank() && hasPrivateKey();
    }

    @Override
    public String toString() {
        // Safe string representation ensuring private keys, JWTs, and tokens are NEVER logged
        return "GitHubProperties{"
            + "apiBaseUrl='" + apiBaseUrl + '\''
            + ", hasAppConfig=" + hasAppConfig()
            + ", appId='" + (appId != null ? appId : "null") + '\''
            + ", hasPrivateKey=" + hasPrivateKey()
            + ", installationId=" + installationId
            + ", hasToken=" + hasToken()
            + ", tokenExpiryBufferSeconds=" + tokenExpiryBufferSeconds
            + '}';
    }
}
