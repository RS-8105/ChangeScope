package com.changescope.service;

import com.changescope.dto.BaseGraphSnapshot;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GraphOverlayResult;
import com.changescope.dto.IndexedTypeDto;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeDependency;
import com.changescope.dto.TypeKind;
import com.changescope.dto.BlastRadiusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphOverlayServiceTest {

    private GraphOverlayService overlayService;
    private BlastRadiusAnalysisService blastRadiusService;

    @BeforeEach
    void setUp() {
        overlayService = new GraphOverlayService();
        blastRadiusService = new BlastRadiusAnalysisService();
    }

    @Test
    @DisplayName("1. No Java changes preserve the baseline graph")
    void overlay_whenNoJavaChanges_preservesBaselineGraph() {
        IndexedTypeDto type1 = new IndexedTypeDto("com.changescope.OrderService", "src/OrderService.java");
        IndexedTypeDto type2 = new IndexedTypeDto("com.changescope.OrderController", "src/OrderController.java");
        TypeDependency dep1 = new TypeDependency(
            "com.changescope.OrderController",
            "com.changescope.OrderService",
            RelationshipKind.FIELD,
            "src/OrderController.java"
        );

        BaseGraphSnapshot base = new BaseGraphSnapshot(List.of(type1, type2), List.of(dep1));

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("README.md", FileChangeStatus.MODIFIED, 1, 1, "patch")
        );

        GraphOverlayResult result = overlayService.overlay(base, fileChanges, List.of(), List.of());

        assertThat(result.dependencies()).containsExactly(dep1);
        assertThat(result.activeTypes()).containsExactlyInAnyOrder(
            "com.changescope.OrderService",
            "com.changescope.OrderController"
        );
        assertThat(result.parseErrors()).isEmpty();
    }

    @Test
    @DisplayName("2. Modified files replace outgoing edges while unchanged incoming edges remain")
    void overlay_whenFileModified_replacesOutgoingEdgesWhilePreservingUnchangedIncomingEdges() {
        IndexedTypeDto controller = new IndexedTypeDto("com.changescope.OrderController", "src/OrderController.java");
        IndexedTypeDto service = new IndexedTypeDto("com.changescope.OrderService", "src/OrderService.java");
        IndexedTypeDto repo = new IndexedTypeDto("com.changescope.OrderRepository", "src/OrderRepository.java");
        IndexedTypeDto gateway = new IndexedTypeDto("com.changescope.PaymentGateway", "src/PaymentGateway.java");

        TypeDependency incoming = new TypeDependency(
            "com.changescope.OrderController",
            "com.changescope.OrderService",
            RelationshipKind.FIELD,
            "src/OrderController.java"
        );
        TypeDependency oldOutgoing = new TypeDependency(
            "com.changescope.OrderService",
            "com.changescope.OrderRepository",
            RelationshipKind.FIELD,
            "src/OrderService.java"
        );

        BaseGraphSnapshot base = new BaseGraphSnapshot(
            List.of(controller, service, repo, gateway),
            List.of(incoming, oldOutgoing)
        );

        String modifiedServiceSource = """
            package com.changescope;
            public class OrderService {
                private PaymentGateway paymentGateway;
            }
            """;

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("src/OrderService.java", FileChangeStatus.MODIFIED, 5, 2, "patch")
        );
        List<JavaSourceFileInput> headFiles = List.of(
            new JavaSourceFileInput("src/OrderService.java", modifiedServiceSource)
        );

        GraphOverlayResult result = overlayService.overlay(
            base,
            fileChanges,
            headFiles,
            List.of("com.changescope.OrderService")
        );

        // Old outgoing edge to repo is purged; new edge to gateway is added; incoming edge from controller is preserved
        assertThat(result.dependencies()).contains(incoming);
        assertThat(result.dependencies()).noneMatch(d -> d.targetType().equals("com.changescope.OrderRepository"));

        TypeDependency newOutgoing = result.dependencies().stream()
            .filter(d -> d.sourceType().equals("com.changescope.OrderService"))
            .findFirst()
            .orElseThrow();
        assertThat(newOutgoing.targetType()).isEqualTo("com.changescope.PaymentGateway");
        assertThat(newOutgoing.sourceFilePath()).isEqualTo("src/OrderService.java");

        // Verify compatibility with BlastRadiusAnalysisService
        BlastRadiusResult blastResult = blastRadiusService.analyze(
            result.dependencies(),
            List.of("com.changescope.OrderService")
        );
        assertThat(blastResult.affectedTypes()).extracting("typeName").contains("com.changescope.OrderController");
    }

    @Test
    @DisplayName("3. Added files resolve dependencies against unchanged types")
    void overlay_whenFileAdded_resolvesDependenciesAgainstUnchangedTypes() {
        IndexedTypeDto baseService = new IndexedTypeDto("com.changescope.OrderService", "src/OrderService.java");
        BaseGraphSnapshot base = new BaseGraphSnapshot(List.of(baseService), List.of());

        String newNotifierSource = """
            package com.changescope;
            public class OrderNotifier {
                private OrderService orderService;
            }
            """;

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("src/OrderNotifier.java", FileChangeStatus.ADDED, 5, 0, "patch")
        );
        List<JavaSourceFileInput> headFiles = List.of(
            new JavaSourceFileInput("src/OrderNotifier.java", newNotifierSource)
        );

        GraphOverlayResult result = overlayService.overlay(base, fileChanges, headFiles, List.of());

        assertThat(result.activeTypes()).contains(
            "com.changescope.OrderService",
            "com.changescope.OrderNotifier"
        );

        assertThat(result.dependencies()).hasSize(1);
        TypeDependency dep = result.dependencies().get(0);
        assertThat(dep.sourceType()).isEqualTo("com.changescope.OrderNotifier");
        assertThat(dep.targetType()).isEqualTo("com.changescope.OrderService");
        assertThat(dep.sourceFilePath()).isEqualTo("src/OrderNotifier.java");
    }

    @Test
    @DisplayName("4. Deleted files preserve incoming edges for blast-radius traversal")
    void overlay_whenFileDeleted_preservesIncomingEdgesForBlastRadius() {
        IndexedTypeDto caller = new IndexedTypeDto("com.changescope.OrderController", "src/OrderController.java");
        IndexedTypeDto legacy = new IndexedTypeDto("com.changescope.LegacyService", "src/LegacyService.java");
        IndexedTypeDto helper = new IndexedTypeDto("com.changescope.LegacyHelper", "src/LegacyService.java");

        TypeDependency incoming = new TypeDependency(
            "com.changescope.OrderController",
            "com.changescope.LegacyService",
            RelationshipKind.FIELD,
            "src/OrderController.java"
        );
        TypeDependency outgoing = new TypeDependency(
            "com.changescope.LegacyService",
            "com.changescope.LegacyHelper",
            RelationshipKind.FIELD,
            "src/LegacyService.java"
        );

        BaseGraphSnapshot base = new BaseGraphSnapshot(
            List.of(caller, legacy, helper),
            List.of(incoming, outgoing)
        );

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("src/LegacyService.java", FileChangeStatus.DELETED, 0, 50, "")
        );

        GraphOverlayResult result = overlayService.overlay(
            base,
            fileChanges,
            List.of(),
            List.of("com.changescope.LegacyService")
        );

        // Outgoing edge from deleted file is removed; incoming edge to deleted type is kept
        assertThat(result.dependencies()).containsExactly(incoming);
        assertThat(result.activeTypes()).doesNotContain("com.changescope.LegacyService", "com.changescope.LegacyHelper");
        assertThat(result.activeTypes()).containsExactly("com.changescope.OrderController");

        // Blast radius from deleted LegacyService still finds OrderController
        BlastRadiusResult blastResult = blastRadiusService.analyze(
            result.dependencies(),
            List.of("com.changescope.LegacyService")
        );
        assertThat(blastResult.affectedTypes()).extracting("typeName").containsExactly("com.changescope.OrderController");
    }

    @Test
    @DisplayName("5. Renamed files invalidate the previous path and index new path")
    void overlay_whenFileRenamed_invalidatesPreviousPath() {
        IndexedTypeDto oldType = new IndexedTypeDto("com.changescope.OldService", "src/OldService.java");
        IndexedTypeDto helper = new IndexedTypeDto("com.changescope.Helper", "src/Helper.java");
        TypeDependency oldDep = new TypeDependency(
            "com.changescope.OldService",
            "com.changescope.Helper",
            RelationshipKind.FIELD,
            "src/OldService.java"
        );

        BaseGraphSnapshot base = new BaseGraphSnapshot(List.of(oldType, helper), List.of(oldDep));

        String newSource = """
            package com.changescope;
            public class NewService {
                private Helper helper;
            }
            """;

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange(
                "src/NewService.java",
                FileChangeStatus.RENAMED,
                5,
                5,
                "patch",
                "src/OldService.java"
            )
        );
        List<JavaSourceFileInput> headFiles = List.of(
            new JavaSourceFileInput("src/NewService.java", newSource)
        );

        GraphOverlayResult result = overlayService.overlay(base, fileChanges, headFiles, List.of());

        assertThat(result.activeTypes()).doesNotContain("com.changescope.OldService");
        assertThat(result.activeTypes()).contains("com.changescope.NewService", "com.changescope.Helper");

        assertThat(result.dependencies()).hasSize(1);
        TypeDependency newDep = result.dependencies().get(0);
        assertThat(newDep.sourceType()).isEqualTo("com.changescope.NewService");
        assertThat(newDep.targetType()).isEqualTo("com.changescope.Helper");
        assertThat(newDep.sourceFilePath()).isEqualTo("src/NewService.java");
    }

    @Test
    @DisplayName("6. Multiple types in one file are all replaced")
    void overlay_whenMultipleTypesInOneFile_allReplaced() {
        IndexedTypeDto mainType = new IndexedTypeDto("com.changescope.MainService", "src/Services.java");
        IndexedTypeDto helperType = new IndexedTypeDto("com.changescope.InternalHelper", "src/Services.java");
        IndexedTypeDto targetA = new IndexedTypeDto("com.changescope.TargetA", "src/TargetA.java");
        IndexedTypeDto targetB = new IndexedTypeDto("com.changescope.TargetB", "src/TargetB.java");

        TypeDependency dep1 = new TypeDependency("com.changescope.MainService", "com.changescope.TargetA", RelationshipKind.FIELD, "src/Services.java");
        TypeDependency dep2 = new TypeDependency("com.changescope.InternalHelper", "com.changescope.TargetB", RelationshipKind.FIELD, "src/Services.java");

        BaseGraphSnapshot base = new BaseGraphSnapshot(
            List.of(mainType, helperType, targetA, targetB),
            List.of(dep1, dep2)
        );

        String replacedSource = """
            package com.changescope;
            public class MainService {
                private TargetB targetB;
            }
            class ReplacementHelper {
                private TargetA targetA;
            }
            """;

        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("src/Services.java", FileChangeStatus.MODIFIED, 10, 10, "patch")
        );
        List<JavaSourceFileInput> headFiles = List.of(
            new JavaSourceFileInput("src/Services.java", replacedSource)
        );

        GraphOverlayResult result = overlayService.overlay(base, fileChanges, headFiles, List.of());

        assertThat(result.activeTypes()).doesNotContain("com.changescope.InternalHelper");
        assertThat(result.activeTypes()).contains(
            "com.changescope.MainService",
            "com.changescope.ReplacementHelper",
            "com.changescope.TargetA",
            "com.changescope.TargetB"
        );

        assertThat(result.dependencies()).hasSize(2);
        assertThat(result.dependencies()).anyMatch(d ->
            d.sourceType().equals("com.changescope.MainService") && d.targetType().equals("com.changescope.TargetB"));
        assertThat(result.dependencies()).anyMatch(d ->
            d.sourceType().equals("com.changescope.ReplacementHelper") && d.targetType().equals("com.changescope.TargetA"));
    }

    @Test
    @DisplayName("7. Base snapshot immutability")
    void overlay_ensuresBaseSnapshotImmutability() {
        IndexedTypeDto type = new IndexedTypeDto("com.changescope.Service", "src/Service.java");
        TypeDependency dep = new TypeDependency("com.changescope.Service", "com.changescope.Other", RelationshipKind.FIELD, "src/Service.java");

        List<IndexedTypeDto> mutableTypes = new ArrayList<>(List.of(type));
        List<TypeDependency> mutableDeps = new ArrayList<>(List.of(dep));

        BaseGraphSnapshot base = new BaseGraphSnapshot(mutableTypes, mutableDeps);

        // Mutating external lists does not affect base snapshot
        mutableTypes.clear();
        mutableDeps.clear();
        assertThat(base.indexedTypes()).hasSize(1);
        assertThat(base.dependencies()).hasSize(1);

        // Attempting to modify returned lists from base throws exception
        assertThatThrownBy(() -> base.indexedTypes().add(new IndexedTypeDto("foo", "bar")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> base.dependencies().add(dep))
            .isInstanceOf(UnsupportedOperationException.class);

        // Running overlay does not mutate base snapshot collections
        overlayService.overlay(
            base,
            List.of(new PullRequestFileChange("src/Service.java", FileChangeStatus.DELETED, 0, 10, "")),
            List.of(),
            List.of()
        );

        assertThat(base.indexedTypes()).hasSize(1);
        assertThat(base.dependencies()).hasSize(1);
    }

    @Test
    @DisplayName("8. Duplicate FQNs across files")
    void overlay_whenDuplicateFqnsAcrossFiles_doesNotRemoveTypeWhileAnotherDeclarationRemains() {
        IndexedTypeDto copy1 = new IndexedTypeDto("com.changescope.SharedModel", "src/mod1/SharedModel.java");
        IndexedTypeDto copy2 = new IndexedTypeDto("com.changescope.SharedModel", "src/mod2/SharedModel.java");
        IndexedTypeDto targetA = new IndexedTypeDto("com.changescope.TargetA", "src/TargetA.java");
        IndexedTypeDto targetB = new IndexedTypeDto("com.changescope.TargetB", "src/TargetB.java");

        TypeDependency dep1 = new TypeDependency("com.changescope.SharedModel", "com.changescope.TargetA", RelationshipKind.FIELD, "src/mod1/SharedModel.java");
        TypeDependency dep2 = new TypeDependency("com.changescope.SharedModel", "com.changescope.TargetB", RelationshipKind.FIELD, "src/mod2/SharedModel.java");

        BaseGraphSnapshot base = new BaseGraphSnapshot(
            List.of(copy1, copy2, targetA, targetB),
            List.of(dep1, dep2)
        );

        // Delete mod1 only
        List<PullRequestFileChange> fileChanges = List.of(
            new PullRequestFileChange("src/mod1/SharedModel.java", FileChangeStatus.DELETED, 0, 20, "")
        );

        GraphOverlayResult result = overlayService.overlay(base, fileChanges, List.of(), List.of());

        // SharedModel remains active because mod2/SharedModel.java still declares it
        assertThat(result.activeTypes()).contains("com.changescope.SharedModel");

        // dep1 from mod1 is removed; dep2 from mod2 is retained
        assertThat(result.dependencies()).containsExactly(dep2);

        // Now if both are deleted
        List<PullRequestFileChange> bothDeleted = List.of(
            new PullRequestFileChange("src/mod1/SharedModel.java", FileChangeStatus.DELETED, 0, 20, ""),
            new PullRequestFileChange("src/mod2/SharedModel.java", FileChangeStatus.DELETED, 0, 20, "")
        );
        GraphOverlayResult bothResult = overlayService.overlay(base, bothDeleted, List.of(), List.of());
        assertThat(bothResult.activeTypes()).doesNotContain("com.changescope.SharedModel");
        assertThat(bothResult.dependencies()).isEmpty();
    }
}
