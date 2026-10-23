package com.changescope.service;

import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDependencyAnalysisServiceTest {

    private JavaDependencyAnalysisService dependencyService;

    @BeforeEach
    void setUp() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        dependencyService = new JavaDependencyAnalysisService(parserService);
    }

    @Test
    void analyze_fieldDependency_extractsFieldRelationship() {
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
            new JavaSourceFileInput("src/main/java/com/changescope/order/OrderService.java", serviceSource),
            new JavaSourceFileInput("src/main/java/com/changescope/payment/PaymentGateway.java", gatewaySource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).containsExactly(
            new TypeDependency(
                "com.changescope.order.OrderService",
                "com.changescope.payment.PaymentGateway",
                RelationshipKind.FIELD,
                "src/main/java/com/changescope/order/OrderService.java"
            )
        );
    }

    @Test
    void analyze_methodSignatures_extractsReturnAndParameterRelationships() {
        String serviceSource = """
            package com.changescope.order;

            import com.changescope.model.Order;
            import com.changescope.model.OrderStatus;

            public class OrderProcessor {
                public Order processOrder(Order input, OrderStatus targetStatus) {
                    return input;
                }
            }
            """;

        String orderSource = """
            package com.changescope.model;

            public class Order {}
            """;

        String statusSource = """
            package com.changescope.model;

            public class OrderStatus {}
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderProcessor.java", serviceSource),
            new JavaSourceFileInput("Order.java", orderSource),
            new JavaSourceFileInput("OrderStatus.java", statusSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).contains(
            new TypeDependency("com.changescope.order.OrderProcessor", "com.changescope.model.Order", RelationshipKind.METHOD_RETURN, "OrderProcessor.java"),
            new TypeDependency("com.changescope.order.OrderProcessor", "com.changescope.model.Order", RelationshipKind.METHOD_PARAMETER, "OrderProcessor.java"),
            new TypeDependency("com.changescope.order.OrderProcessor", "com.changescope.model.OrderStatus", RelationshipKind.METHOD_PARAMETER, "OrderProcessor.java")
        );
    }

    @Test
    void analyze_inheritance_extractsExtendsRelationship() {
        String baseSource = """
            package com.changescope.common;

            public class BaseEntity {}
            """;

        String subSource = """
            package com.changescope.order;

            import com.changescope.common.BaseEntity;

            public class OrderEntity extends BaseEntity {}
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("BaseEntity.java", baseSource),
            new JavaSourceFileInput("OrderEntity.java", subSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).containsExactly(
            new TypeDependency("com.changescope.order.OrderEntity", "com.changescope.common.BaseEntity", RelationshipKind.EXTENDS, "OrderEntity.java")
        );
    }

    @Test
    void analyze_implementation_extractsImplementsRelationship() {
        String interfaceSource = """
            package com.changescope.service;

            public interface Processable {}
            """;

        String implSource = """
            package com.changescope.service;

            public class AsyncProcessor implements Processable {}
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("Processable.java", interfaceSource),
            new JavaSourceFileInput("AsyncProcessor.java", implSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).containsExactly(
            new TypeDependency("com.changescope.service.AsyncProcessor", "com.changescope.service.Processable", RelationshipKind.IMPLEMENTS, "AsyncProcessor.java")
        );
    }

    @Test
    void analyze_constructorParameters_extractsConstructorDependency() {
        String repoSource = """
            package com.changescope.order;

            public class OrderRepository {}
            """;

        String serviceSource = """
            package com.changescope.order;

            public class OrderService {
                public OrderService(OrderRepository repository) {}
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderRepository.java", repoSource),
            new JavaSourceFileInput("OrderService.java", serviceSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).contains(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.CONSTRUCTOR_PARAMETER, "OrderService.java")
        );
    }

    @Test
    void analyze_unrelatedTypes_producesNoDependencies() {
        String mathSource = """
            package com.changescope.util;

            public class MathUtils {
                public static int add(int a, int b) {
                    return a + b;
                }
            }
            """;

        String stringSource = """
            package com.changescope.util;

            public class StringFormatter {
                public static String clean(String input) {
                    return input.trim();
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("MathUtils.java", mathSource),
            new JavaSourceFileInput("StringFormatter.java", stringSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void analyze_invalidFiles_recordsErrorsWhileAnalyzingValidFiles() {
        String validSource = """
            package com.changescope.order;

            public class OrderService {
                private OrderRepository repository;
            }
            """;

        String validRepo = """
            package com.changescope.order;

            public class OrderRepository {}
            """;

        String brokenSource = """
            package com.changescope.broken;

            public class MalformedClass {
                public void broken(
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("OrderService.java", validSource),
            new JavaSourceFileInput("OrderRepository.java", validRepo),
            new JavaSourceFileInput("MalformedClass.java", brokenSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).hasSize(1);
        FileParseError error = result.errors().get(0);
        assertThat(error.filePath()).isEqualTo("MalformedClass.java");
        assertThat(error.errorMessage()).containsIgnoringCase("syntax");

        assertThat(result.dependencies()).containsExactly(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java")
        );
    }

    @Test
    void analyze_wildcardImport_resolvesDependencies() {
        String modelSource = """
            package com.changescope.model;

            public class Customer {}
            """;

        String serviceSource = """
            package com.changescope.service;

            import com.changescope.model.*;

            public class CustomerService {
                public Customer getCustomer() {
                    return null;
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("Customer.java", modelSource),
            new JavaSourceFileInput("CustomerService.java", serviceSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        assertThat(result.dependencies()).containsExactly(
            new TypeDependency("com.changescope.service.CustomerService", "com.changescope.model.Customer", RelationshipKind.METHOD_RETURN, "CustomerService.java")
        );
    }

    @Test
    void analyze_ambiguousWildcards_doesNotInventRelationship() {
        String sourceA = """
            package com.changescope.pkgA;

            public class SharedItem {}
            """;

        String sourceB = """
            package com.changescope.pkgB;

            public class SharedItem {}
            """;

        String consumerSource = """
            package com.changescope.consumer;

            import com.changescope.pkgA.*;
            import com.changescope.pkgB.*;

            public class Consumer {
                private SharedItem item;
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("SharedItemA.java", sourceA),
            new JavaSourceFileInput("SharedItemB.java", sourceB),
            new JavaSourceFileInput("Consumer.java", consumerSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();
        // Because SharedItem is present in both pkgA and pkgB wildcard imports, resolution is ambiguous and must not invent a relationship
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void analyze_referencesToRecordAndEnum_resolvesDependenciesWithoutAmbiguity() {
        String eventInterfaceSource = """
            package com.changescope.event;

            public interface DomainEvent {}
            """;

        String enumSource = """
            package com.changescope.model;

            public enum PaymentStatus {
                PENDING, AUTHORIZED, FAILED
            }
            """;

        String recordSource = """
            package com.changescope.model;

            import com.changescope.event.DomainEvent;

            public record PaymentTransaction(String transactionId, PaymentStatus status) implements DomainEvent {}
            """;

        String consumerSource = """
            package com.changescope.service;

            import com.changescope.model.PaymentStatus;
            import com.changescope.model.PaymentTransaction;

            public class PaymentService {
                private PaymentTransaction lastTransaction;

                public PaymentStatus verifyPayment(PaymentTransaction tx) {
                    return tx.status();
                }
            }
            """;

        List<JavaSourceFileInput> files = List.of(
            new JavaSourceFileInput("src/main/java/com/changescope/event/DomainEvent.java", eventInterfaceSource),
            new JavaSourceFileInput("src/main/java/com/changescope/model/PaymentStatus.java", enumSource),
            new JavaSourceFileInput("src/main/java/com/changescope/model/PaymentTransaction.java", recordSource),
            new JavaSourceFileInput("src/main/java/com/changescope/service/PaymentService.java", consumerSource)
        );

        DependencyAnalysisResult result = dependencyService.analyze(files);

        assertThat(result.errors()).isEmpty();

        // 1. Record implements interface
        assertThat(result.dependencies()).contains(
            new TypeDependency(
                "com.changescope.model.PaymentTransaction",
                "com.changescope.event.DomainEvent",
                RelationshipKind.IMPLEMENTS,
                "src/main/java/com/changescope/model/PaymentTransaction.java"
            )
        );

        // 2. Record component references enum (FIELD relationship)
        assertThat(result.dependencies()).contains(
            new TypeDependency(
                "com.changescope.model.PaymentTransaction",
                "com.changescope.model.PaymentStatus",
                RelationshipKind.FIELD,
                "src/main/java/com/changescope/model/PaymentTransaction.java"
            )
        );

        // 3. Class references record via field
        assertThat(result.dependencies()).contains(
            new TypeDependency(
                "com.changescope.service.PaymentService",
                "com.changescope.model.PaymentTransaction",
                RelationshipKind.FIELD,
                "src/main/java/com/changescope/service/PaymentService.java"
            )
        );

        // 4. Class references enum via method return
        assertThat(result.dependencies()).contains(
            new TypeDependency(
                "com.changescope.service.PaymentService",
                "com.changescope.model.PaymentStatus",
                RelationshipKind.METHOD_RETURN,
                "src/main/java/com/changescope/service/PaymentService.java"
            )
        );

        // 5. Class references record via method parameter
        assertThat(result.dependencies()).contains(
            new TypeDependency(
                "com.changescope.service.PaymentService",
                "com.changescope.model.PaymentTransaction",
                RelationshipKind.METHOD_PARAMETER,
                "src/main/java/com/changescope/service/PaymentService.java"
            )
        );
    }
}
