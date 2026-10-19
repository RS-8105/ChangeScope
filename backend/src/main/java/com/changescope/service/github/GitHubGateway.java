package com.changescope.service.github;

import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.SourceFileContent;

import java.util.List;

/**
 * Domain gateway interface for accessing GitHub pull requests and repository source files.
 * Keeps ChangeScope's analysis pipeline completely decoupled from external HTTP clients and GitHub JSON schemas.
 */
public interface GitHubGateway {

    /**
     * Retrieves metadata for a specific pull request.
     *
     * @param owner      the repository owner (user or organization)
     * @param repo       the repository name
     * @param pullNumber the pull request number
     * @return the pull request metadata
     */
    PullRequestMetadata getPullRequest(String owner, String repo, int pullNumber);

    /**
     * Lists all changed files associated with a specific pull request.
     *
     * @param owner      the repository owner
     * @param repo       the repository name
     * @param pullNumber the pull request number
     * @return list of changed files in the pull request
     */
    List<PullRequestFileChange> getPullRequestFiles(String owner, String repo, int pullNumber);

    /**
     * Retrieves the decoded source file content at a specified commit reference.
     *
     * @param owner     the repository owner
     * @param repo      the repository name
     * @param filePath  the relative file path in the repository
     * @param commitSha the commit SHA or git reference
     * @return the source file content
     */
    SourceFileContent getFileContent(String owner, String repo, String filePath, String commitSha);

    /**
     * Retrieves multiple source files at a specified commit reference.
     *
     * @param owner     the repository owner
     * @param repo      the repository name
     * @param filePaths the list of relative file paths to fetch
     * @param commitSha the commit SHA or git reference
     * @return list of source file contents
     */
    List<SourceFileContent> getFilesContent(String owner, String repo, List<String> filePaths, String commitSha);
}
