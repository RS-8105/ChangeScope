package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link GitHubTokenProvider}.
 *
 * Authentication Precedence:
 * 1. GitHub App (appId + privateKey / privateKeyPath): Generates RS256 JWT, retrieves and caches installation tokens
 *    associated with the requested repository's installation ID.
 * 2. Personal Access Token (token): Static PAT fallback for local development when GitHub App is not configured.
 * 3. Anonymous / Unauthenticated: Returns Optional.empty() if neither is configured.
 */
@Service
public class DefaultGitHubTokenProvider implements GitHubTokenProvider {

    private final GitHubProperties properties;
    private final GitHubAppJwtService jwtService;
    private final RestClient restClient;
    private final RepositoryEntityRepository repositoryEntityRepository;

    private final Object refreshLock = new Object();
    private final Map<Long, CachedToken> installationTokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid(long bufferSeconds) {
            return Instant.now().isBefore(expiresAt.minusSeconds(bufferSeconds));
        }
    }

    public DefaultGitHubTokenProvider(
        GitHubProperties properties,
        GitHubAppJwtService jwtService,
        RestClient.Builder restClientBuilder
    ) {
        this(properties, jwtService, restClientBuilder, null);
    }

    @Autowired
    public DefaultGitHubTokenProvider(
        GitHubProperties properties,
        GitHubAppJwtService jwtService,
        RestClient.Builder restClientBuilder,
        @Autowired(required = false) RepositoryEntityRepository repositoryEntityRepository
    ) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.restClient = restClientBuilder
            .baseUrl(properties.getApiBaseUrl())
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .defaultHeader(HttpHeaders.USER_AGENT, "ChangeScope-App")
            .build();
    }

    @Override
    public Optional<String> getToken() {
        if (properties.hasAppConfig()) {
            Long defaultInstallationId = properties.getInstallationId();
            if (defaultInstallationId == null) {
                throw new GitHubAuthenticationException(
                    401,
                    "GitHub App is configured, but no repository context or default installation ID was provided"
                );
            }
            return Optional.of(getGitHubAppInstallationToken(defaultInstallationId));
        } else if (properties.getAppId() != null && !properties.getAppId().isBlank() && !properties.hasPrivateKey()) {
            throw new GitHubAuthenticationException(401, "GitHub App ID is configured but private key is missing or blank");
        } else if (properties.hasToken()) {
            return Optional.of(properties.getToken());
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getTokenForRepository(String owner, String repo) {
        if (properties.hasAppConfig()) {
            Long installationId = resolveInstallationIdForRepository(owner, repo);
            return Optional.of(getGitHubAppInstallationToken(installationId));
        } else if (properties.getAppId() != null && !properties.getAppId().isBlank() && !properties.hasPrivateKey()) {
            throw new GitHubAuthenticationException(401, "GitHub App ID is configured but private key is missing or blank");
        } else if (properties.hasToken()) {
            return Optional.of(properties.getToken());
        } else {
            return Optional.empty();
        }
    }

    private Long resolveInstallationIdForRepository(String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            if (properties.getInstallationId() != null) {
                return properties.getInstallationId();
            }
            throw new GitHubAuthenticationException(
                401,
                "GitHub App authentication requires a valid repository owner/name or configured installation ID"
            );
        }

        String trimmedOwner = owner.trim();
        String trimmedRepo = repo.trim();

        if (repositoryEntityRepository != null) {
            Optional<RepositoryEntity> repoEntity = repositoryEntityRepository.findByOwnerAndName(trimmedOwner, trimmedRepo);
            if (repoEntity.isPresent()) {
                Long installationId = repoEntity.get().getInstallationId();
                if (installationId != null) {
                    if (installationId <= 0) {
                        throw new GitHubAuthenticationException(
                            401,
                            "Invalid GitHub App installation ID configured for repository " + trimmedOwner + "/" + trimmedRepo
                        );
                    }
                    return installationId;
                }
                throw new GitHubAuthenticationException(
                    401,
                    "Repository '" + trimmedOwner + "/" + trimmedRepo + "' is missing required GitHub App installation ID"
                );
            }
            throw new GitHubAuthenticationException(
                401,
                "Repository '" + trimmedOwner + "/" + trimmedRepo + "' is not registered or missing GitHub App installation ID"
            );
        }

        if (properties.getInstallationId() != null) {
            return properties.getInstallationId();
        }

        throw new GitHubAuthenticationException(
            401,
            "Repository '" + trimmedOwner + "/" + trimmedRepo + "' has no associated GitHub App installation ID"
        );
    }

    private String getGitHubAppInstallationToken(Long installationId) {
        if (installationId == null || installationId <= 0) {
            throw new GitHubAuthenticationException(401, "Invalid GitHub App installation ID: " + installationId);
        }

        // 1. Fast path: check cached token
        CachedToken cached = installationTokenCache.get(installationId);
        if (cached != null && cached.isValid(properties.getTokenExpiryBufferSeconds())) {
            return cached.token();
        }

        // 2. Synchronized block to prevent concurrent refreshes for this installation
        synchronized (refreshLock) {
            cached = installationTokenCache.get(installationId);
            if (cached != null && cached.isValid(properties.getTokenExpiryBufferSeconds())) {
                return cached.token();
            }

            // Load and parse private key
            PrivateKey privateKey = loadPrivateKey();

            // Create short-lived App JWT
            String appJwt = jwtService.createJwt(properties.getAppId(), privateKey, Instant.now());

            // Fetch installation access token
            CachedToken newToken = fetchInstallationToken(appJwt, installationId);
            installationTokenCache.put(installationId, newToken);
            return newToken.token();
        }
    }

    private PrivateKey loadPrivateKey() {
        String keyContent = properties.getPrivateKey();
        if (keyContent == null || keyContent.isBlank()) {
            keyContent = properties.getPrivateKeyPath();
        }
        if (keyContent == null || keyContent.isBlank()) {
            throw new GitHubAuthenticationException(401, "GitHub App private key is not configured");
        }
        return jwtService.parsePrivateKey(keyContent);
    }

    private CachedToken fetchInstallationToken(String appJwt, Long installationId) {
        try {
            GitHubAccessTokenWire response = restClient.post()
                .uri("/app/installations/{installation_id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                .retrieve()
                .body(GitHubAccessTokenWire.class);

            if (response == null || response.token() == null || response.token().isBlank()) {
                throw new GitHubAuthenticationException(500, "Received empty installation token response for installation " + installationId);
            }

            Instant expiresAt = (response.expiresAt() != null)
                ? Instant.parse(response.expiresAt())
                : Instant.now().plusSeconds(3600);

            return new CachedToken(response.token(), expiresAt);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            throw new GitHubAuthenticationException(
                status,
                "Failed to retrieve GitHub App installation token (HTTP " + status + ") for installation " + installationId
            );
        } catch (Exception ex) {
            if (ex instanceof GitHubAuthenticationException gha) {
                throw gha;
            }
            throw new GitHubAuthenticationException(500, "Error requesting GitHub App installation token: " + ex.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubAccessTokenWire(
        String token,
        @JsonProperty("expires_at") String expiresAt
    ) {}
}
