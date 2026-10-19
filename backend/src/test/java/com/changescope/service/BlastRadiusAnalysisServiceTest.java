package com.changescope.service;

import com.changescope.dto.AffectedType;
import com.changescope.dto.BlastRadiusResult;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlastRadiusAnalysisServiceTest {

    private BlastRadiusAnalysisService blastRadiusService;

    @BeforeEach
    void setUp() {
        blastRadiusService = new BlastRadiusAnalysisService();
    }

    @Test
    void analyze_directImpact_identifiesImmediateCallersAtDistanceOne() {
        List<TypeDependency> dependencies = List.of(
            new TypeDependency(
                "com.changescope.order.OrderService",
                "com.changescope.order.OrderRepository",
                RelationshipKind.FIELD,
                "OrderService.java"
            )
        );

        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.order.OrderRepository"));

        assertThat(result.changedTypes()).containsExactly("com.changescope.order.OrderRepository");
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1)
        );
    }

    @Test
    void analyze_transitiveImpact_identifiesMultiHopCallersWithCorrectDistances() {
        // OrderController -> OrderService -> OrderRepository
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java"),
            new TypeDependency("com.changescope.order.OrderController", "com.changescope.order.OrderService", RelationshipKind.CONSTRUCTOR_PARAMETER, "OrderController.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.order.OrderRepository"));

        assertThat(result.changedTypes()).containsExactly("com.changescope.order.OrderRepository");
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1),
            new AffectedType("com.changescope.order.OrderController", 2)
        );
    }

    @Test
    void analyze_directionality_changingCallerDoesNotAffectDependency() {
        // OrderService depends on OrderRepository
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java"),
            new TypeDependency("com.changescope.order.OrderController", "com.changescope.order.OrderService", RelationshipKind.FIELD, "OrderController.java")
        );

        // Changing OrderService should affect OrderController, but NEVER OrderRepository
        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.order.OrderService"));

        assertThat(result.changedTypes()).containsExactly("com.changescope.order.OrderService");
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderController", 1)
        );
        assertThat(result.affectedTypeNames()).doesNotContain("com.changescope.order.OrderRepository");
    }

    @Test
    void analyze_shortestDistance_whenDirectAndTransitivePathsExist() {
        // FastCheckout -> PaymentGateway (distance 1)
        // FastCheckout -> CheckoutService -> PaymentGateway (distance 2)
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.checkout.CheckoutService", "com.changescope.payment.PaymentGateway", RelationshipKind.FIELD, "CheckoutService.java"),
            new TypeDependency("com.changescope.checkout.FastCheckout", "com.changescope.checkout.CheckoutService", RelationshipKind.EXTENDS, "FastCheckout.java"),
            new TypeDependency("com.changescope.checkout.FastCheckout", "com.changescope.payment.PaymentGateway", RelationshipKind.METHOD_PARAMETER, "FastCheckout.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.payment.PaymentGateway"));

        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.checkout.CheckoutService", 1),
            new AffectedType("com.changescope.checkout.FastCheckout", 1)
        );
    }

    @Test
    void analyze_cycles_terminatesWithoutDuplicates() {
        // Cycle: ServiceA -> ServiceB -> ServiceA
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.cycle.ServiceA", "com.changescope.cycle.ServiceB", RelationshipKind.FIELD, "ServiceA.java"),
            new TypeDependency("com.changescope.cycle.ServiceB", "com.changescope.cycle.ServiceA", RelationshipKind.FIELD, "ServiceB.java"),
            new TypeDependency("com.changescope.cycle.ServiceC", "com.changescope.cycle.ServiceB", RelationshipKind.FIELD, "ServiceC.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.cycle.ServiceA"));

        // ServiceB depends on ServiceA (dist 1)
        // ServiceC depends on ServiceB (dist 2)
        // ServiceA is in changedTypes, so it is not in affectedTypes
        assertThat(result.changedTypes()).containsExactly("com.changescope.cycle.ServiceA");
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.cycle.ServiceB", 1),
            new AffectedType("com.changescope.cycle.ServiceC", 2)
        );
    }

    @Test
    void analyze_multipleChangedTypes_aggregatesImpactWithMinimumDistances() {
        // OrderService -> OrderRepository
        // PaymentService -> PaymentGateway
        // CheckoutFacade -> OrderService and PaymentService
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java"),
            new TypeDependency("com.changescope.payment.PaymentService", "com.changescope.payment.PaymentGateway", RelationshipKind.FIELD, "PaymentService.java"),
            new TypeDependency("com.changescope.checkout.CheckoutFacade", "com.changescope.order.OrderService", RelationshipKind.FIELD, "CheckoutFacade.java"),
            new TypeDependency("com.changescope.checkout.CheckoutFacade", "com.changescope.payment.PaymentService", RelationshipKind.FIELD, "CheckoutFacade.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(
            dependencies,
            List.of("com.changescope.order.OrderRepository", "com.changescope.payment.PaymentGateway")
        );

        assertThat(result.changedTypes()).containsExactly(
            "com.changescope.order.OrderRepository",
            "com.changescope.payment.PaymentGateway"
        );
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderService", 1),
            new AffectedType("com.changescope.payment.PaymentService", 1),
            new AffectedType("com.changescope.checkout.CheckoutFacade", 2)
        );
    }

    @Test
    void analyze_multipleChangedTypes_whenOneChangedDependsOnAnother() {
        // OrderController -> OrderService -> OrderRepository
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java"),
            new TypeDependency("com.changescope.order.OrderController", "com.changescope.order.OrderService", RelationshipKind.FIELD, "OrderController.java")
        );

        // Both OrderRepository and OrderService are in changedTypes
        BlastRadiusResult result = blastRadiusService.analyze(
            dependencies,
            List.of("com.changescope.order.OrderRepository", "com.changescope.order.OrderService")
        );

        assertThat(result.changedTypes()).containsExactly(
            "com.changescope.order.OrderRepository",
            "com.changescope.order.OrderService"
        );
        // OrderService is changed, so it is NOT in affectedTypes.
        // OrderController is distance 1 from changed OrderService.
        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.order.OrderController", 1)
        );
    }

    @Test
    void analyze_emptyAndNullInputs_handledGracefully() {
        // Null changed types
        BlastRadiusResult result1 = blastRadiusService.analyze(List.of(), (List<String>) null);
        assertThat(result1.changedTypes()).isEmpty();
        assertThat(result1.affectedTypes()).isEmpty();

        // Empty changed types
        BlastRadiusResult result2 = blastRadiusService.analyze(List.of(), List.of());
        assertThat(result2.changedTypes()).isEmpty();
        assertThat(result2.affectedTypes()).isEmpty();

        // Null dependencies
        BlastRadiusResult result3 = blastRadiusService.analyze((List<TypeDependency>) null, List.of("com.changescope.order.OrderService"));
        assertThat(result3.changedTypes()).containsExactly("com.changescope.order.OrderService");
        assertThat(result3.affectedTypes()).isEmpty();

        // Empty dependencies
        BlastRadiusResult result4 = blastRadiusService.analyze(List.of(), List.of("com.changescope.order.OrderService"));
        assertThat(result4.changedTypes()).containsExactly("com.changescope.order.OrderService");
        assertThat(result4.affectedTypes()).isEmpty();
    }

    @Test
    void analyze_unknownChangedTypes_handledGracefully() {
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(
            dependencies,
            List.of("com.changescope.unknown.GhostType")
        );

        assertThat(result.changedTypes()).containsExactly("com.changescope.unknown.GhostType");
        assertThat(result.affectedTypes()).isEmpty();
    }

    @Test
    void analyze_deterministicOrdering_sortedByDistanceThenAlphabetical() {
        // ZService -> Target (dist 1)
        // AService -> Target (dist 1)
        // MService -> Target (dist 1)
        // TransitiveB -> ZService (dist 2)
        // TransitiveA -> ZService (dist 2)
        List<TypeDependency> dependencies = List.of(
            new TypeDependency("com.changescope.ZService", "com.changescope.Target", RelationshipKind.FIELD, "ZService.java"),
            new TypeDependency("com.changescope.AService", "com.changescope.Target", RelationshipKind.FIELD, "AService.java"),
            new TypeDependency("com.changescope.MService", "com.changescope.Target", RelationshipKind.FIELD, "MService.java"),
            new TypeDependency("com.changescope.TransitiveB", "com.changescope.ZService", RelationshipKind.FIELD, "TransitiveB.java"),
            new TypeDependency("com.changescope.TransitiveA", "com.changescope.ZService", RelationshipKind.FIELD, "TransitiveA.java")
        );

        BlastRadiusResult result = blastRadiusService.analyze(dependencies, List.of("com.changescope.Target"));

        assertThat(result.affectedTypes()).containsExactly(
            new AffectedType("com.changescope.AService", 1),
            new AffectedType("com.changescope.MService", 1),
            new AffectedType("com.changescope.ZService", 1),
            new AffectedType("com.changescope.TransitiveA", 2),
            new AffectedType("com.changescope.TransitiveB", 2)
        );
    }
}
