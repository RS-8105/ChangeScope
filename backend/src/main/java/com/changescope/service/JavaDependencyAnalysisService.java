package com.changescope.service;

import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.ParsedConstructor;
import com.changescope.dto.ParsedField;
import com.changescope.dto.ParsedJavaFile;
import com.changescope.dto.ParsedMethod;
import com.changescope.dto.ParsedType;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeDependency;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class JavaDependencyAnalysisService {

    private final JavaSourceParserService parserService;

    public JavaDependencyAnalysisService(JavaSourceParserService parserService) {
        this.parserService = parserService;
    }

    public DependencyAnalysisResult analyze(Map<String, String> filesByPath) {
        if (filesByPath == null || filesByPath.isEmpty()) {
            return new DependencyAnalysisResult(List.of(), List.of());
        }
        List<JavaSourceFileInput> inputs = filesByPath.entrySet().stream()
            .map(entry -> new JavaSourceFileInput(entry.getKey(), entry.getValue()))
            .toList();
        return analyze(inputs);
    }

    public DependencyAnalysisResult analyze(List<JavaSourceFileInput> files) {
        if (files == null || files.isEmpty()) {
            return new DependencyAnalysisResult(List.of(), List.of());
        }

        List<FileParseError> errors = new ArrayList<>();
        List<FileContext> validFiles = new ArrayList<>();
        Set<String> declaredTypes = new HashSet<>();
        Map<String, List<String>> typesBySimpleName = new HashMap<>();

        // Pass 1: Parse all files, record errors, and index all declared types
        for (JavaSourceFileInput file : files) {
            if (file == null || file.filePath() == null) {
                continue;
            }

            ParsedJavaFile parsed = parserService.parse(file.sourceCode());
            if (!parsed.success()) {
                errors.add(new FileParseError(file.filePath(), parsed.errorMessage()));
                continue;
            }

            for (ParsedType type : parsed.types()) {
                String fqn = buildFqn(parsed.packageName(), type.name());
                declaredTypes.add(fqn);
                typesBySimpleName.computeIfAbsent(type.name(), k -> new ArrayList<>()).add(fqn);
            }

            validFiles.add(new FileContext(file.filePath(), parsed));
        }

        // Pass 2: Extract and resolve dependencies across declared types
        Set<TypeDependency> dependencies = new LinkedHashSet<>();

        for (FileContext ctx : validFiles) {
            String filePath = ctx.filePath();
            ParsedJavaFile file = ctx.parsed();
            String currentPackage = file.packageName();
            List<String> imports = file.imports();

            for (ParsedType type : file.types()) {
                String sourceFqn = buildFqn(currentPackage, type.name());

                // 1. Inheritance (extends)
                for (String candidate : type.extendedTypes()) {
                    addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.EXTENDS,
                        filePath, currentPackage, imports, declaredTypes);
                }

                // 2. Implementation (implements)
                for (String candidate : type.implementedTypes()) {
                    addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.IMPLEMENTS,
                        filePath, currentPackage, imports, declaredTypes);
                }

                // 3. Field types
                for (ParsedField field : type.fields()) {
                    for (String candidate : field.referencedTypeNames()) {
                        addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.FIELD,
                            filePath, currentPackage, imports, declaredTypes);
                    }
                }

                // 4. Constructor parameters
                for (ParsedConstructor ctor : type.constructors()) {
                    for (String candidate : ctor.referencedTypeNames()) {
                        addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.CONSTRUCTOR_PARAMETER,
                            filePath, currentPackage, imports, declaredTypes);
                    }
                }

                // 5. Method returns & parameters
                for (ParsedMethod method : type.methods()) {
                    for (String candidate : method.returnTypeReferencedTypes()) {
                        addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.METHOD_RETURN,
                            filePath, currentPackage, imports, declaredTypes);
                    }
                    for (String candidate : method.parameterReferencedTypes()) {
                        addDependencyIfResolved(dependencies, sourceFqn, candidate, RelationshipKind.METHOD_PARAMETER,
                            filePath, currentPackage, imports, declaredTypes);
                    }
                }
            }
        }

        return new DependencyAnalysisResult(new ArrayList<>(dependencies), errors);
    }

    private void addDependencyIfResolved(
        Set<TypeDependency> dependencies,
        String sourceFqn,
        String candidateName,
        RelationshipKind kind,
        String filePath,
        String currentPackage,
        List<String> imports,
        Set<String> declaredTypes
    ) {
        String targetFqn = resolveType(candidateName, currentPackage, imports, declaredTypes);
        if (targetFqn != null && !targetFqn.equals(sourceFqn)) {
            dependencies.add(new TypeDependency(sourceFqn, targetFqn, kind, filePath));
        }
    }

    private String resolveType(
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

        // 4. Wildcard import resolution (e.g. import com.changescope.model.*)
        List<String> wildcardMatches = new ArrayList<>();
        for (String imp : imports) {
            if (imp.endsWith(".*")) {
                String pkgPrefix = imp.substring(0, imp.length() - 1); // e.g. "com.changescope.model."
                String testFqn = pkgPrefix + candidateName;
                if (declaredTypes.contains(testFqn)) {
                    wildcardMatches.add(testFqn);
                }
            }
        }

        if (wildcardMatches.size() == 1) {
            return wildcardMatches.get(0);
        } else if (wildcardMatches.size() > 1) {
            // Ambiguous wildcard resolution: do not guess
            return null;
        }

        return null;
    }

    private String buildFqn(String packageName, String typeName) {
        return (packageName != null && !packageName.isBlank())
            ? packageName + "." + typeName
            : typeName;
    }

    private record FileContext(
        String filePath,
        ParsedJavaFile parsed
    ) {}
}
