package com.changescope.service.github;

import com.changescope.config.GitHubProperties;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.SourceFileContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubFoundationTest {

    @Test
    void gitHubProperties_defaults_matchExpectedValuesAndMaskToken() {
        GitHubProperties properties = new GitHubProperties();

        assertThat(properties.getApiBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(properties.getToken()).isNull();
        assertThat(properties.hasToken()).isFalse();
        assertThat(properties.toString()).doesNotContain("secret");
        assertThat(properties.toString()).contains("hasToken=false");
    }

    @Test
    void gitHubProperties_customConfiguration_normalizesValuesAndProtectsSecret() {
        GitHubProperties properties = new GitHubProperties();
        properties.setApiBaseUrl("https://github.myenterprise.internal///");
        properties.setToken("ghp_Secret123TokenXYZ   ");

        assertThat(properties.getApiBaseUrl()).isEqualTo("https://github.myenterprise.internal");
        assertThat(properties.getToken()).isEqualTo("ghp_Secret123TokenXYZ");
        assertThat(properties.hasToken()).isTrue();

        // Ensure token is never leaked via toString()
        assertThat(properties.toString()).doesNotContain("ghp_Secret123TokenXYZ");
        assertThat(properties.toString()).contains("hasToken=true");
    }

    @Test
    void pullRequestMetadata_domainPropertiesAndHelpers_workAsExpected() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Fix payment gateway timeout",
            "base111122223333444455556666777788889999",
            "head999988887777666655554444333322221111",
            PullRequestState.OPEN
        );

        assertThat(pr.getRepositoryFullName()).isEqualTo("octocat/Hello-World");
        assertThat(pr.isOpen()).isTrue();
        assertThat(pr.number()).isEqualTo(42);
        assertThat(pr.title()).isEqualTo("Fix payment gateway timeout");
        assertThat(pr.baseCommitSha()).isEqualTo("base111122223333444455556666777788889999");
        assertThat(pr.headCommitSha()).isEqualTo("head999988887777666655554444333322221111");
    }

    @Test
    void pullRequestFileChange_statusAndJavaDetection_workCorrectly() {
        PullRequestFileChange javaFile = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            12,
            3,
            "@@ -10,3 +10,12 @@"
        );

        assertThat(javaFile.isJavaSource()).isTrue();
        assertThat(javaFile.hasPatch()).isTrue();
        assertThat(javaFile.status()).isEqualTo(FileChangeStatus.MODIFIED);

        PullRequestFileChange nonJavaFile = new PullRequestFileChange(
            "README.md",
            FileChangeStatus.fromString("modified"),
            5,
            0,
            null
        );

        assertThat(nonJavaFile.isJavaSource()).isFalse();
        assertThat(nonJavaFile.hasPatch()).isFalse();
        assertThat(nonJavaFile.status()).isEqualTo(FileChangeStatus.MODIFIED);
    }

    @Test
    void sourceFileContent_bridgesToAnalysisPipelineCleanly() {
        SourceFileContent source = new SourceFileContent(
            "OrderService.java",
            "public class OrderService {}",
            "commitSha123"
        );

        JavaSourceFileInput fileInput = source.toFileInput();
        assertThat(fileInput.filePath()).isEqualTo("OrderService.java");
        assertThat(fileInput.sourceCode()).isEqualTo("public class OrderService {}");
    }
}
