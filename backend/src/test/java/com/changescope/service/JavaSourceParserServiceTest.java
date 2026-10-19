package com.changescope.service;

import com.changescope.dto.ParsedJavaFile;
import com.changescope.dto.ParsedMethod;
import com.changescope.dto.ParsedType;
import com.changescope.dto.TypeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceParserServiceTest {

    private JavaSourceParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new JavaSourceParserService();
    }

    @Test
    void parse_validJavaClass_extractsPackageImportsAndClass() {
        String source = """
            package com.changescope.order;

            import java.time.Instant;
            import java.util.List;

            public class OrderService {
                public String getStatus() {
                    return "ACTIVE";
                }
            }
            """;

        ParsedJavaFile result = parserService.parse(source);

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.packageName()).isEqualTo("com.changescope.order");
        assertThat(result.imports()).containsExactly("java.time.Instant", "java.util.List");
        assertThat(result.types()).hasSize(1);

        ParsedType type = result.types().get(0);
        assertThat(type.name()).isEqualTo("OrderService");
        assertThat(type.kind()).isEqualTo(TypeKind.CLASS);
        assertThat(type.isClass()).isTrue();
        assertThat(type.isInterface()).isFalse();
        assertThat(type.methods()).hasSize(1);

        ParsedMethod method = type.methods().get(0);
        assertThat(method.name()).isEqualTo("getStatus");
        assertThat(method.returnType()).isEqualTo("String");
        assertThat(method.parameterTypes()).isEmpty();
        assertThat(method.parameters()).isEmpty();
    }

    @Test
    void parse_classWithMultipleMethodsAndParameters_extractsAllSignatures() {
        String source = """
            package com.changescope.inventory;

            import java.math.BigDecimal;
            import java.util.Optional;

            public class InventoryManager {

                public Optional<Integer> checkStock(Long itemId, String warehouseCode) {
                    return Optional.of(42);
                }

                public boolean reserveItem(Long itemId, int quantity, BigDecimal unitPrice) {
                    return true;
                }

                private void auditLog(String message) {
                    System.out.println(message);
                }
            }
            """;

        ParsedJavaFile result = parserService.parse(source);

        assertThat(result.success()).isTrue();
        assertThat(result.packageName()).isEqualTo("com.changescope.inventory");
        assertThat(result.types()).hasSize(1);

        ParsedType type = result.types().get(0);
        assertThat(type.name()).isEqualTo("InventoryManager");
        assertThat(type.methods()).hasSize(3);

        ParsedMethod method1 = type.methods().get(0);
        assertThat(method1.name()).isEqualTo("checkStock");
        assertThat(method1.returnType()).isEqualTo("Optional<Integer>");
        assertThat(method1.parameterTypes()).containsExactly("Long", "String");
        assertThat(method1.parameters()).hasSize(2);
        assertThat(method1.parameters().get(0).type()).isEqualTo("Long");
        assertThat(method1.parameters().get(0).name()).isEqualTo("itemId");
        assertThat(method1.parameters().get(1).type()).isEqualTo("String");
        assertThat(method1.parameters().get(1).name()).isEqualTo("warehouseCode");

        ParsedMethod method2 = type.methods().get(1);
        assertThat(method2.name()).isEqualTo("reserveItem");
        assertThat(method2.returnType()).isEqualTo("boolean");
        assertThat(method2.parameterTypes()).containsExactly("Long", "int", "BigDecimal");

        ParsedMethod method3 = type.methods().get(2);
        assertThat(method3.name()).isEqualTo("auditLog");
        assertThat(method3.returnType()).isEqualTo("void");
        assertThat(method3.parameterTypes()).containsExactly("String");
    }

    @Test
    void parse_interfaceDeclaration_extractsInterfaceAndMethods() {
        String source = """
            package com.changescope.api;

            import java.util.List;

            public interface PaymentGateway {
                String processPayment(String transactionId, double amount);
                List<String> getSupportedCurrencies();
                default boolean isHealthy() {
                    return true;
                }
            }
            """;

        ParsedJavaFile result = parserService.parse(source);

        assertThat(result.success()).isTrue();
        assertThat(result.packageName()).isEqualTo("com.changescope.api");
        assertThat(result.imports()).containsExactly("java.util.List");
        assertThat(result.types()).hasSize(1);

        ParsedType type = result.types().get(0);
        assertThat(type.name()).isEqualTo("PaymentGateway");
        assertThat(type.kind()).isEqualTo(TypeKind.INTERFACE);
        assertThat(type.isInterface()).isTrue();
        assertThat(type.isClass()).isFalse();

        assertThat(type.methods()).hasSize(3);

        ParsedMethod method1 = type.methods().get(0);
        assertThat(method1.name()).isEqualTo("processPayment");
        assertThat(method1.returnType()).isEqualTo("String");
        assertThat(method1.parameterTypes()).containsExactly("String", "double");

        ParsedMethod method2 = type.methods().get(1);
        assertThat(method2.name()).isEqualTo("getSupportedCurrencies");
        assertThat(method2.returnType()).isEqualTo("List<String>");
        assertThat(method2.parameterTypes()).isEmpty();

        ParsedMethod method3 = type.methods().get(2);
        assertThat(method3.name()).isEqualTo("isHealthy");
        assertThat(method3.returnType()).isEqualTo("boolean");
    }

    @Test
    void parse_wildcardAndMultipleImports_extractsAllImports() {
        String source = """
            package com.changescope.util;

            import java.util.*;
            import java.io.InputStream;
            import java.io.OutputStream;

            public class StreamUtils {
                public void pipe(InputStream in, OutputStream out) {}
            }
            """;

        ParsedJavaFile result = parserService.parse(source);

        assertThat(result.success()).isTrue();
        assertThat(result.packageName()).isEqualTo("com.changescope.util");
        assertThat(result.imports()).containsExactly(
            "java.util.*",
            "java.io.InputStream",
            "java.io.OutputStream"
        );
    }

    @Test
    void parse_noPackage_returnsNullPackage() {
        String source = """
            public class StandaloneClass {
                public void execute() {}
            }
            """;

        ParsedJavaFile result = parserService.parse(source);

        assertThat(result.success()).isTrue();
        assertThat(result.packageName()).isNull();
        assertThat(result.types()).hasSize(1);
        assertThat(result.types().get(0).name()).isEqualTo("StandaloneClass");
    }

    @Test
    void parse_invalidJavaSyntax_returnsGracefulParsingError() {
        String invalidSource = """
            package com.changescope.broken;

            public class BrokenClass {
                public void incompleteMethod(
            """;

        ParsedJavaFile result = parserService.parse(invalidSource);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotNull();
        assertThat(result.errorMessage()).containsIgnoringCase("syntax");
        assertThat(result.types()).isEmpty();
        assertThat(result.imports()).isEmpty();
    }

    @Test
    void parse_nullOrEmptySource_returnsGracefulError() {
        ParsedJavaFile nullResult = parserService.parse(null);
        assertThat(nullResult.success()).isFalse();
        assertThat(nullResult.errorMessage()).contains("must not be null or empty");

        ParsedJavaFile emptyResult = parserService.parse("");
        assertThat(emptyResult.success()).isFalse();
        assertThat(emptyResult.errorMessage()).contains("must not be null or empty");

        ParsedJavaFile blankResult = parserService.parse("   \n\t  ");
        assertThat(blankResult.success()).isFalse();
        assertThat(blankResult.errorMessage()).contains("must not be null or empty");
    }
}
