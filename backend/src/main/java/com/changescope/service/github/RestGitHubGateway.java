package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.SourceFileContent;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class RestGitHubGateway implements GitHubGateway {

    private static final int DEFAULT_MAX_FILES_PER_PR = 300;
    private static final int DEFAULT_MAX_PAGES = 5;
    private static final int DEFAULT_PER_PAGE = 100;
    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB
    private static final int DEFAULT_MAX_BATCH_FILES = 100;

    private final RestClient restClient;
    private final GitHubProperties properties;
    private final GitHubTokenProvider tokenProvider;
    private final int maxFilesPerPr;
    private final int maxPages;
    private final int perPage;
    private final long maxFileSizeBytes;
    private final int maxBatchFiles;

    public RestGitHubGateway(GitHubProperties properties) {
        this(properties, new DefaultGitHubTokenProvider(properties, new GitHubAppJwtService(), RestClient.builder()));
    }

    public RestGitHubGateway(GitHubProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, new DefaultGitHubTokenProvider(properties, new GitHubAppJwtService(), restClientBuilder), restClientBuilder);
    }

    public RestGitHubGateway(GitHubProperties properties, GitHubTokenProvider tokenProvider) {
        this(properties, tokenProvider, RestClient.builder());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RestGitHubGateway(GitHubProperties properties, GitHubTokenProvider tokenProvider, RestClient.Builder restClientBuilder) {
        this(
            properties,
            tokenProvider,
            restClientBuilder,
            DEFAULT_MAX_FILES_PER_PR,
            DEFAULT_MAX_PAGES,
            DEFAULT_PER_PAGE,
            DEFAULT_MAX_FILE_SIZE_BYTES,
            DEFAULT_MAX_BATCH_FILES
        );
    }

    public RestGitHubGateway(
        GitHubProperties properties,
        RestClient.Builder restClientBuilder,
        int maxFilesPerPr,
        int maxPages,
        int perPage,
        long maxFileSizeBytes,
        int maxBatchFiles
    ) {
        this(
            properties,
            new DefaultGitHubTokenProvider(properties, new GitHubAppJwtService(), restClientBuilder),
            restClientBuilder,
            maxFilesPerPr,
            maxPages,
            perPage,
            maxFileSizeBytes,
            maxBatchFiles
        );
    }

    public RestGitHubGateway(
        GitHubProperties properties,
        GitHubTokenProvider tokenProvider,
        RestClient.Builder restClientBuilder,
        int maxFilesPerPr,
        int maxPages,
        int perPage,
        long maxFileSizeBytes,
        int maxBatchFiles
    ) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.maxFilesPerPr = maxFilesPerPr;
        this.maxPages = maxPages;
        this.perPage = perPage;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxBatchFiles = maxBatchFiles;

        RestClient.Builder builder = restClientBuilder
            .baseUrl(properties.getApiBaseUrl())
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .defaultHeader(HttpHeaders.USER_AGENT, "ChangeScope-App")
            .requestInterceptor((request, body, execution) -> {
                String path = request.getURI().getPath();
                RepoCoordinates coords = extractRepoCoordinates(path);
                Optional<String> token = (coords != null)
                    ? tokenProvider.getTokenForRepository(coords.owner(), coords.repo())
                    : tokenProvider.getToken();
                token.ifPresent(t -> request.getHeaders().setBearerAuth(t));
                return execution.execute(request, body);
            });

        this.restClient = builder.build();
    }

    record RepoCoordinates(String owner, String repo) {}

    static RepoCoordinates extractRepoCoordinates(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.indexOf("/repos/");
        if (idx == -1) {
            return null;
        }
        String remaining = path.substring(idx + 7);
        int slash1 = remaining.indexOf('/');
        if (slash1 <= 0) {
            return null;
        }
        String owner = remaining.substring(0, slash1);
        String afterOwner = remaining.substring(slash1 + 1);
        int slash2 = afterOwner.indexOf('/');
        String repo = (slash2 == -1) ? afterOwner : afterOwner.substring(0, slash2);
        if (owner.isBlank() || repo.isBlank()) {
            return null;
        }
        return new RepoCoordinates(owner, repo);
    }

    @Override
    public PullRequestMetadata getPullRequest(String owner, String repo, int pullNumber) {
        validateRepoCoordinates(owner, repo);
        if (pullNumber <= 0) {
            throw new IllegalArgumentException("Pull request number must be positive");
        }

        String resourceDesc = "Pull Request " + owner + "/" + repo + "#" + pullNumber;
        GitHubPullRequestWire wire = executeWithExceptionHandling(resourceDesc, () ->
            restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, pullNumber)
                .retrieve()
                .body(GitHubPullRequestWire.class)
        );

        if (wire == null) {
            throw new GitHubApiException(500, "MALFORMED_RESPONSE", "Received null response for " + resourceDesc);
        }

        PullRequestState state;
        if (Boolean.TRUE.equals(wire.merged())) {
            state = PullRequestState.MERGED;
        } else {
            state = PullRequestState.fromString(wire.state());
        }

        String baseSha = (wire.base() != null) ? wire.base().sha() : null;
        String headSha = (wire.head() != null) ? wire.head().sha() : null;

        return new PullRequestMetadata(
            owner,
            repo,
            wire.number() > 0 ? wire.number() : pullNumber,
            wire.title() != null ? wire.title() : "",
            baseSha,
            headSha,
            state
        );
    }

    @Override
    public List<PullRequestFileChange> getPullRequestFiles(String owner, String repo, int pullNumber) {
        validateRepoCoordinates(owner, repo);
        if (pullNumber <= 0) {
            throw new IllegalArgumentException("Pull request number must be positive");
        }

        List<PullRequestFileChange> allFiles = new ArrayList<>();
        int page = 1;

        while (true) {
            final int currentPage = page;
            String resourceDesc = "Files for Pull Request " + owner + "/" + repo + "#" + pullNumber + " (page " + currentPage + ")";

            List<GitHubFileChangeWire> pageItems = executeWithExceptionHandling(resourceDesc, () ->
                restClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/pulls/{number}/files")
                        .queryParam("per_page", perPage)
                        .queryParam("page", currentPage)
                        .build(owner, repo, pullNumber))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GitHubFileChangeWire>>() {})
            );

            if (pageItems == null || pageItems.isEmpty()) {
                break;
            }

            for (GitHubFileChangeWire wire : pageItems) {
                allFiles.add(mapToFileChange(wire));
                if (allFiles.size() > maxFilesPerPr) {
                    throw new GitHubApiException(400, "LIMIT_EXCEEDED",
                        "Pull request " + owner + "/" + repo + "#" + pullNumber
                            + " exceeds maximum allowed file change limit (" + maxFilesPerPr + " files)");
                }
            }

            if (pageItems.size() < perPage) {
                // Reached the final page
                break;
            }

            page++;
            if (page > maxPages) {
                throw new GitHubApiException(400, "LIMIT_EXCEEDED",
                    "Pull request " + owner + "/" + repo + "#" + pullNumber
                        + " exceeds maximum allowed pagination depth (" + maxPages + " pages)");
            }
        }

        return allFiles;
    }

    @Override
    public SourceFileContent getFileContent(String owner, String repo, String filePath, String commitSha) {
        validateRepoCoordinates(owner, repo);
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be null or blank");
        }
        String cleanPath = filePath.trim().replaceAll("^/+", "");
        String cleanRef = (commitSha != null && !commitSha.isBlank()) ? commitSha.trim() : null;

        String resourceDesc = "File '" + cleanPath + "' in " + owner + "/" + repo + (cleanRef != null ? " at commit " + cleanRef : "");

        GitHubContentWire wire = executeWithExceptionHandling(resourceDesc, () ->
            restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/repos/" + owner + "/" + repo + "/contents/" + cleanPath);
                    if (cleanRef != null) {
                        uriBuilder.queryParam("ref", cleanRef);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(GitHubContentWire.class)
        );

        if (wire == null) {
            throw new GitHubApiException(500, "MALFORMED_RESPONSE", "Received null content response for " + resourceDesc);
        }

        if (wire.type() != null && !"file".equalsIgnoreCase(wire.type())) {
            throw new GitHubApiException(400, "INVALID_RESOURCE_TYPE",
                "Path '" + cleanPath + "' in " + owner + "/" + repo + " is a directory or unsupported type (type=" + wire.type() + ")");
        }

        if (wire.size() != null && wire.size() > maxFileSizeBytes) {
            throw new GitHubApiException(400, "FILE_TOO_LARGE",
                "File '" + cleanPath + "' in " + owner + "/" + repo + " exceeds maximum allowed size of "
                    + maxFileSizeBytes + " bytes (actual: " + wire.size() + " bytes)");
        }

        String decodedContent = decodeContent(wire.content(), wire.encoding());
        return new SourceFileContent(cleanPath, decodedContent, commitSha);
    }

    @Override
    public List<SourceFileContent> getFilesContent(String owner, String repo, List<String> filePaths, String commitSha) {
        validateRepoCoordinates(owner, repo);
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }

        if (filePaths.size() > maxBatchFiles) {
            throw new GitHubApiException(400, "LIMIT_EXCEEDED",
                "Batch source retrieval exceeds maximum limit of " + maxBatchFiles + " files (requested: " + filePaths.size() + ")");
        }

        List<SourceFileContent> results = new ArrayList<>();
        for (String path : filePaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            results.add(getFileContent(owner, repo, path.trim(), commitSha));
        }
        return results;
    }

    private String decodeContent(String content, String encoding) {
        if (content == null || content.isBlank()) {
            return "";
        }

        if ("base64".equalsIgnoreCase(encoding)) {
            try {
                byte[] decodedBytes = Base64.getMimeDecoder().decode(content);
                return new String(decodedBytes, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new GitHubApiException(500, "MALFORMED_RESPONSE", "Failed to decode Base64 file content from GitHub API: " + ex.getMessage(), ex);
            }
        }

        return content;
    }

    private PullRequestFileChange mapToFileChange(GitHubFileChangeWire wire) {
        String filename = wire.filename() != null ? wire.filename() : "unknown";
        FileChangeStatus status = FileChangeStatus.fromString(wire.status());
        return new PullRequestFileChange(
            filename,
            status,
            wire.additions(),
            wire.deletions(),
            wire.patch(),
            wire.previousFilename()
        );
    }

    private <T> T executeWithExceptionHandling(String resourceDesc, Supplier<T> requestSupplier) {
        try {
            return requestSupplier.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) {
                throw new GitHubAuthenticationException(401, "GitHub API authentication failed (HTTP 401). Verify configured credentials.");
            } else if (status == 403) {
                String rateRemaining = (ex.getResponseHeaders() != null)
                    ? ex.getResponseHeaders().getFirst("X-RateLimit-Remaining")
                    : null;
                boolean isRateLimit = "0".equals(rateRemaining)
                    || (ex.getResponseBodyAsString() != null && ex.getResponseBodyAsString().toLowerCase().contains("rate limit"));

                if (isRateLimit) {
                    String resetTime = (ex.getResponseHeaders() != null)
                        ? ex.getResponseHeaders().getFirst("X-RateLimit-Reset")
                        : "unknown";
                    throw new GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 403). Reset at: " + resetTime);
                }
                throw new GitHubAuthenticationException(403, "GitHub API access forbidden (HTTP 403) for " + resourceDesc);
            } else if (status == 404) {
                throw new GitHubResourceNotFoundException("Resource not found on GitHub (HTTP 404): " + resourceDesc);
            } else if (status == 429) {
                throw new GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 429) for " + resourceDesc);
            } else if (status >= 500) {
                throw new GitHubApiException(status, "GITHUB_SERVER_ERROR", "GitHub API server error (HTTP " + status + ") for " + resourceDesc);
            } else {
                throw new GitHubApiException(status, "GITHUB_CLIENT_ERROR", "GitHub API request failed (HTTP " + status + ") for " + resourceDesc);
            }
        } catch (ResourceAccessException ex) {
            throw new GitHubApiException(503, "NETWORK_ERROR", "Network error communicating with GitHub API for " + resourceDesc + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            if (ex instanceof GitHubApiException gha) {
                throw gha;
            }
            throw new GitHubApiException(500, "MALFORMED_RESPONSE", "Failed to parse or process GitHub response for " + resourceDesc + ": " + ex.getMessage(), ex);
        }
    }

    private void validateRepoCoordinates(String owner, String repo) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Repository owner must not be null or blank");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be null or blank");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubPullRequestWire(
        int number,
        String title,
        String state,
        Boolean merged,
        GitHubCommitRefWire base,
        GitHubCommitRefWire head
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubCommitRefWire(
        String sha
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubFileChangeWire(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch,
        @JsonProperty("previous_filename") String previousFilename
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubContentWire(
        String type,
        String encoding,
        Long size,
        String name,
        String path,
        String content,
        String sha
    ) {}
}
