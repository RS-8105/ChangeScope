package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.exception.GitHubAuthenticationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubAuthenticationTest {

    private GitHubAppJwtService jwtService;
    private KeyPair keyPair;
    private String privateKeyPkcs8Pem;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new GitHubAppJwtService();
        objectMapper = new ObjectMapper();

        // Generate dynamic 2048-bit RSA key pair for tests
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        keyPair = keyGen.generateKeyPair();

        // Encode to PKCS#8 PEM
        String base64Pkcs8 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        privateKeyPkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" + base64Pkcs8 + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void createJwt_validClaimsAndSignature_verifiedWithPublicKey() throws Exception {
        Instant now = Instant.parse("2026-10-17T14:30:00Z");
        String appId = "123456";

        String jwt = jwtService.createJwt(appId, keyPair.getPrivate(), now);

        assertThat(jwt).isNotNull();
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        // Verify header
        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode headerNode = objectMapper.readTree(headerBytes);
        assertThat(headerNode.get("alg").asText()).isEqualTo("RS256");
        assertThat(headerNode.get("typ").asText()).isEqualTo("JWT");

        // Verify payload claims
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode payloadNode = objectMapper.readTree(payloadBytes);
        assertThat(payloadNode.get("iss").asText()).isEqualTo("123456");
        // iat should be now - 60s
        long expectedIat = now.minusSeconds(60).getEpochSecond();
        assertThat(payloadNode.get("iat").asLong()).isEqualTo(expectedIat);
        // exp should be now + 9m (540s)
        long expectedExp = now.plusSeconds(540).getEpochSecond();
        assertThat(payloadNode.get("exp").asLong()).isEqualTo(expectedExp);

        // Verify cryptographic signature with the public key
        String signingInput = parts[0] + "." + parts[1];
        byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(signatureBytes)).isTrue();
    }

    @Test
    void parsePrivateKey_fromPkcs8PemString_parsesSuccessfully() {
        PrivateKey parsed = jwtService.parsePrivateKey(privateKeyPkcs8Pem);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getAlgorithm()).isEqualTo("RSA");

        // Verify it can create a valid signature
        String jwt = jwtService.createJwt("app-id", parsed, Instant.now());
        assertThat(jwt).isNotBlank();
    }

    @Test
    void parsePrivateKey_fromFile_readsAndParsesSuccessfully() throws IOException {
        Path tempKeyFile = Files.createTempFile("changescope-test-key", ".pem");
        try {
            Files.writeString(tempKeyFile, privateKeyPkcs8Pem);

            PrivateKey parsed = jwtService.parsePrivateKey(tempKeyFile.toString());
            assertThat(parsed).isNotNull();
            assertThat(parsed.getAlgorithm()).isEqualTo("RSA");
        } finally {
            Files.deleteIfExists(tempKeyFile);
        }
    }

    @Test
    void parsePrivateKey_invalidPem_throwsException() {
        assertThatThrownBy(() -> jwtService.parsePrivateKey("Not a valid PEM content"))
            .isInstanceOf(GitHubAuthenticationException.class);

        assertThatThrownBy(() -> jwtService.parsePrivateKey(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenProvider_gitHubApp_fetchesAndCachesInstallationToken() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setInstallationId(5555L);
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        String tokenResponse = """
            {
              "token": "ghs_installation_token_alpha",
              "expires_at": "2026-10-17T16:00:00Z"
            }
            """;

        // Expected only ONCE due to caching
        mockServer.expect(requestTo("https://api.github.com/app/installations/5555/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
            .andRespond(withSuccess(tokenResponse, MediaType.APPLICATION_JSON));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        // First call triggers network request
        Optional<String> token1 = provider.getToken();
        assertThat(token1).contains("ghs_installation_token_alpha");

        // Second call should return cached token without network request
        Optional<String> token2 = provider.getToken();
        assertThat(token2).contains("ghs_installation_token_alpha");

        mockServer.verify();
    }

    @Test
    void tokenProvider_gitHubApp_refreshesWhenExpired() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setInstallationId(5555L);
        properties.setApiBaseUrl("https://api.github.com");
        properties.setTokenExpiryBufferSeconds(60);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        // First response has expired timestamp in the past
        String expiredResponse = String.format("""
            {
              "token": "ghs_token_expired",
              "expires_at": "%s"
            }
            """, Instant.now().minusSeconds(100));
        String refreshedResponse = String.format("""
            {
              "token": "ghs_token_fresh",
              "expires_at": "%s"
            }
            """, Instant.now().plusSeconds(3600));

        mockServer.expect(requestTo("https://api.github.com/app/installations/5555/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(expiredResponse, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.com/app/installations/5555/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(refreshedResponse, MediaType.APPLICATION_JSON));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        // First call gets expired token
        Optional<String> token1 = provider.getToken();
        assertThat(token1).contains("ghs_token_expired");

        // Second call immediately detects expired token and fetches fresh token
        Optional<String> token2 = provider.getToken();
        assertThat(token2).contains("ghs_token_fresh");

        mockServer.verify();
    }

    @Test
    void tokenProvider_gitHubApp_usesRepositoryInstallationId() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setApiBaseUrl("https://api.github.com");

        RepositoryEntityRepository repoRepository = mock(RepositoryEntityRepository.class);
        RepositoryEntity repo = new RepositoryEntity("changescope-org", "order-service", "main", 101L, 8888L);
        when(repoRepository.findByOwnerAndName("changescope-org", "order-service")).thenReturn(Optional.of(repo));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        String tokenResponse = """
            {
              "token": "ghs_repo_specific_installation_token",
              "expires_at": "2026-10-17T16:00:00Z"
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/app/installations/8888/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(tokenResponse, MediaType.APPLICATION_JSON));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder, repoRepository);

        Optional<String> token = provider.getTokenForRepository("changescope-org", "order-service");
        assertThat(token).contains("ghs_repo_specific_installation_token");

        mockServer.verify();
    }

    @Test
    void tokenProvider_gitHubApp_missingInstallationIdOnRepository_failsClearlyWithoutPatFallback() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setToken("ghp_pat_should_never_be_used");
        properties.setApiBaseUrl("https://api.github.com");

        RepositoryEntityRepository repoRepository = mock(RepositoryEntityRepository.class);
        // Repository has no installation ID
        RepositoryEntity repoWithoutInstallation = new RepositoryEntity("changescope-org", "order-service", "main", 101L, null);
        when(repoRepository.findByOwnerAndName("changescope-org", "order-service")).thenReturn(Optional.of(repoWithoutInstallation));

        RestClient.Builder builder = RestClient.builder();
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder, repoRepository);

        assertThatThrownBy(() -> provider.getTokenForRepository("changescope-org", "order-service"))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("missing required GitHub App installation ID");
    }

    @Test
    void tokenProvider_gitHubApp_unregisteredRepository_failsClearly() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setApiBaseUrl("https://api.github.com");

        RepositoryEntityRepository repoRepository = mock(RepositoryEntityRepository.class);
        when(repoRepository.findByOwnerAndName("changescope-org", "unknown-service")).thenReturn(Optional.empty());

        RestClient.Builder builder = RestClient.builder();
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder, repoRepository);

        assertThatThrownBy(() -> provider.getTokenForRepository("changescope-org", "unknown-service"))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("not registered or missing GitHub App installation ID");
    }

    @Test
    void tokenProvider_gitHubApp_distinctRepositoriesUseRespectiveInstallationTokens() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setApiBaseUrl("https://api.github.com");

        RepositoryEntityRepository repoRepository = mock(RepositoryEntityRepository.class);
        RepositoryEntity repo1 = new RepositoryEntity("org-a", "repo-1", "main", 101L, 1001L);
        RepositoryEntity repo2 = new RepositoryEntity("org-b", "repo-2", "main", 102L, 2002L);
        when(repoRepository.findByOwnerAndName("org-a", "repo-1")).thenReturn(Optional.of(repo1));
        when(repoRepository.findByOwnerAndName("org-b", "repo-2")).thenReturn(Optional.of(repo2));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        mockServer.expect(requestTo("https://api.github.com/app/installations/1001/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""
                { "token": "ghs_token_for_inst_1001", "expires_at": "2026-10-17T16:00:00Z" }
                """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.com/app/installations/2002/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""
                { "token": "ghs_token_for_inst_2002", "expires_at": "2026-10-17T16:00:00Z" }
                """, MediaType.APPLICATION_JSON));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder, repoRepository);

        Optional<String> token1 = provider.getTokenForRepository("org-a", "repo-1");
        Optional<String> token2 = provider.getTokenForRepository("org-b", "repo-2");

        assertThat(token1).contains("ghs_token_for_inst_1001");
        assertThat(token2).contains("ghs_token_for_inst_2002");
        assertThat(token1).isNotEqualTo(token2);

        mockServer.verify();
    }

    @Test
    void tokenProvider_gitHubApp_handlesRefreshFailureGracefully() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setInstallationId(5555L);
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        mockServer.expect(requestTo("https://api.github.com/app/installations/5555/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        assertThatThrownBy(provider::getToken)
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("Failed to retrieve GitHub App installation token (HTTP 401)");

        mockServer.verify();
    }

    @Test
    void tokenProvider_precedence_gitHubAppTakesPrecedenceOverPersonalToken() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(privateKeyPkcs8Pem);
        properties.setInstallationId(5555L);
        properties.setToken("ghp_fallback_pat_should_be_ignored");
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        String tokenResponse = """
            {
              "token": "ghs_app_installation_token",
              "expires_at": "2026-10-17T16:00:00Z"
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/app/installations/5555/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(tokenResponse, MediaType.APPLICATION_JSON));

        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        Optional<String> token = provider.getToken();
        assertThat(token).contains("ghs_app_installation_token");
        assertThat(token.orElseThrow()).isNotEqualTo("ghp_fallback_pat_should_be_ignored");

        mockServer.verify();
    }

    @Test
    void tokenProvider_fallback_personalTokenWhenAppNotConfigured() {
        GitHubProperties properties = new GitHubProperties();
        properties.setToken("ghp_local_dev_token");
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        Optional<String> token = provider.getToken();
        assertThat(token).contains("ghp_local_dev_token");
    }

    @Test
    void tokenProvider_unauthenticatedFallbackWhenNoConfig() {
        GitHubProperties properties = new GitHubProperties();
        properties.setApiBaseUrl("https://api.github.com");

        RestClient.Builder builder = RestClient.builder();
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        Optional<String> token = provider.getToken();
        assertThat(token).isEmpty();
    }

    @Test
    void tokenProvider_missingPrivateKeyWhenAppIdConfigured_failsClearly() {
        GitHubProperties properties = new GitHubProperties();
        properties.setAppId("app-123");
        properties.setPrivateKey(null);
        properties.setPrivateKeyPath(null);

        RestClient.Builder builder = RestClient.builder();
        DefaultGitHubTokenProvider provider = new DefaultGitHubTokenProvider(properties, jwtService, builder);

        assertThatThrownBy(provider::getToken)
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("GitHub App ID is configured but private key is missing or blank");
    }

    @Test
    void restGitHubGateway_usesDynamicBearerAuthFromTokenProvider() {
        GitHubProperties properties = new GitHubProperties();
        properties.setApiBaseUrl("https://api.github.com");

        GitHubTokenProvider mockTokenProvider = () -> Optional.of("ghs_dynamic_injected_token");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        String prJson = """
            {
              "number": 1,
              "title": "Dynamic Auth Test",
              "state": "open",
              "merged": false,
              "base": { "sha": "base1" },
              "head": { "sha": "head1" }
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghs_dynamic_injected_token"))
            .andRespond(withSuccess(prJson, MediaType.APPLICATION_JSON));

        RestGitHubGateway gateway = new RestGitHubGateway(properties, mockTokenProvider, builder);
        var pr = gateway.getPullRequest("owner", "repo", 1);

        assertThat(pr).isNotNull();
        assertThat(pr.title()).isEqualTo("Dynamic Auth Test");
        mockServer.verify();
    }
}
