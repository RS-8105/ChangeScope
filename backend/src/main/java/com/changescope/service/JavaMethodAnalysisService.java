package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.MethodAnalysisResult;
import com.changescope.dto.MethodDependency;
import com.changescope.dto.UnresolvedMethodCall;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
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
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

@Service
public class JavaMethodAnalysisService {

    private final JavaParser javaParser;

    public JavaMethodAnalysisService() {
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(configuration);
    }

    public JavaMethodAnalysisService(JavaSourceParserService parserService) {
        this();
    }

    public MethodAnalysisResult analyze(Map<String, String> filesByPath, List<String> changedTypes) {
        if (filesByPath == null || filesByPath.isEmpty()) {
            return new MethodAnalysisResult(List.of(), List.of(), List.of(), List.of());
        }
        List<JavaSourceFileInput> inputs = filesByPath.entrySet().stream()
            .map(entry -> new JavaSourceFileInput(entry.getKey(), entry.getValue()))
            .toList();
        return analyze(inputs, changedTypes);
    }

    public MethodAnalysisResult analyze(List<JavaSourceFileInput> files, List<String> changedTypes) {
        if (files == null || files.isEmpty()) {
            return new MethodAnalysisResult(List.of(), List.of(), List.of(), List.of());
        }

        // Pass 1: Parse AST and catalog all types, fields, constructors, and methods
        List<FileAstContext> parsedFiles = new ArrayList<>();
        Set<String> declaredTypes = new HashSet<>();
        Map<String, List<DeclaredCallableInfo>> callablesByType = new LinkedHashMap<>();
        Map<String, Map<String, String>> fieldsByType = new HashMap<>();
        Map<String, List<String>> supertypesByType = new HashMap<>();

        for (JavaSourceFileInput file : files) {
            if (file == null || file.filePath() == null || file.sourceCode() == null || file.sourceCode().isBlank()) {
                continue;
            }

            ParseResult<CompilationUnit> parseResult;
            try {
                parseResult = javaParser.parse(file.sourceCode());
            } catch (Exception ex) {
                continue;
            }

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }

            CompilationUnit cu = parseResult.getResult().get();
            String packageName = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse(null);
            List<String> imports = cu.getImports().stream()
                .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                .toList();

            FileAstContext fileCtx = new FileAstContext(file.filePath(), packageName, imports, cu);
            parsedFiles.add(fileCtx);

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String typeFqn = buildFqn(packageName, decl.getNameAsString());
                declaredTypes.add(typeFqn);

                // Collect supertypes
                List<String> supers = new ArrayList<>();
                decl.getExtendedTypes().forEach(t -> supers.add(t.getNameWithScope()));
                decl.getImplementedTypes().forEach(t -> supers.add(t.getNameWithScope()));
                supertypesByType.put(typeFqn, supers);

                // Collect fields: name -> declared type string
                Map<String, String> fieldMap = new HashMap<>();
                for (FieldDeclaration field : decl.getFields()) {
                    String fieldType = field.getElementType().asString();
                    for (VariableDeclarator var : field.getVariables()) {
                        fieldMap.put(var.getNameAsString(), fieldType);
                    }
                }
                fieldsByType.put(typeFqn, fieldMap);

                List<DeclaredCallableInfo> callableList = callablesByType.computeIfAbsent(typeFqn, k -> new ArrayList<>());

                // Catalog constructors
                for (ConstructorDeclaration ctor : decl.getConstructors()) {
                    List<String> paramTypes = ctor.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .toList();
                    String methodId = buildMethodIdentifier(typeFqn, "<init>", paramTypes, null);
                    callableList.add(new DeclaredCallableInfo(
                        methodId,
                        typeFqn,
                        "<init>",
                        paramTypes,
                        null,
                        true,
                        file.filePath(),
                        ctor
                    ));
                }

                // Catalog methods
                for (MethodDeclaration method : decl.getMethods()) {
                    List<String> paramTypes = method.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .toList();
                    String returnType = method.getType().asString();
                    String methodId = buildMethodIdentifier(typeFqn, method.getNameAsString(), paramTypes, returnType);
                    callableList.add(new DeclaredCallableInfo(
                        methodId,
                        typeFqn,
                        method.getNameAsString(),
                        paramTypes,
                        returnType,
                        false,
                        file.filePath(),
                        method
                    ));
                }
            }
        }

        // Pass 2: Inspect AST of every declared callable to extract method calls and constructor invocations
        Set<MethodDependency> methodDependencies = new LinkedHashSet<>();
        Set<UnresolvedMethodCall> unresolvedCalls = new LinkedHashSet<>();

        for (FileAstContext fileCtx : parsedFiles) {
            String filePath = fileCtx.filePath();
            String currentPackage = fileCtx.packageName();
            List<String> imports = fileCtx.imports();

            for (ClassOrInterfaceDeclaration decl : fileCtx.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String callerTypeFqn = buildFqn(currentPackage, decl.getNameAsString());
                Map<String, String> classFields = fieldsByType.getOrDefault(callerTypeFqn, Collections.emptyMap());

                List<DeclaredCallableInfo> callables = callablesByType.getOrDefault(callerTypeFqn, List.of());
                for (DeclaredCallableInfo caller : callables) {
                    CallableDeclaration<?> node = caller.astNode();

                    // Index local variables in this callable
                    Map<String, String> localScopeVariables = new HashMap<>();
                    for (Parameter param : caller.parameters()) {
                        localScopeVariables.put(param.getNameAsString(), param.getType().asString());
                    }
                    for (VariableDeclarationExpr varDecl : node.findAll(VariableDeclarationExpr.class)) {
                        for (VariableDeclarator v : varDecl.getVariables()) {
                            localScopeVariables.put(v.getNameAsString(), v.getType().asString());
                        }
                    }

                    // A. Explicit constructor invocations (this(...) or super(...))
                    for (ExplicitConstructorInvocationStmt stmt : node.findAll(ExplicitConstructorInvocationStmt.class)) {
                        String targetTypeCandidate = stmt.isThis() ? callerTypeFqn : resolveSuperclass(callerTypeFqn, supertypesByType, currentPackage, imports, declaredTypes);
                        resolveCallableCall(
                            caller,
                            "<init>",
                            targetTypeCandidate,
                            stmt.getArguments().size(),
                            filePath,
                            currentPackage,
                            imports,
                            declaredTypes,
                            callablesByType,
                            supertypesByType,
                            methodDependencies,
                            unresolvedCalls
                        );
                    }

                    // B. Object creation expressions (new Foo(...))
                    for (ObjectCreationExpr creation : node.findAll(ObjectCreationExpr.class)) {
                        String rawType = creation.getType().asString();
                        resolveCallableCall(
                            caller,
                            "<init>",
                            rawType,
                            creation.getArguments().size(),
                            filePath,
                            currentPackage,
                            imports,
                            declaredTypes,
                            callablesByType,
                            supertypesByType,
                            methodDependencies,
                            unresolvedCalls
                        );
                    }

                    // C. Method call expressions (foo.bar(...), this.bar(...), bar(...))
                    for (MethodCallExpr call : node.findAll(MethodCallExpr.class)) {
                        String methodName = call.getNameAsString();
                        int argCount = call.getArguments().size();
                        Optional<Expression> scopeOpt = call.getScope();

                        String targetTypeCandidate = null;

                        if (scopeOpt.isEmpty() || scopeOpt.get() instanceof ThisExpr) {
                            // Direct or this. call
                            targetTypeCandidate = callerTypeFqn;
                        } else if (scopeOpt.get() instanceof SuperExpr) {
                            targetTypeCandidate = resolveSuperclass(callerTypeFqn, supertypesByType, currentPackage, imports, declaredTypes);
                        } else if (scopeOpt.get() instanceof NameExpr nameExpr) {
                            String name = nameExpr.getNameAsString();
                            if (localScopeVariables.containsKey(name)) {
                                targetTypeCandidate = localScopeVariables.get(name);
                            } else if (classFields.containsKey(name)) {
                                targetTypeCandidate = classFields.get(name);
                            } else {
                                // Could be a static method call on a class name (e.g. Utility.doWork())
                                targetTypeCandidate = name;
                            }
                        } else if (scopeOpt.get() instanceof FieldAccessExpr fieldAccess) {
                            String name = fieldAccess.getNameAsString();
                            if (classFields.containsKey(name)) {
                                targetTypeCandidate = classFields.get(name);
                            } else {
                                targetTypeCandidate = fieldAccess.toString();
                            }
                        } else if (scopeOpt.get() instanceof ObjectCreationExpr creationExpr) {
                            targetTypeCandidate = creationExpr.getType().asString();
                        } else {
                            // Chained or complex expression
                            targetTypeCandidate = scopeOpt.get().toString();
                        }

                        resolveCallableCall(
                            caller,
                            methodName,
                            targetTypeCandidate,
                            argCount,
                            filePath,
                            currentPackage,
                            imports,
                            declaredTypes,
                            callablesByType,
                            supertypesByType,
                            methodDependencies,
                            unresolvedCalls
                        );
                    }
                }
            }
        }

        // Pass 3: Calculate changed methods and affected methods via BFS call-graph traversal
        List<String> cleanChangedTypes = (changedTypes != null) ? changedTypes.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList() : List.of();

        List<String> changedMethods = new ArrayList<>();
        for (String changedType : cleanChangedTypes) {
            List<DeclaredCallableInfo> declaredInType = callablesByType.getOrDefault(changedType, List.of());
            for (DeclaredCallableInfo info : declaredInType) {
                changedMethods.add(info.methodId());
            }
        }
        changedMethods.sort(String::compareTo);

        // Build reverse dependency graph: targetMethodId -> Set of (callerMethodId, callerType)
        Map<String, Set<CallerInfo>> callersByTarget = new HashMap<>();
        for (MethodDependency dep : methodDependencies) {
            callersByTarget
                .computeIfAbsent(dep.targetMethod(), k -> new TreeSet<>(Comparator.comparing(CallerInfo::methodId)))
                .add(new CallerInfo(dep.callerMethod(), dep.callerType()));
        }

        Set<String> changedMethodSet = new HashSet<>(changedMethods);
        Map<String, Integer> shortestDistance = new HashMap<>();
        Map<String, String> declaringTypeByMethod = new HashMap<>();
        Queue<QueueNode> queue = new ArrayDeque<>();

        for (String changed : changedMethods) {
            queue.add(new QueueNode(changed, 0));
        }

        while (!queue.isEmpty()) {
            QueueNode current = queue.poll();
            String currentMethod = current.methodId();
            int currentDist = current.distance();

            Set<CallerInfo> directCallers = callersByTarget.getOrDefault(currentMethod, Collections.emptySet());
            for (CallerInfo caller : directCallers) {
                if (changedMethodSet.contains(caller.methodId())) {
                    continue;
                }

                int nextDist = currentDist + 1;
                if (!shortestDistance.containsKey(caller.methodId())) {
                    shortestDistance.put(caller.methodId(), nextDist);
                    declaringTypeByMethod.put(caller.methodId(), caller.declaringType());
                    queue.add(new QueueNode(caller.methodId(), nextDist));
                }
            }
        }

        List<AffectedMethod> affectedMethods = shortestDistance.entrySet().stream()
            .map(entry -> new AffectedMethod(
                entry.getKey(),
                declaringTypeByMethod.get(entry.getKey()),
                entry.getValue()
            ))
            .sorted(Comparator
                .comparingInt(AffectedMethod::distance)
                .thenComparing(AffectedMethod::declaringType)
                .thenComparing(AffectedMethod::methodIdentifier))
            .toList();

        return new MethodAnalysisResult(
            changedMethods,
            affectedMethods,
            new ArrayList<>(methodDependencies),
            new ArrayList<>(unresolvedCalls)
        );
    }

    private void resolveCallableCall(
        DeclaredCallableInfo caller,
        String calledMethodName,
        String targetTypeCandidate,
        int argumentCount,
        String filePath,
        String currentPackage,
        List<String> imports,
        Set<String> declaredTypes,
        Map<String, List<DeclaredCallableInfo>> callablesByType,
        Map<String, List<String>> supertypesByType,
        Set<MethodDependency> methodDependencies,
        Set<UnresolvedMethodCall> unresolvedCalls
    ) {
        String targetFqn = resolveType(targetTypeCandidate, currentPackage, imports, declaredTypes);

        if (targetFqn == null || !declaredTypes.contains(targetFqn)) {
            unresolvedCalls.add(new UnresolvedMethodCall(
                caller.methodId(),
                caller.declaringType(),
                calledMethodName,
                targetTypeCandidate,
                "UNRESOLVED_TARGET_TYPE",
                List.of(),
                filePath
            ));
            return;
        }

        // Look for matching callables in targetFqn (and its declared supertypes)
        List<DeclaredCallableInfo> matchingCandidates = findMatchingCandidates(
            targetFqn,
            calledMethodName,
            callablesByType,
            supertypesByType,
            currentPackage,
            imports,
            declaredTypes,
            new HashSet<>()
        );

        if (matchingCandidates.isEmpty()) {
            unresolvedCalls.add(new UnresolvedMethodCall(
                caller.methodId(),
                caller.declaringType(),
                calledMethodName,
                targetFqn,
                "METHOD_NOT_FOUND",
                List.of(),
                filePath
            ));
            return;
        }

        if (matchingCandidates.size() == 1) {
            DeclaredCallableInfo target = matchingCandidates.get(0);
            methodDependencies.add(new MethodDependency(
                caller.methodId(),
                caller.declaringType(),
                target.methodId(),
                target.declaringType(),
                filePath
            ));
            return;
        }

        // Multiple candidates with same name (overloading). Filter by arity:
        List<DeclaredCallableInfo> arityMatches = matchingCandidates.stream()
            .filter(c -> c.parameterTypes().size() == argumentCount)
            .toList();

        if (arityMatches.size() == 1) {
            DeclaredCallableInfo target = arityMatches.get(0);
            methodDependencies.add(new MethodDependency(
                caller.methodId(),
                caller.declaringType(),
                target.methodId(),
                target.declaringType(),
                filePath
            ));
        } else if (arityMatches.isEmpty()) {
            List<String> candidateIds = matchingCandidates.stream()
                .map(DeclaredCallableInfo::methodId)
                .sorted()
                .toList();
            unresolvedCalls.add(new UnresolvedMethodCall(
                caller.methodId(),
                caller.declaringType(),
                calledMethodName,
                targetFqn,
                "ARITY_MISMATCH",
                candidateIds,
                filePath
            ));
        } else {
            // Ambiguous overload (multiple overloads share same name and parameter count)
            List<String> candidateIds = arityMatches.stream()
                .map(DeclaredCallableInfo::methodId)
                .sorted()
                .toList();
            unresolvedCalls.add(new UnresolvedMethodCall(
                caller.methodId(),
                caller.declaringType(),
                calledMethodName,
                targetFqn,
                "AMBIGUOUS_OVERLOAD",
                candidateIds,
                filePath
            ));
        }
    }

    private List<DeclaredCallableInfo> findMatchingCandidates(
        String targetFqn,
        String methodName,
        Map<String, List<DeclaredCallableInfo>> callablesByType,
        Map<String, List<String>> supertypesByType,
        String currentPackage,
        List<String> imports,
        Set<String> declaredTypes,
        Set<String> visitedTypes
    ) {
        if (targetFqn == null || !visitedTypes.add(targetFqn)) {
            return List.of();
        }

        List<DeclaredCallableInfo> candidates = new ArrayList<>();
        List<DeclaredCallableInfo> declaredInType = callablesByType.getOrDefault(targetFqn, List.of());
        for (DeclaredCallableInfo info : declaredInType) {
            if (info.methodName().equals(methodName)) {
                candidates.add(info);
            }
        }

        if (!candidates.isEmpty()) {
            return candidates;
        }

        // If not found in class, check declared supertypes
        List<String> supers = supertypesByType.getOrDefault(targetFqn, List.of());
        for (String rawSuper : supers) {
            String superFqn = resolveType(rawSuper, currentPackage, imports, declaredTypes);
            if (superFqn != null && declaredTypes.contains(superFqn)) {
                List<DeclaredCallableInfo> superCandidates = findMatchingCandidates(
                    superFqn,
                    methodName,
                    callablesByType,
                    supertypesByType,
                    currentPackage,
                    imports,
                    declaredTypes,
                    visitedTypes
                );
                candidates.addAll(superCandidates);
                if (!candidates.isEmpty()) {
                    break;
                }
            }
        }

        return candidates;
    }

    private String resolveSuperclass(
        String typeFqn,
        Map<String, List<String>> supertypesByType,
        String currentPackage,
        List<String> imports,
        Set<String> declaredTypes
    ) {
        List<String> supers = supertypesByType.getOrDefault(typeFqn, List.of());
        if (supers.isEmpty()) {
            return null;
        }
        return resolveType(supers.get(0), currentPackage, imports, declaredTypes);
    }

    public static String buildMethodIdentifier(
        String typeFqn,
        String methodName,
        List<String> parameterTypes,
        String returnType
    ) {
        String params = (parameterTypes != null && !parameterTypes.isEmpty())
            ? String.join(", ", parameterTypes)
            : "";
        if ("<init>".equals(methodName)) {
            return typeFqn + "#<init>(" + params + ")";
        }
        String ret = (returnType != null && !returnType.isBlank()) ? returnType : "void";
        return typeFqn + "#" + methodName + "(" + params + "): " + ret;
    }

    public String resolveType(
        String candidateName,
        String currentPackage,
        List<String> imports,
        Set<String> declaredTypes
    ) {
        if (candidateName == null || candidateName.isBlank()) {
            return null;
        }

        // 1. Direct match with a fully qualified name
        if (declaredTypes.contains(candidateName)) {
            return candidateName;
        }

        // 2. Exact single-type import match
        for (String imp : imports) {
            if (imp.equals(candidateName) || imp.endsWith("." + candidateName)) {
                if (declaredTypes.contains(imp)) {
                    return imp;
                }
            }
        }

        // 3. Same package match
        if (currentPackage != null && !currentPackage.isBlank()) {
            String samePackageFqn = currentPackage + "." + candidateName;
            if (declaredTypes.contains(samePackageFqn)) {
                return samePackageFqn;
            }
        }

        // 4. Wildcard import resolution
        List<String> wildcardMatches = new ArrayList<>();
        for (String imp : imports) {
            if (imp.endsWith(".*")) {
                String pkgPrefix = imp.substring(0, imp.length() - 1);
                String testFqn = pkgPrefix + candidateName;
                if (declaredTypes.contains(testFqn)) {
                    wildcardMatches.add(testFqn);
                }
            }
        }

        if (wildcardMatches.size() == 1) {
            return wildcardMatches.get(0);
        }

        return null;
    }

    private String buildFqn(String packageName, String typeName) {
        return (packageName != null && !packageName.isBlank())
            ? packageName + "." + typeName
            : typeName;
    }

    private record FileAstContext(
        String filePath,
        String packageName,
        List<String> imports,
        CompilationUnit cu
    ) {}

    private record DeclaredCallableInfo(
        String methodId,
        String declaringType,
        String methodName,
        List<String> parameterTypes,
        String returnType,
        boolean isConstructor,
        String filePath,
        CallableDeclaration<?> astNode
    ) {
        List<Parameter> parameters() {
            if (astNode instanceof MethodDeclaration m) {
                return m.getParameters();
            } else if (astNode instanceof ConstructorDeclaration c) {
                return c.getParameters();
            }
            return List.of();
        }
    }

    private record CallerInfo(String methodId, String declaringType) {}

    private record QueueNode(String methodId, int distance) {}
}
