package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.MethodAnalysisResult;
import com.changescope.dto.MethodDependency;
import com.changescope.dto.UnresolvedMethodCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaMethodAnalysisServiceTest {

    private JavaMethodAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new JavaMethodAnalysisService();
    }

    @Test
    void directMethodCall_identifiedCorrectlyWithDistance1() {
        String gatewaySource = """
            package com.changescope.payment;

            public interface PaymentGateway {
                boolean charge(String orderId);
            }
            """;

        String serviceSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;

            public class OrderService {
                private PaymentGateway gateway;

                public void checkout(String orderId) {
                    gateway.charge(orderId);
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource),
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.payment.PaymentGateway"));

        // Changed methods
        assertThat(result.changedMethods()).containsExactly(
            "com.changescope.payment.PaymentGateway#charge(String): boolean"
        );

        // Confirmed call relationship
        assertThat(result.methodDependencies()).hasSize(1);
        MethodDependency dep = result.methodDependencies().get(0);
        assertThat(dep.callerMethod()).isEqualTo("com.changescope.order.OrderService#checkout(String): void");
        assertThat(dep.callerType()).isEqualTo("com.changescope.order.OrderService");
        assertThat(dep.targetMethod()).isEqualTo("com.changescope.payment.PaymentGateway#charge(String): boolean");
        assertThat(dep.targetType()).isEqualTo("com.changescope.payment.PaymentGateway");

        // Affected methods
        assertThat(result.affectedMethods()).containsExactly(
            new AffectedMethod(
                "com.changescope.order.OrderService#checkout(String): void",
                "com.changescope.order.OrderService",
                1
            )
        );
    }

    @Test
    void transitiveMethodCalls_chainedCallersIdentifiedWithIncreasingDistance() {
        String gatewaySource = """
            package com.changescope.payment;

            public interface PaymentGateway {
                boolean charge(String orderId);
            }
            """;

        String serviceSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;

            public class OrderService {
                private PaymentGateway gateway;

                public void checkout(String orderId) {
                    gateway.charge(orderId);
                }
            }
            """;

        String controllerSource = """
            package com.changescope.order;

            public class OrderController {
                private OrderService orderService;

                public void submitOrder(String orderId) {
                    orderService.checkout(orderId);
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource),
            new JavaSourceFileInput("OrderService.java", serviceSource),
            new JavaSourceFileInput("OrderController.java", controllerSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.payment.PaymentGateway"));

        assertThat(result.affectedMethods()).containsExactly(
            new AffectedMethod("com.changescope.order.OrderService#checkout(String): void", "com.changescope.order.OrderService", 1),
            new AffectedMethod("com.changescope.order.OrderController#submitOrder(String): void", "com.changescope.order.OrderController", 2)
        );
    }

    @Test
    void overloadedMethods_disambiguatesByArity() {
        String gatewaySource = """
            package com.changescope.payment;

            public interface PaymentGateway {
                boolean charge(String orderId);
                boolean charge(String orderId, String currency);
            }
            """;

        String serviceSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;

            public class OrderService {
                private PaymentGateway gateway;

                public void checkout(String orderId) {
                    gateway.charge(orderId);
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource),
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.payment.PaymentGateway"));

        assertThat(result.methodDependencies()).hasSize(1);
        MethodDependency dep = result.methodDependencies().get(0);
        assertThat(dep.targetMethod()).isEqualTo("com.changescope.payment.PaymentGateway#charge(String): boolean");
    }

    @Test
    void overloadedMethods_reportsAmbiguousWhenArityMatchesMultipleCandidates() {
        String gatewaySource = """
            package com.changescope.payment;

            public interface PaymentGateway {
                boolean verify(String code);
                boolean verify(int id);
            }
            """;

        String serviceSource = """
            package com.changescope.order;

            import com.changescope.payment.PaymentGateway;

            public class OrderService {
                private PaymentGateway gateway;

                public void validate(String code) {
                    gateway.verify(code);
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("PaymentGateway.java", gatewaySource),
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.payment.PaymentGateway"));

        // Since both overloads have arity 1, it is reported as ambiguous uncertainty rather than inventing a target
        assertThat(result.methodDependencies()).isEmpty();
        assertThat(result.unresolvedCalls()).hasSize(1);

        UnresolvedMethodCall call = result.unresolvedCalls().get(0);
        assertThat(call.calledMethodName()).isEqualTo("verify");
        assertThat(call.reason()).isEqualTo("AMBIGUOUS_OVERLOAD");
        assertThat(call.candidateMethods()).containsExactlyInAnyOrder(
            "com.changescope.payment.PaymentGateway#verify(String): boolean",
            "com.changescope.payment.PaymentGateway#verify(int): boolean"
        );
    }

    @Test
    void unresolvedCalls_reportsUnresolvedTargetTypeWhenExternal() {
        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                public void logOrder(String id) {
                    System.out.println(id);
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of());

        assertThat(result.methodDependencies()).isEmpty();
        assertThat(result.unresolvedCalls()).isNotEmpty();
        UnresolvedMethodCall call = result.unresolvedCalls().get(0);
        assertThat(call.calledMethodName()).isEqualTo("println");
        assertThat(call.reason()).isEqualTo("UNRESOLVED_TARGET_TYPE");
    }

    @Test
    void cycles_handlesRecursiveAndCyclicCallsSafelyWithoutInfiniteLoop() {
        String serviceASource = """
            package com.changescope.service;

            public class ServiceA {
                private ServiceB serviceB;

                public void ping() {
                    serviceB.pong();
                }
            }
            """;

        String serviceBSource = """
            package com.changescope.service;

            public class ServiceB {
                private ServiceA serviceA;

                public void pong() {
                    serviceA.ping();
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("ServiceA.java", serviceASource),
            new JavaSourceFileInput("ServiceB.java", serviceBSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.service.ServiceB"));

        assertThat(result.changedMethods()).containsExactly(
            "com.changescope.service.ServiceB#pong(): void"
        );

        // Caller ServiceA#ping is affected at distance 1, without cycle looping
        assertThat(result.affectedMethods()).containsExactly(
            new AffectedMethod("com.changescope.service.ServiceA#ping(): void", "com.changescope.service.ServiceA", 1)
        );
    }

    @Test
    void constructors_recognizesNewObjectCreationAndConstructorCall() {
        String repoSource = """
            package com.changescope.order;

            public class OrderRepository {
                public OrderRepository() {}
            }
            """;

        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                private OrderRepository repository;

                public void initRepo() {
                    this.repository = new OrderRepository();
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderRepository.java", repoSource),
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.order.OrderRepository"));

        assertThat(result.changedMethods()).contains(
            "com.changescope.order.OrderRepository#<init>()"
        );

        assertThat(result.affectedMethods()).contains(
            new AffectedMethod(
                "com.changescope.order.OrderService#initRepo(): void",
                "com.changescope.order.OrderService",
                1
            )
        );
    }

    @Test
    void invalidSource_handlesSyntaxErrorsGracefully() {
        String invalidSource = "class Invalid { this is not valid java code ???";
        String validSource = """
            package com.changescope.order;

            public class OrderService {
                public void doWork() {}
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("Invalid.java", invalidSource),
            new JavaSourceFileInput("OrderService.java", validSource)
        );

        MethodAnalysisResult result = service.analyze(files, List.of("com.changescope.order.OrderService"));

        assertThat(result.changedMethods()).containsExactly(
            "com.changescope.order.OrderService#doWork(): void"
        );
    }
}
