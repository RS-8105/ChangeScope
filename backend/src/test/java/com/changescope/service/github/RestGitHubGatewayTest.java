package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.SourceFileContent;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestGitHubGatewayTest {

    private GitHubProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        properties = new GitHubProperties();
        properties.setApiBaseUrl("https://api.github.com");
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @Test
    void getPullRequest_successfulResponse_mapsMetadataCorrectly() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        String json = """
            {
              "number": 42,
              "title": "Fix payment timeout bug",
              "state": "open",
              "merged": false,
              "base": {
                "sha": "base1111"
              },
              "head": {
                "sha": "head9999"
              }
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/42"))
            .andExpect(header("Accept", "application/vnd.github+json"))
            .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PullRequestMetadata metadata = gateway.getPullRequest("octocat", "Hello-World", 42);

        mockServer.verify();
        assertThat(metadata.owner()).isEqualTo("octocat");
        assertThat(metadata.repo()).isEqualTo("Hello-World");
        assertThat(metadata.number()).isEqualTo(42);
        assertThat(metadata.title()).isEqualTo("Fix payment timeout bug");
        assertThat(metadata.baseCommitSha()).isEqualTo("base1111");
        assertThat(metadata.headCommitSha()).isEqualTo("head9999");
        assertThat(metadata.state()).isEqualTo(PullRequestState.OPEN);
        assertThat(metadata.isOpen()).isTrue();
    }

    @Test
    void getPullRequest_mergedState_mapsToMergedState() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        String json = """
            {
              "number": 100,
              "title": "Merge feature branch",
              "state": "closed",
              "merged": true,
              "base": { "sha": "base111" },
              "head": { "sha": "head222" }
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/100"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PullRequestMetadata metadata = gateway.getPullRequest("octocat", "Hello-World", 100);

        mockServer.verify();
        assertThat(metadata.state()).isEqualTo(PullRequestState.MERGED);
        assertThat(metadata.isOpen()).isFalse();
    }

    @Test
    void getPullRequestFiles_multiplePages_aggregatesAllFilesAcrossPages() {
        // Set perPage to 2 so page 1 triggers page 2
        RestGitHubGateway gateway = new RestGitHubGateway(
            properties,
            restClientBuilder,
            100, // maxFiles
            5,   // maxPages
            2,   // perPage
            2 * 1024 * 1024,
            100
        );

        String page1Json = """
            [
              {
                "filename": "src/main/OrderService.java",
                "status": "modified",
                "additions": 10,
                "deletions": 2,
                "patch": "@@ -1,5 +1,13 @@"
              },
              {
                "filename": "src/main/OrderController.java",
                "status": "added",
                "additions": 25,
                "deletions": 0,
                "patch": "@@ -0,0 +1,25 @@"
              }
            ]
            """;

        String page2Json = """
            [
              {
                "filename": "src/test/OrderServiceTest.java",
                "status": "removed",
                "additions": 0,
                "deletions": 15,
                "patch": "@@ -1,15 +0,0 @@"
              }
            ]
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/5/files?per_page=2&page=1"))
            .andRespond(withSuccess(page1Json, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/5/files?per_page=2&page=2"))
            .andRespond(withSuccess(page2Json, MediaType.APPLICATION_JSON));

        List<PullRequestFileChange> files = gateway.getPullRequestFiles("octocat", "Hello-World", 5);

        mockServer.verify();
        assertThat(files).hasSize(3);

        assertThat(files.get(0).filePath()).isEqualTo("src/main/OrderService.java");
        assertThat(files.get(0).status()).isEqualTo(FileChangeStatus.MODIFIED);
        assertThat(files.get(0).additions()).isEqualTo(10);

        assertThat(files.get(1).filePath()).isEqualTo("src/main/OrderController.java");
        assertThat(files.get(1).status()).isEqualTo(FileChangeStatus.ADDED);

        // 'removed' status mapped to DELETED
        assertThat(files.get(2).filePath()).isEqualTo("src/test/OrderServiceTest.java");
        assertThat(files.get(2).status()).isEqualTo(FileChangeStatus.DELETED);
    }

    @Test
    void getPullRequestFiles_exceedsMaxFileLimit_throwsLimitExceededException() {
        // Set maxFiles to 2, perPage to 3
        RestGitHubGateway gateway = new RestGitHubGateway(
            properties,
            restClientBuilder,
            2, // maxFiles
            5,
            3,
            2 * 1024 * 1024,
            100
        );

        String json = """
            [
              { "filename": "FileA.java", "status": "added", "additions": 1, "deletions": 0 },
              { "filename": "FileB.java", "status": "added", "additions": 1, "deletions": 0 },
              { "filename": "FileC.java", "status": "added", "additions": 1, "deletions": 0 }
            ]
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/10/files?per_page=3&page=1"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.getPullRequestFiles("octocat", "Hello-World", 10))
            .isInstanceOf(GitHubApiException.class)
            .hasMessageContaining("exceeds maximum allowed file change limit");
    }

    @Test
    void getFileContent_base64EncodedContent_decodesCorrectly() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        String rawSource = "package com.changescope;\npublic class OrderService {\n    public void checkout() {}\n}\n";
        String base64Content = Base64.getEncoder().encodeToString(rawSource.getBytes(StandardCharsets.UTF_8));
        // GitHub sends Base64 with escaped \\n in the JSON string
        String githubBase64 = base64Content.substring(0, 20) + "\\n" + base64Content.substring(20);

        String json = """
            {
              "type": "file",
              "encoding": "base64",
              "size": 75,
              "name": "OrderService.java",
              "path": "src/main/OrderService.java",
              "content": "%s",
              "sha": "blobSha123"
            }
            """.formatted(githubBase64);

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/contents/src/main/OrderService.java?ref=headCommit123"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        SourceFileContent result = gateway.getFileContent("octocat", "Hello-World", "src/main/OrderService.java", "headCommit123");

        mockServer.verify();
        assertThat(result.filePath()).isEqualTo("src/main/OrderService.java");
        assertThat(result.content()).isEqualTo(rawSource);
        assertThat(result.commitSha()).isEqualTo("headCommit123");
    }

    @Test
    void getFileContent_directoryType_throwsInvalidResourceType() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        String json = """
            {
              "type": "dir",
              "size": 0,
              "name": "src",
              "path": "src"
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/contents/src"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.getFileContent("octocat", "Hello-World", "src", null))
            .isInstanceOf(GitHubApiException.class)
            .hasMessageContaining("is a directory or unsupported type");
    }

    @Test
    void optionalAuthentication_sendsBearerHeaderWhenConfigured() {
        properties.setToken("ghp_SecretTokenXYZ");
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        String json = """
            {
              "number": 1,
              "title": "Auth test",
              "state": "open",
              "base": { "sha": "b" },
              "head": { "sha": "h" }
            }
            """;

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghp_SecretTokenXYZ"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PullRequestMetadata metadata = gateway.getPullRequest("octocat", "Hello-World", 1);

        mockServer.verify();
        assertThat(metadata.number()).isEqualTo(1);
    }

    @Test
    void errorHandling_404NotFound_throwsGitHubResourceNotFoundException() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/999"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gateway.getPullRequest("octocat", "Hello-World", 999))
            .isInstanceOf(GitHubResourceNotFoundException.class)
            .hasMessageContaining("Resource not found on GitHub (HTTP 404)");
    }

    @Test
    void errorHandling_rateLimiting_throwsGitHubRateLimitException() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Reset", "1700000000");

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/1"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).headers(headers).body("API rate limit exceeded"));

        assertThatThrownBy(() -> gateway.getPullRequest("octocat", "Hello-World", 1))
            .isInstanceOf(GitHubRateLimitException.class)
            .hasMessageContaining("rate limit exceeded (HTTP 403)");
    }

    @Test
    void errorHandling_malformedResponse_throwsMalformedResponseException() {
        RestGitHubGateway gateway = new RestGitHubGateway(properties, restClientBuilder);

        mockServer.expect(requestTo("https://api.github.com/repos/octocat/Hello-World/pulls/1"))
            .andRespond(withSuccess("this is { not valid json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.getPullRequest("octocat", "Hello-World", 1))
            .isInstanceOf(GitHubApiException.class)
            .hasMessageContaining("Failed to parse or process GitHub response");
    }
}
