package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.AffectedType;
import com.changescope.dto.BlastRadiusResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.MethodAnalysisResult;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestImpactResult;
import com.changescope.dto.TestMatchEvidence;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class TestImpactAnalysisService {

    private final JavaParser javaParser;

    public TestImpactAnalysisService() {
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(configuration);
    }

    public TestImpactResult analyze(
        List<JavaSourceFileInput> files,
        BlastRadiusResult blastRadiusResult,
        MethodAnalysisResult methodResult
    ) {
        List<String> changedTypes = (blastRadiusResult != null) ? blastRadiusResult.changedTypes() : List.of();
        List<AffectedType> affectedTypes = (blastRadiusResult != null) ? blastRadiusResult.affectedTypes() : List.of();
        List<String> changedMethods = (methodResult != null) ? methodResult.changedMethods() : List.of();
        List<AffectedMethod> affectedMethods = (methodResult != null) ? methodResult.affectedMethods() : List.of();

        return analyze(files, changedTypes, affectedTypes, changedMethods, affectedMethods);
    }

    public TestImpactResult analyze(
        List<JavaSourceFileInput> files,
        List<String> changedTypes,
        List<AffectedType> affectedTypes,
        List<String> changedMethods,
        List<AffectedMethod> affectedMethods
    ) {
        if (files == null || files.isEmpty()) {
            return new TestImpactResult(List.of(), List.of());
        }

        // 1. Index target production types
        Set<String> targetTypeFqns = new LinkedHashSet<>();
        if (changedTypes != null) {
            changedTypes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(targetTypeFqns::add);
        }
        if (affectedTypes != null) {
            affectedTypes.stream().map(AffectedType::typeName).filter(Objects::nonNull).forEach(targetTypeFqns::add);
        }

        if (targetTypeFqns.isEmpty()) {
            return new TestImpactResult(List.of(), List.of());
        }

        Map<String, String> targetSimpleToFqn = new HashMap<>();
        for (String fqn : targetTypeFqns) {
            int lastDot = fqn.lastIndexOf('.');
            String simple = (lastDot >= 0) ? fqn.substring(lastDot + 1) : fqn;
            targetSimpleToFqn.put(simple, fqn);
        }

        // 2. Index target production methods
        Set<String> targetMethodIds = new LinkedHashSet<>();
        if (changedMethods != null) {
            changedMethods.stream().filter(Objects::nonNull).forEach(targetMethodIds::add);
        }
        if (affectedMethods != null) {
            affectedMethods.stream().map(AffectedMethod::methodIdentifier).filter(Objects::nonNull).forEach(targetMethodIds::add);
        }

        Map<String, List<String>> targetMethodsBySimpleName = new HashMap<>();
        for (String methodId : targetMethodIds) {
            // methodId format: pkg.Class#methodName(params): returnType or pkg.Class#<init>(params)
            int hashIndex = methodId.indexOf('#');
            int parenIndex = methodId.indexOf('(');
            if (hashIndex >= 0 && parenIndex > hashIndex) {
                String methodName = methodId.substring(hashIndex + 1, parenIndex);
                targetMethodsBySimpleName.computeIfAbsent(methodName, k -> new ArrayList<>()).add(methodId);
            }
        }

        // 3. Parse files, record errors, and inspect test candidates
        List<FileParseError> parseErrors = new ArrayList<>();
        List<TestImpactCandidate> candidates = new ArrayList<>();

        for (JavaSourceFileInput file : files) {
            if (file == null || file.filePath() == null || file.sourceCode() == null || file.sourceCode().isBlank()) {
                continue;
            }

            ParseResult<CompilationUnit> parseResult;
            try {
                parseResult = javaParser.parse(file.sourceCode());
            } catch (Exception ex) {
                parseErrors.add(new FileParseError(file.filePath(), "Unexpected error during parsing: " + ex.getMessage()));
                continue;
            }

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                String errorDetails = parseResult.getProblems().stream()
                    .map(com.github.javaparser.Problem::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Failed to parse test file syntax");
                parseErrors.add(new FileParseError(file.filePath(), errorDetails));
                continue;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String packageName = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse(null);
            List<String> imports = cu.getImports().stream()
                .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                .toList();

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!isTestClass(decl, file.filePath())) {
                    continue;
                }

                String testClassFqn = buildFqn(packageName, decl.getNameAsString());
                inspectTestClass(
                    decl,
                    file.filePath(),
                    testClassFqn,
                    packageName,
                    imports,
                    targetTypeFqns,
                    targetSimpleToFqn,
                    targetMethodIds,
                    targetMethodsBySimpleName,
                    candidates
                );
            }
        }

        // 4. Sort candidates deterministically
        candidates.sort(Comparator
            .comparing(TestImpactCandidate::evidenceKind)
            .thenComparing(TestImpactCandidate::testClassName)
            .thenComparing(c -> c.testMethodName() != null ? c.testMethodName() : "")
            .thenComparing(TestImpactCandidate::targetElement));

        return new TestImpactResult(candidates, parseErrors);
    }

    private void inspectTestClass(
        ClassOrInterfaceDeclaration decl,
        String filePath,
        String testClassFqn,
        String packageName,
        List<String> imports,
        Set<String> targetTypeFqns,
        Map<String, String> targetSimpleToFqn,
        Set<String> targetMethodIds,
        Map<String, List<String>> targetMethodsBySimpleName,
        List<TestImpactCandidate> candidates
    ) {
        String testClassName = decl.getNameAsString();

        // Check Class-level Naming Match
        String namingMatchedType = null;
        for (Map.Entry<String, String> entry : targetSimpleToFqn.entrySet()) {
            String prodSimple = entry.getKey();
            if (testClassName.equals(prodSimple + "Test")
                || testClassName.equals(prodSimple + "Tests")
                || testClassName.startsWith(prodSimple + "Test")) {
                namingMatchedType = entry.getValue();
                break;
            }
        }

        // Check Class-level Import Match
        String importMatchedType = null;
        for (String targetFqn : targetTypeFqns) {
            for (String imp : imports) {
                if (imp.equals(targetFqn)) {
                    importMatchedType = targetFqn;
                    break;
                } else if (imp.endsWith(".*")) {
                    String prefix = imp.substring(0, imp.length() - 2);
                    if (targetFqn.startsWith(prefix + ".")) {
                        importMatchedType = targetFqn;
                        break;
                    }
                }
            }
            if (importMatchedType != null) {
                break;
            }
        }

        // Check Class Fields: name -> simple type
        Map<String, String> testClassFields = new HashMap<>();
        String fieldMatchedType = null;
        for (FieldDeclaration field : decl.getFields()) {
            String fieldType = field.getElementType().asString();
            String resolvedType = resolveType(fieldType, packageName, imports, targetTypeFqns, targetSimpleToFqn);
            for (VariableDeclarator var : field.getVariables()) {
                testClassFields.put(var.getNameAsString(), fieldType);
            }
            if (resolvedType != null && fieldMatchedType == null) {
                fieldMatchedType = resolvedType;
            }
        }

        // Find test methods
        List<MethodDeclaration> testMethods = decl.getMethods().stream()
            .filter(this::isTestMethod)
            .toList();

        if (testMethods.isEmpty()) {
            // Class-level match if any evidence exists
            if (fieldMatchedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    null,
                    TestMatchEvidence.TYPE_REFERENCE,
                    "Test class declares field referencing production type: " + fieldMatchedType,
                    fieldMatchedType
                ));
            } else if (importMatchedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    null,
                    TestMatchEvidence.IMPORT_MATCH,
                    "Test file imports production type: " + importMatchedType,
                    importMatchedType
                ));
            } else if (namingMatchedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    null,
                    TestMatchEvidence.NAMING_MATCH,
                    "Test class name corresponds by naming convention to production type: " + namingMatchedType,
                    namingMatchedType
                ));
            }
            return;
        }

        // Inspect each test method for strongest evidence
        for (MethodDeclaration method : testMethods) {
            String testMethodName = method.getNameAsString();

            // Local variables in method: name -> type
            Map<String, String> localVars = new HashMap<>();
            method.getParameters().forEach(p -> localVars.put(p.getNameAsString(), p.getType().asString()));
            for (VariableDeclarationExpr varDecl : method.findAll(VariableDeclarationExpr.class)) {
                for (VariableDeclarator v : varDecl.getVariables()) {
                    localVars.put(v.getNameAsString(), v.getType().asString());
                }
            }

            // 1. Check for Direct Method Invocations
            String invokedMethodId = null;
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                List<String> potentialTargetMethods = targetMethodsBySimpleName.getOrDefault(methodName, List.of());
                if (potentialTargetMethods.isEmpty()) {
                    continue;
                }

                // Check scope
                Optional<Expression> scopeOpt = call.getScope();
                String scopeType = null;
                if (scopeOpt.isPresent()) {
                    Expression scope = scopeOpt.get();
                    if (scope instanceof NameExpr nameExpr) {
                        String name = nameExpr.getNameAsString();
                        if (localVars.containsKey(name)) {
                            scopeType = localVars.get(name);
                        } else if (testClassFields.containsKey(name)) {
                            scopeType = testClassFields.get(name);
                        } else if (targetSimpleToFqn.containsKey(name)) {
                            scopeType = targetSimpleToFqn.get(name);
                        }
                    } else if (scope instanceof FieldAccessExpr fa) {
                        String name = fa.getNameAsString();
                        if (testClassFields.containsKey(name)) {
                            scopeType = testClassFields.get(name);
                        }
                    }
                }

                String resolvedScopeType = resolveType(scopeType, packageName, imports, targetTypeFqns, targetSimpleToFqn);
                for (String candidateMethodId : potentialTargetMethods) {
                    if (resolvedScopeType != null && candidateMethodId.startsWith(resolvedScopeType + "#")) {
                        invokedMethodId = candidateMethodId;
                        break;
                    } else if (resolvedScopeType == null && targetTypeFqns.stream().anyMatch(t -> candidateMethodId.startsWith(t + "#"))) {
                        invokedMethodId = candidateMethodId;
                        break;
                    }
                }

                if (invokedMethodId != null) {
                    break;
                }
            }

            // Also check constructor invocations (new TargetType(...))
            if (invokedMethodId == null) {
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                    String rawType = creation.getType().asString();
                    String resolvedType = resolveType(rawType, packageName, imports, targetTypeFqns, targetSimpleToFqn);
                    if (resolvedType != null) {
                        for (String methodId : targetMethodIds) {
                            if (methodId.startsWith(resolvedType + "#<init>")) {
                                invokedMethodId = methodId;
                                break;
                            }
                        }
                    }
                    if (invokedMethodId != null) {
                        break;
                    }
                }
            }

            if (invokedMethodId != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    testMethodName,
                    TestMatchEvidence.METHOD_INVOCATION,
                    "Directly invokes production method: " + invokedMethodId,
                    invokedMethodId
                ));
                continue;
            }

            // 2. Check for Direct Type References inside method body
            String referencedType = null;
            // A. Local variable types
            for (String varType : localVars.values()) {
                String resolved = resolveType(varType, packageName, imports, targetTypeFqns, targetSimpleToFqn);
                if (resolved != null) {
                    referencedType = resolved;
                    break;
                }
            }
            // B. Class expressions (e.g. mock(OrderService.class))
            if (referencedType == null) {
                for (ClassExpr classExpr : method.findAll(ClassExpr.class)) {
                    String raw = classExpr.getType().asString();
                    String resolved = resolveType(raw, packageName, imports, targetTypeFqns, targetSimpleToFqn);
                    if (resolved != null) {
                        referencedType = resolved;
                        break;
                    }
                }
            }
            // C. Object creation expressions (new OrderService())
            if (referencedType == null) {
                for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
                    String raw = creation.getType().asString();
                    String resolved = resolveType(raw, packageName, imports, targetTypeFqns, targetSimpleToFqn);
                    if (resolved != null) {
                        referencedType = resolved;
                        break;
                    }
                }
            }
            // D. Use of class field in test method
            if (referencedType == null && fieldMatchedType != null) {
                referencedType = fieldMatchedType;
            }

            if (referencedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    testMethodName,
                    TestMatchEvidence.TYPE_REFERENCE,
                    "Direct reference to production type: " + referencedType,
                    referencedType
                ));
                continue;
            }

            // 3. Fallback to Import Match
            if (importMatchedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    testMethodName,
                    TestMatchEvidence.IMPORT_MATCH,
                    "Test file imports production type: " + importMatchedType,
                    importMatchedType
                ));
                continue;
            }

            // 4. Fallback to Naming Match
            if (namingMatchedType != null) {
                candidates.add(new TestImpactCandidate(
                    filePath,
                    testClassFqn,
                    testMethodName,
                    TestMatchEvidence.NAMING_MATCH,
                    "Test class name corresponds by naming convention to production type: " + namingMatchedType,
                    namingMatchedType
                ));
            }
            // If none matched, this test method is unrelated!
        }
    }

    private boolean isTestClass(ClassOrInterfaceDeclaration decl, String filePath) {
        String name = decl.getNameAsString();
        if (name.endsWith("Test") || name.endsWith("Tests")) {
            return true;
        }
        if (filePath != null && (filePath.contains("/test/") || filePath.endsWith("Test.java") || filePath.endsWith("Tests.java"))) {
            return true;
        }
        return decl.getMethods().stream().anyMatch(this::isTestMethod);
    }

    private boolean isTestMethod(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("Test".equals(name) || "ParameterizedTest".equals(name) || "RepeatedTest".equals(name)
                || name.endsWith(".Test") || name.endsWith(".ParameterizedTest") || name.endsWith(".RepeatedTest")) {
                return true;
            }
        }
        return false;
    }

    private String resolveType(
        String typeName,
        String currentPackage,
        List<String> imports,
        Set<String> targetTypeFqns,
        Map<String, String> targetSimpleToFqn
    ) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        // Direct FQN match
        if (targetTypeFqns.contains(typeName)) {
            return typeName;
        }

        // Simple name match via target map
        if (targetSimpleToFqn.containsKey(typeName)) {
            return targetSimpleToFqn.get(typeName);
        }

        // Check imports
        for (String imp : imports) {
            if (imp.equals(typeName) || imp.endsWith("." + typeName)) {
                if (targetTypeFqns.contains(imp)) {
                    return imp;
                }
            }
        }

        // Check same package
        if (currentPackage != null && !currentPackage.isBlank()) {
            String samePkgFqn = currentPackage + "." + typeName;
            if (targetTypeFqns.contains(samePkgFqn)) {
                return samePkgFqn;
            }
        }

        return null;
    }

    private String buildFqn(String packageName, String typeName) {
        return (packageName != null && !packageName.isBlank())
            ? packageName + "." + typeName
            : typeName;
    }
}
