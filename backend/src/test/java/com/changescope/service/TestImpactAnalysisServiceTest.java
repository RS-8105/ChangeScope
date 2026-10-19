package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.AffectedType;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestImpactResult;
import com.changescope.dto.TestMatchEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestImpactAnalysisServiceTest {

    private TestImpactAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new TestImpactAnalysisService();
    }

    @Test
    void methodInvocationEvidence_identifiesDirectCallToChangedMethod() {
        String testSource = """
            package com.changescope.payment;

            import org.junit.jupiter.api.Test;

            public class PaymentGatewayTest {
                private PaymentGateway gateway;

                @Test
                void testChargeSuccess() {
                    gateway.charge("ORD-123");
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/PaymentGatewayTest.java", testSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(),
            List.of("com.changescope.payment.PaymentGateway#charge(String): boolean"),
            List.of()
        );

        assertThat(result.candidateTests()).hasSize(1);
        TestImpactCandidate candidate = result.candidateTests().get(0);
        assertThat(candidate.testClassName()).isEqualTo("com.changescope.payment.PaymentGatewayTest");
        assertThat(candidate.testMethodName()).isEqualTo("testChargeSuccess");
        assertThat(candidate.evidenceKind()).isEqualTo(TestMatchEvidence.METHOD_INVOCATION);
        assertThat(candidate.targetElement()).isEqualTo("com.changescope.payment.PaymentGateway#charge(String): boolean");
        assertThat(candidate.isDirectEvidence()).isTrue();
    }

    @Test
    void directTypeReference_identifiesTypeReferenceWithoutDirectMethodCall() {
        String testSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;
            import org.junit.jupiter.api.Test;

            public class OrderServiceTest {
                @Test
                void testWithClassReference() {
                    Class<?> clazz = PaymentGateway.class;
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/OrderServiceTest.java", testSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(),
            List.of(),
            List.of()
        );

        assertThat(result.candidateTests()).hasSize(1);
        TestImpactCandidate candidate = result.candidateTests().get(0);
        assertThat(candidate.testMethodName()).isEqualTo("testWithClassReference");
        assertThat(candidate.evidenceKind()).isEqualTo(TestMatchEvidence.TYPE_REFERENCE);
        assertThat(candidate.targetElement()).isEqualTo("com.changescope.payment.PaymentGateway");
        assertThat(candidate.isDirectEvidence()).isTrue();
    }

    @Test
    void namingOnlyMatch_identifiesMatchingClassNameWithoutDirectBodyReferences() {
        String testSource = """
            package com.changescope.order;

            import org.junit.jupiter.api.Test;

            public class OrderServiceTest {
                @Test
                void dummyTest() {
                    int x = 1 + 1;
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/OrderServiceTest.java", testSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of(),
            List.of(new AffectedType("com.changescope.order.OrderService", 1)),
            List.of(),
            List.of()
        );

        assertThat(result.candidateTests()).hasSize(1);
        TestImpactCandidate candidate = result.candidateTests().get(0);
        assertThat(candidate.testClassName()).isEqualTo("com.changescope.order.OrderServiceTest");
        assertThat(candidate.testMethodName()).isEqualTo("dummyTest");
        assertThat(candidate.evidenceKind()).isEqualTo(TestMatchEvidence.NAMING_MATCH);
        assertThat(candidate.targetElement()).isEqualTo("com.changescope.order.OrderService");
        assertThat(candidate.isDirectEvidence()).isFalse();
    }

    @Test
    void unrelatedTest_isIgnored() {
        String testSource = """
            package com.changescope.user;

            import org.junit.jupiter.api.Test;

            public class UserRepositoryTest {
                @Test
                void testUserLookup() {
                    String username = "admin";
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/UserRepositoryTest.java", testSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(new AffectedType("com.changescope.order.OrderService", 1)),
            List.of(),
            List.of()
        );

        assertThat(result.candidateTests()).isEmpty();
    }

    @Test
    void malformedTestFile_recordsParseErrorAndAnalyzesOtherFiles() {
        String malformedSource = "class MalformedTest { this is invalid syntax ??";
        String validSource = """
            package com.changescope.payment;

            import org.junit.jupiter.api.Test;

            public class PaymentGatewayTest {
                private PaymentGateway gateway;

                @Test
                void testValid() {
                    gateway.charge("123");
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/MalformedTest.java", malformedSource),
            new JavaSourceFileInput("src/test/java/PaymentGatewayTest.java", validSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(),
            List.of("com.changescope.payment.PaymentGateway#charge(String): boolean"),
            List.of()
        );

        assertThat(result.parseErrors()).hasSize(1);
        assertThat(result.parseErrors().get(0).filePath()).isEqualTo("src/test/java/MalformedTest.java");

        assertThat(result.candidateTests()).hasSize(1);
        assertThat(result.candidateTests().get(0).testClassName()).isEqualTo("com.changescope.payment.PaymentGatewayTest");
    }

    @Test
    void junitAnnotations_supportsParameterizedAndRepeatedTests() {
        String testSource = """
            package com.changescope.order;

            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.api.RepeatedTest;

            public class OrderServiceTest {
                private OrderService orderService;

                @ParameterizedTest
                void paramTest(String id) {
                    orderService.checkout(id);
                }

                @RepeatedTest(3)
                void repeatedTest() {
                    orderService.checkout("ORD-1");
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/test/java/OrderServiceTest.java", testSource)
        );

        TestImpactResult result = service.analyze(
            files,
            List.of(),
            List.of(new AffectedType("com.changescope.order.OrderService", 1)),
            List.of(),
            List.of(new AffectedMethod("com.changescope.order.OrderService#checkout(String): void", "com.changescope.order.OrderService", 1))
        );

        assertThat(result.candidateTests()).hasSize(2);
        assertThat(result.candidateTests())
            .extracting(TestImpactCandidate::testMethodName)
            .containsExactlyInAnyOrder("paramTest", "repeatedTest");
        assertThat(result.candidateTests())
            .allMatch(c -> c.evidenceKind() == TestMatchEvidence.METHOD_INVOCATION);
    }
}
