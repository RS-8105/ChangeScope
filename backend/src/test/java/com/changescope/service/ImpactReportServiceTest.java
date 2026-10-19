package com.changescope.service;

import com.changescope.dto.AffectedType;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactReportServiceTest {

    private ImpactReportService impactReportService;

    @BeforeEach
    void setUp() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusService = new BlastRadiusAnalysisService();
        impactReportService = new ImpactReportService(dependencyService, blastRadiusService);
    }

    @Test
    void generateReport_successfulReport_matchesAllFieldsAndConsistentCounts() {
        String serviceSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;

            public class OrderService {
                private PaymentGateway paymentGateway;
            }
            """;

        String gatewaySource = """
            package com.changescope.payment;

            public interface PaymentGateway {}
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderService.java", serviceSource),
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource)
        );

        ImpactReport report = impactReportService.generateReport(files, List.of("com.changescope.payment.PaymentGateway"));

        // Changed types
        assertThat(report.changedTypes()).containsExactly("com.changescope.payment.PaymentGateway");
        assertThat(report.totalChangedTypes()).isEqualTo(1);
        assertThat(report.totalChangedTypes()).isEqualTo(report.changedTypes().size());

        // Affected types
        assertThat(report.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1)
        );
        assertThat(report.totalAffectedTypes()).isEqualTo(1);
        assertThat(report.totalAffectedTypes()).isEqualTo(report.affectedTypes().size());

        // Dependencies
        assertThat(report.dependencies()).containsExactly(
            new TypeDependency(
                "com.changescope.order.OrderService",
                "com.changescope.payment.PaymentGateway",
                RelationshipKind.FIELD,
                "OrderService.java"
            )
        );

        // Errors
        assertThat(report.parseErrors()).isEmpty();
        assertThat(report.hasErrors()).isFalse();
    }

    @Test
    void generateReport_transitiveImpact_preservesDistancesAndConsistentCounts() {
        String repoSource = """
            package com.changescope.order;

            public class OrderRepository {}
            """;

        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                private OrderRepository repository;
            }
            """;

        String controllerSource = """
            package com.changescope.order;

            public class OrderController {
                private OrderService orderService;
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderRepository.java", repoSource),
            new JavaSourceFileInput("OrderService.java", serviceSource),
            new JavaSourceFileInput("OrderController.java", controllerSource)
        );

        ImpactReport report = impactReportService.generateReport(files, List.of("com.changescope.order.OrderRepository"));

        assertThat(report.changedTypes()).containsExactly("com.changescope.order.OrderRepository");
        assertThat(report.totalChangedTypes()).isEqualTo(1);

        assertThat(report.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1),
            new AffectedType("com.changescope.order.OrderController", 2)
        );
        assertThat(report.totalAffectedTypes()).isEqualTo(2);

        // Counts guarantee
        assertThat(report.totalChangedTypes()).isEqualTo(report.changedTypes().size());
        assertThat(report.totalAffectedTypes()).isEqualTo(report.affectedTypes().size());
        assertThat(report.dependencies()).hasSize(2);
        assertThat(report.parseErrors()).isEmpty();
    }

    @Test
    void generateReport_partialParsingFailures_continuesAnalysisForValidFiles() {
        String repoSource = """
            package com.changescope.order;

            public class OrderRepository {}
            """;

        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                private OrderRepository repository;
            }
            """;

        String brokenSource = """
            package com.changescope.broken;

            public class BrokenClass {
                public void syntaxError(
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderRepository.java", repoSource),
            new JavaSourceFileInput("OrderService.java", serviceSource),
            new JavaSourceFileInput("BrokenClass.java", brokenSource)
        );

        ImpactReport report = impactReportService.generateReport(files, List.of("com.changescope.order.OrderRepository"));

        // Has parsing error for broken file
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.parseErrors()).hasSize(1);
        FileParseError error = report.parseErrors().get(0);
        assertThat(error.filePath()).isEqualTo("BrokenClass.java");
        assertThat(error.errorMessage()).containsIgnoringCase("syntax");

        // Continues and extracts valid dependencies and blast radius
        assertThat(report.changedTypes()).containsExactly("com.changescope.order.OrderRepository");
        assertThat(report.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1)
        );
        assertThat(report.dependencies()).containsExactly(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java")
        );

        assertThat(report.totalChangedTypes()).isEqualTo(1);
        assertThat(report.totalAffectedTypes()).isEqualTo(1);
    }

    @Test
    void generateReport_emptyAndNullInputs_handledGracefully() {
        // Null inputs
        ImpactReport reportNull = impactReportService.generateReport((List<JavaSourceFileInput>) null, (List<String>) null);
        assertThat(reportNull.changedTypes()).isEmpty();
        assertThat(reportNull.affectedTypes()).isEmpty();
        assertThat(reportNull.totalChangedTypes()).isZero();
        assertThat(reportNull.totalAffectedTypes()).isZero();
        assertThat(reportNull.dependencies()).isEmpty();
        assertThat(reportNull.parseErrors()).isEmpty();

        // Empty inputs
        ImpactReport reportEmpty = impactReportService.generateReport(List.of(), List.of());
        assertThat(reportEmpty.changedTypes()).isEmpty();
        assertThat(reportEmpty.affectedTypes()).isEmpty();
        assertThat(reportEmpty.totalChangedTypes()).isZero();
        assertThat(reportEmpty.totalAffectedTypes()).isZero();

        // Valid files but empty changed types
        String source = """
            package com.changescope.example;
            public class Example {}
            """;
        ImpactReport reportNoChanges = impactReportService.generateReport(
            List.of(new JavaSourceFileInput("Example.java", source)),
            List.of()
        );
        assertThat(reportNoChanges.changedTypes()).isEmpty();
        assertThat(reportNoChanges.affectedTypes()).isEmpty();
        assertThat(reportNoChanges.totalChangedTypes()).isZero();
        assertThat(reportNoChanges.totalAffectedTypes()).isZero();
        assertThat(reportNoChanges.parseErrors()).isEmpty();
    }

    @Test
    void generateReport_unknownChangedTypes_handledGracefully() {
        String source = """
            package com.changescope.example;
            public class Example {}
            """;

        ImpactReport report = impactReportService.generateReport(
            List.of(new JavaSourceFileInput("Example.java", source)),
            List.of("com.changescope.unknown.NonExistentService")
        );

        assertThat(report.changedTypes()).containsExactly("com.changescope.unknown.NonExistentService");
        assertThat(report.totalChangedTypes()).isEqualTo(1);
        assertThat(report.affectedTypes()).isEmpty();
        assertThat(report.totalAffectedTypes()).isZero();
        assertThat(report.parseErrors()).isEmpty();
    }

    @Test
    void generateReport_consistentCounts_matchesListSizesExactly() {
        String repoSource = """
            package com.changescope.order;
            public class OrderRepository {}
            """;
        String gatewaySource = """
            package com.changescope.payment;
            public class PaymentGateway {}
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderRepository.java", repoSource),
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource)
        );

        ImpactReport report = impactReportService.generateReport(
            files,
            List.of("com.changescope.order.OrderRepository", "com.changescope.payment.PaymentGateway", "com.changescope.unknown.Third")
        );

        assertThat(report.totalChangedTypes()).isEqualTo(3);
        assertThat(report.totalChangedTypes()).isEqualTo(report.changedTypes().size());
        assertThat(report.totalAffectedTypes()).isZero();
        assertThat(report.totalAffectedTypes()).isEqualTo(report.affectedTypes().size());
    }

    @Test
    void generateReport_withTestFiles_identifiesCandidateTests() {
        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                public void checkout(String id) {}
            }
            """;

        String testSource = """
            package com.changescope.order;

            import org.junit.jupiter.api.Test;

            public class OrderServiceTest {
                private OrderService orderService;

                @Test
                void testCheckout() {
                    orderService.checkout("123");
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/main/java/OrderService.java", serviceSource),
            new JavaSourceFileInput("src/test/java/OrderServiceTest.java", testSource)
        );

        ImpactReport report = impactReportService.generateReport(
            files,
            List.of("com.changescope.order.OrderService")
        );

        assertThat(report.totalCandidateTests()).isEqualTo(1);
        assertThat(report.candidateTests()).hasSize(1);
        assertThat(report.candidateTests().get(0).testMethodName()).isEqualTo("testCheckout");
        assertThat(report.candidateTests().get(0).testClassName()).isEqualTo("com.changescope.order.OrderServiceTest");

        // Verification plan assertions
        assertThat(report.verificationPlan()).isNotNull();
        assertThat(report.verificationPlan().confidence()).isNotNull();
        assertThat(report.verificationPlan().confidence().rating()).isEqualTo("HIGH_CONFIDENCE");
        assertThat(report.verificationPlan().recommendations()).isNotEmpty();
    }
}
