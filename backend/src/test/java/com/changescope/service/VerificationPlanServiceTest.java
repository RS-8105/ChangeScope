package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.AffectedType;
import com.changescope.dto.ConfidenceLevel;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestMatchEvidence;
import com.changescope.dto.TypeDependency;
import com.changescope.dto.UnresolvedMethodCall;
import com.changescope.dto.VerificationPlan;
import com.changescope.dto.VerificationPriority;
import com.changescope.dto.AnalysisCoverage;
import com.changescope.dto.CoverageScope;
import com.changescope.dto.VerificationRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationPlanServiceTest {

    private VerificationPlanService service;

    @BeforeEach
    void setUp() {
        service = new VerificationPlanService();
    }

    @Test
    void highConfidence_cleanReport_producesHighConfidenceAndPositiveFactors() {
        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of("com.changescope.order.OrderService#checkout(String): void"),
            List.of(new AffectedMethod("com.changescope.order.OrderController#submit(String): void", "com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of(new TestImpactCandidate(
                "OrderServiceTest.java",
                "com.changescope.order.OrderServiceTest",
                "testCheckout",
                TestMatchEvidence.METHOD_INVOCATION,
                "Direct invocation",
                "com.changescope.order.OrderService#checkout(String): void"
            ))
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(plan.confidence().score()).isGreaterThanOrEqualTo(80);
        assertThat(plan.confidence().rating()).isEqualTo("HIGH_CONFIDENCE");
        assertThat(plan.confidence().negativeFactors()).isEmpty();
        assertThat(plan.confidence().positiveFactors()).anyMatch(s -> s.contains("parsed successfully"));
        assertThat(plan.confidence().positiveFactors()).anyMatch(s -> s.contains("zero ambiguities"));

        assertThat(plan.recommendations()).isNotEmpty();
        assertThat(plan.highPriorityCount()).isGreaterThan(0);
    }

    @Test
    void unresolvedCalls_reducesConfidenceAndGeneratesAmbiguityRecommendations() {
        UnresolvedMethodCall ambiguousCall = new UnresolvedMethodCall(
            "com.changescope.order.OrderService#validate(): void",
            "com.changescope.order.OrderService",
            "verify",
            "com.changescope.payment.PaymentGateway",
            "AMBIGUOUS_OVERLOAD",
            List.of("verify(String)", "verify(int)"),
            "OrderService.java"
        );

        UnresolvedMethodCall externalCall = new UnresolvedMethodCall(
            "com.changescope.order.OrderService#log(): void",
            "com.changescope.order.OrderService",
            "println",
            "System.out",
            "UNRESOLVED_TARGET_TYPE",
            List.of(),
            "OrderService.java"
        );

        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(ambiguousCall, externalCall),
            List.of()
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().score()).isLessThan(100);
        assertThat(plan.confidence().negativeFactors()).anyMatch(s -> s.contains("ambiguous overloads"));
        assertThat(plan.confidence().negativeFactors()).anyMatch(s -> s.contains("external or unresolvable"));

        // High priority recommendation for ambiguous call
        assertThat(plan.recommendations()).anyMatch(r ->
            r.priority() == VerificationPriority.HIGH
                && "AMBIGUOUS_CALL".equals(r.category())
                && r.action().contains("Disambiguate")
        );

        // Low priority recommendation for external call
        assertThat(plan.recommendations()).anyMatch(r ->
            r.priority() == VerificationPriority.LOW
                && "UNRESOLVED_CALL".equals(r.category())
        );
    }

    @Test
    void parsingErrors_severelyReducesConfidenceAndGeneratesHighPriorityRecommendations() {
        FileParseError error1 = new FileParseError("InvalidA.java", "Syntax error at token 'class'");
        FileParseError error2 = new FileParseError("InvalidB.java", "Unclosed bracket");

        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(),
            List.of(),
            List.of(error1, error2),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        VerificationPlan plan = service.generatePlan(report);

        // 2 parse errors deduct 50 points
        assertThat(plan.confidence().score()).isLessThanOrEqualTo(50);
        assertThat(plan.confidence().level()).isIn(ConfidenceLevel.MEDIUM, ConfidenceLevel.LOW);
        assertThat(plan.confidence().negativeFactors()).anyMatch(s -> s.contains("failed to parse"));

        // Verify recommendations
        List<VerificationRecommendation> parseRecs = plan.recommendations().stream()
            .filter(r -> "PARSE_ERROR".equals(r.category()))
            .toList();
        assertThat(parseRecs).hasSize(2);
        assertThat(parseRecs).allMatch(r -> r.priority() == VerificationPriority.HIGH);
    }

    @Test
    void candidateTests_generatesPrioritizedTestRecommendationsBasedOnEvidence() {
        TestImpactCandidate directTest = new TestImpactCandidate(
            "PaymentGatewayTest.java",
            "PaymentGatewayTest",
            "testCharge",
            TestMatchEvidence.METHOD_INVOCATION,
            "Direct invocation",
            "PaymentGateway#charge"
        );

        TestImpactCandidate typeTest = new TestImpactCandidate(
            "OrderServiceTest.java",
            "OrderServiceTest",
            "testMock",
            TestMatchEvidence.TYPE_REFERENCE,
            "Type reference",
            "PaymentGateway"
        );

        TestImpactCandidate namingTest = new TestImpactCandidate(
            "GatewayTests.java",
            "GatewayTests",
            "dummyTest",
            TestMatchEvidence.NAMING_MATCH,
            "Naming match",
            "PaymentGateway"
        );

        ImpactReport report = new ImpactReport(
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(directTest, typeTest, namingTest)
        );

        VerificationPlan plan = service.generatePlan(report);

        List<VerificationRecommendation> testRecs = plan.recommendations().stream()
            .filter(r -> "CANDIDATE_TEST".equals(r.category()))
            .toList();
        assertThat(testRecs).hasSize(3);

        // Verify priorities
        assertThat(testRecs.get(0).priority()).isEqualTo(VerificationPriority.HIGH);
        assertThat(testRecs.get(0).target()).isEqualTo("PaymentGatewayTest#testCharge");

        assertThat(testRecs.get(1).priority()).isEqualTo(VerificationPriority.MEDIUM);
        assertThat(testRecs.get(1).target()).isEqualTo("OrderServiceTest#testMock");

        assertThat(testRecs.get(2).priority()).isEqualTo(VerificationPriority.LOW);
        assertThat(testRecs.get(2).target()).isEqualTo("GatewayTests#dummyTest");
    }

    @Test
    void emptyAndPartialReports_handledSafelyWithoutErrors() {
        // 1. Completely null report
        VerificationPlan nullPlan = service.generatePlan((ImpactReport) null);
        assertThat(nullPlan.confidence().level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(nullPlan.confidence().score()).isEqualTo(100);
        assertThat(nullPlan.recommendations()).isEmpty();

        // 2. Empty report
        ImpactReport emptyReport = new ImpactReport(List.of(), List.of(), List.of(), List.of());
        VerificationPlan emptyPlan = service.generatePlan(emptyReport);
        assertThat(emptyPlan.confidence().score()).isEqualTo(100);
        assertThat(emptyPlan.recommendations()).isEmpty();

        // 3. Partial report: affected method with no candidate tests
        ImpactReport partialReport = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of(),
            List.of(new AffectedMethod("com.changescope.order.OrderController#submit(): void", "com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of()
        );

        VerificationPlan partialPlan = service.generatePlan(partialReport);
        assertThat(partialPlan.recommendations()).isNotEmpty();
        assertThat(partialPlan.recommendations()).anyMatch(r ->
            "AFFECTED_METHOD".equals(r.category())
                && r.priority() == VerificationPriority.HIGH
                && r.target().equals("com.changescope.order.OrderController#submit(): void")
        );
        assertThat(partialPlan.recommendations()).anyMatch(r ->
            "AFFECTED_TYPE".equals(r.category())
                && r.priority() == VerificationPriority.MEDIUM
                && r.target().equals("com.changescope.order.OrderController")
        );
    }

    @Test
    void cleanPrFilesOnlyCoverage_doesNotReduceConfidence() {
        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of("com.changescope.order.OrderService#checkout(): void"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new TestImpactCandidate(
                "OrderServiceTest.java",
                "com.changescope.order.OrderServiceTest",
                "testCheckout",
                TestMatchEvidence.METHOD_INVOCATION,
                "Direct invocation",
                "com.changescope.order.OrderService#checkout(): void"
            )),
            null,
            AnalysisCoverage.prFilesOnly("base-sha-123")
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().level()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(plan.confidence().score()).isEqualTo(100);
        assertThat(plan.confidence().negativeFactors()).isEmpty();
        assertThat(plan.confidence().positiveFactors())
            .anyMatch(s -> s.contains("Analysis completed within pull request file changeset scope"));
    }

    @Test
    void boundedPartialCoverage_reducesConfidenceAndAddsNegativeFactor() {
        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of("com.changescope.order.OrderService#checkout(): void"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new TestImpactCandidate(
                "OrderServiceTest.java",
                "com.changescope.order.OrderServiceTest",
                "testCheckout",
                TestMatchEvidence.METHOD_INVOCATION,
                "Direct invocation",
                "com.changescope.order.OrderService#checkout(): void"
            )),
            null,
            AnalysisCoverage.boundedPartial(
                "base-sha-123",
                5,
                5,
                false,
                List.of("Bounded candidate discovery was performed")
            )
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().score()).isEqualTo(90);
        assertThat(plan.confidence().negativeFactors())
            .anyMatch(s -> s.contains("bounded to targeted candidate discovery") && s.contains("unchanged production dependents may be missing"));
    }

    @Test
    void truncatedOrCappedCoverage_reducesConfidenceWithCappedNegativeFactor() {
        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of("com.changescope.order.OrderService#checkout(): void"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new TestImpactCandidate(
                "OrderServiceTest.java",
                "com.changescope.order.OrderServiceTest",
                "testCheckout",
                TestMatchEvidence.METHOD_INVOCATION,
                "Direct invocation",
                "com.changescope.order.OrderService#checkout(): void"
            )),
            null,
            AnalysisCoverage.boundedPartial(
                "base-sha-123",
                25,
                20,
                true,
                List.of("Targeted production file discovery reached limit of 20 files")
            )
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().score()).isEqualTo(85);
        assertThat(plan.confidence().negativeFactors())
            .anyMatch(s -> s.contains("truncated or capped at discovery limits") && s.contains("unchanged production dependents may be missing"));
    }

    @Test
    void failedDiscoveryCoverage_reducesConfidenceAndAddsFailureFactor() {
        ImpactReport report = new ImpactReport(
            List.of("com.changescope.order.OrderService"),
            List.of(new AffectedType("com.changescope.order.OrderController", 1)),
            List.of(),
            List.of(),
            List.of("com.changescope.order.OrderService#checkout(): void"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new TestImpactCandidate(
                "OrderServiceTest.java",
                "com.changescope.order.OrderServiceTest",
                "testCheckout",
                TestMatchEvidence.METHOD_INVOCATION,
                "Direct invocation",
                "com.changescope.order.OrderService#checkout(): void"
            )),
            null,
            AnalysisCoverage.prFilesOnlyIncomplete(
                "base-sha-123",
                "Failed to retrieve base repository tree for unchanged discovery: timeout"
            )
        );

        VerificationPlan plan = service.generatePlan(report);

        assertThat(plan.confidence().score()).isEqualTo(85);
        assertThat(plan.confidence().negativeFactors())
            .anyMatch(s -> s.contains("Repository tree discovery failed") && s.contains("restricted to PR files only"));
    }
}
