package com.changescope.service.github;

import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GitDiffExtractionResult;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.TypeChangeEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffTypeExtractorTest {

    private GitDiffTypeExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GitDiffTypeExtractor();
    }

    @Test
    void changedMethodBody_identifiesEnclosingTypeOnly() {
        String source = """
            package com.changescope.order;

            public class OrderService {
                public void checkout() {
                    validate();
                }
            }

            class HelperService {
                public void helper() {}
            }
            """;

        // Patch modifies inside checkout() at line 5
        String patch = """
            @@ -4,3 +4,4 @@ public class OrderService {
                 public void checkout() {
            +        log();
                     validate();
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "src/main/java/com/changescope/order/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("src/main/java/com/changescope/order/OrderService.java", source)
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.order.OrderService");
        assertThat(result.changedTypes()).doesNotContain("com.changescope.order.HelperService");
        assertThat(result.detections().get(0).evidence()).isEqualTo(TypeChangeEvidence.PATCH_LINE_MATCH);
    }

    @Test
    void changedClassDeclaration_identifiesModifiedClass() {
        String source = """
            package com.changescope.payment;

            public interface PaymentGateway {
                boolean charge();
            }
            """;

        String patch = """
            @@ -2,3 +2,3 @@ package com.changescope.payment;
            -public interface PaymentGateway {
            +public interface PaymentGateway extends BaseGateway {
                 boolean charge();
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "PaymentGateway.java",
            FileChangeStatus.MODIFIED,
            1,
            1,
            patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("PaymentGateway.java", source)
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.payment.PaymentGateway");
        assertThat(result.detections().get(0).evidence()).isEqualTo(TypeChangeEvidence.PATCH_LINE_MATCH);
    }

    @Test
    void unrelatedUnchangedTypeInSameFile_isNotMarkedAsChanged() {
        String source = """
            package com.changescope.example;

            public class TypeA {
                public void doA() {}
            }

            class TypeB {
                public void doB() {
                    System.out.println("B");
                }
            }
            """;

        // Patch touches line 9 (inside TypeB)
        String patch = """
            @@ -8,3 +8,4 @@ class TypeB {
                 public void doB() {
            +        logB();
                     System.out.println("B");
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "Example.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("Example.java", source)
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.example.TypeB");
        assertThat(result.changedTypes()).doesNotContain("com.changescope.example.TypeA");
    }

    @Test
    void missingPatchText_fallsBackToAllDeclaredTypesWithWarning() {
        String source = """
            package com.changescope.order;

            public class OrderService {}
            class HelperService {}
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "OrderService.java",
            FileChangeStatus.MODIFIED,
            10,
            5,
            null // Missing patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("OrderService.java", source)
        );

        assertThat(result.changedTypes()).containsExactly(
            "com.changescope.order.HelperService",
            "com.changescope.order.OrderService"
        );
        assertThat(result.detections()).allMatch(d -> d.evidence() == TypeChangeEvidence.FILE_LEVEL_FALLBACK);
        assertThat(result.warnings()).anyMatch(w -> w.contains("Patch text unavailable"));
    }

    @Test
    void deletedFiles_withPreviousSource_extractsDeletedTypes() {
        String prevSource = """
            package com.changescope.legacy;

            public class LegacyPaymentGateway {}
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "LegacyPaymentGateway.java",
            FileChangeStatus.DELETED,
            0,
            20,
            null
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of(),
            Map.of("LegacyPaymentGateway.java", prevSource)
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.legacy.LegacyPaymentGateway");
        assertThat(result.detections().get(0).evidence()).isEqualTo(TypeChangeEvidence.DELETED_FILE_PREVIOUS_SOURCE);
    }

    @Test
    void deletedFiles_withoutPreviousSource_infersFromPathWithWarning() {
        PullRequestFileChange change = new PullRequestFileChange(
            "src/main/java/com/changescope/billing/InvoiceService.java",
            FileChangeStatus.DELETED,
            0,
            50,
            null
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of()
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.billing.InvoiceService");
        assertThat(result.detections().get(0).evidence()).isEqualTo(TypeChangeEvidence.DELETED_FILE_INFERRED_PATH);
        assertThat(result.warnings()).anyMatch(w -> w.contains("inferred candidate type from path"));
    }

    @Test
    void malformedSource_handlesSyntaxErrorGracefullyWithWarning() {
        String invalidSource = "class Broken { not valid java syntax ???";

        String patch = """
            @@ -1,1 +1,2 @@
             class Broken {
            +    int x;
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "Broken.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("Broken.java", invalidSource)
        );

        assertThat(result.changedTypes()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Failed to parse source file 'Broken.java'"));
    }

    @Test
    void nestedTypes_identifiesNestedAndOuterTypesCorrectly() {
        String source = """
            package com.changescope.nest;

            public class Outer {
                public static class Inner {
                    public void innerWork() {}
                }
            }
            """;

        // Modify line 5 (inside innerWork)
        String patch = """
            @@ -4,3 +4,4 @@ public class Outer {
                     public void innerWork() {
            +            log();
                     }
            """;

        PullRequestFileChange change = new PullRequestFileChange(
            "Outer.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            patch
        );

        GitDiffExtractionResult result = extractor.extractChangedTypes(
            List.of(change),
            Map.of("Outer.java", source)
        );

        assertThat(result.changedTypes()).contains(
            "com.changescope.nest.Outer.Inner",
            "com.changescope.nest.Outer"
        );
    }
}
