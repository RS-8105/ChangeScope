package com.changescope.service;

import com.changescope.dto.BaseGraphSnapshot;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.FileParseError;
import com.changescope.dto.GraphOverlayResult;
import com.changescope.dto.IndexedTypeDto;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.ParsedConstructor;
import com.changescope.dto.ParsedField;
import com.changescope.dto.ParsedJavaFile;
import com.changescope.dto.ParsedMethod;
import com.changescope.dto.ParsedType;
import com.changescope.dto.PullRequestFileChange;
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
public class GraphOverlayService {

    private final JavaSourceParserService parserService;

    public GraphOverlayService() {
        this(new JavaSourceParserService());
    }

    public GraphOverlayService(JavaSourceParserService parserService) {
        this.parserService = parserService;
    }

    public GraphOverlayResult overlay(
        BaseGraphSnapshot baseSnapshot,
        List<PullRequestFileChange> fileChanges,
        List<JavaSourceFileInput> headFiles,
        List<String> changedTypes
    ) {
        BaseGraphSnapshot effectiveBase = (baseSnapshot != null)
            ? baseSnapshot
            : new BaseGraphSnapshot(List.of(), List.of());

        // 1. Determine paths to invalidate
        Set<String> pathsToInvalidate = new HashSet<>();
        if (fileChanges != null) {
            for (PullRequestFileChange change : fileChanges) {
                if (change == null) {
                    continue;
                }
                FileChangeStatus status = change.status();
                if (status == FileChangeStatus.MODIFIED || status == FileChangeStatus.DELETED) {
                    if (change.filePath() != null) {
                        pathsToInvalidate.add(change.filePath());
                    }
                    if (change.previousFilePath() != null) {
                        pathsToInvalidate.add(change.previousFilePath());
                    }
                } else if (status == FileChangeStatus.RENAMED) {
                    if (change.previousFilePath() != null) {
                        pathsToInvalidate.add(change.previousFilePath());
                    }
                    if (change.filePath() != null) {
                        pathsToInvalidate.add(change.filePath());
                    }
                }
            }
        }

        if (headFiles != null) {
            for (JavaSourceFileInput headFile : headFiles) {
                if (headFile != null && headFile.filePath() != null && isJavaFile(headFile.filePath())) {
                    pathsToInvalidate.add(headFile.filePath());
                }
            }
        }

        // 2. Index base snapshot declared types and file associations
        Map<String, Set<String>> typesByFilePath = new HashMap<>();
        Map<String, Set<String>> filePathsByFqn = new HashMap<>();
        Set<String> activeTypes = new HashSet<>();

        for (IndexedTypeDto typeDto : effectiveBase.indexedTypes()) {
            if (typeDto == null || typeDto.fullyQualifiedName() == null) {
                continue;
            }
            String fqn = typeDto.fullyQualifiedName();
            String path = typeDto.filePath();
            activeTypes.add(fqn);
            if (path != null) {
                typesByFilePath.computeIfAbsent(path, k -> new HashSet<>()).add(fqn);
                filePathsByFqn.computeIfAbsent(fqn, k -> new HashSet<>()).add(path);
            }
        }

        // 3. Invalidate types declared at affected paths
        Set<String> solelyInvalidatedTypes = new HashSet<>();
        for (String path : pathsToInvalidate) {
            Set<String> typesInPath = typesByFilePath.remove(path);
            if (typesInPath != null) {
                for (String fqn : typesInPath) {
                    Set<String> pathsForFqn = filePathsByFqn.get(fqn);
                    if (pathsForFqn != null) {
                        pathsForFqn.remove(path);
                        if (pathsForFqn.isEmpty()) {
                            filePathsByFqn.remove(fqn);
                            activeTypes.remove(fqn);
                            solelyInvalidatedTypes.add(fqn);
                        }
                    }
                }
            }
        }

        // 4. Preserve outgoing dependencies from unchanged files (including edges to modified/deleted types)
        List<TypeDependency> preservedBaseDependencies = new ArrayList<>();
        for (TypeDependency dep : effectiveBase.dependencies()) {
            if (dep == null) {
                continue;
            }
            String sourcePath = dep.sourceFilePath();
            if (sourcePath != null) {
                if (!pathsToInvalidate.contains(sourcePath)) {
                    preservedBaseDependencies.add(dep);
                }
            } else {
                if (!solelyInvalidatedTypes.contains(dep.sourceType())) {
                    preservedBaseDependencies.add(dep);
                }
            }
        }

        // 5. Parse valid head files and register their declared types into active pool
        List<FileParseError> parseErrors = new ArrayList<>();
        List<ParsedFileContext> validHeadFiles = new ArrayList<>();

        if (headFiles != null) {
            for (JavaSourceFileInput headFile : headFiles) {
                if (headFile == null || headFile.filePath() == null || !isJavaFile(headFile.filePath())) {
                    continue;
                }

                ParsedJavaFile parsed = parserService.parse(headFile.sourceCode());
                if (!parsed.success()) {
                    parseErrors.add(new FileParseError(headFile.filePath(), parsed.errorMessage()));
                    continue;
                }

                String currentPackage = parsed.packageName();
                for (ParsedType type : parsed.types()) {
                    String fqn = buildFqn(currentPackage, type.name());
                    activeTypes.add(fqn);
                    typesByFilePath.computeIfAbsent(headFile.filePath(), k -> new HashSet<>()).add(fqn);
                    filePathsByFqn.computeIfAbsent(fqn, k -> new HashSet<>()).add(headFile.filePath());
                }

                validHeadFiles.add(new ParsedFileContext(headFile.filePath(), parsed));
            }
        }

        // 6. Extract outgoing dependencies from new/modified head files against the composite active type pool
        Set<TypeDependency> newPrDependencies = new LinkedHashSet<>();
        for (ParsedFileContext ctx : validHeadFiles) {
            String filePath = ctx.filePath();
            ParsedJavaFile file = ctx.parsed();
            String currentPackage = file.packageName();
            List<String> imports = file.imports();

            for (ParsedType type : file.types()) {
                String sourceFqn = buildFqn(currentPackage, type.name());

                // 1. Inheritance (extends)
                for (String candidate : type.extendedTypes()) {
                    addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.EXTENDS,
                        filePath, currentPackage, imports, activeTypes);
                }

                // 2. Implementation (implements)
                for (String candidate : type.implementedTypes()) {
                    addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.IMPLEMENTS,
                        filePath, currentPackage, imports, activeTypes);
                }

                // 3. Field types
                for (ParsedField field : type.fields()) {
                    for (String candidate : field.referencedTypeNames()) {
                        addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.FIELD,
                            filePath, currentPackage, imports, activeTypes);
                    }
                }

                // 4. Constructor parameters
                for (ParsedConstructor ctor : type.constructors()) {
                    for (String candidate : ctor.referencedTypeNames()) {
                        addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.CONSTRUCTOR_PARAMETER,
                            filePath, currentPackage, imports, activeTypes);
                    }
                }

                // 5. Method returns & parameters
                for (ParsedMethod method : type.methods()) {
                    for (String candidate : method.returnTypeReferencedTypes()) {
                        addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.METHOD_RETURN,
                            filePath, currentPackage, imports, activeTypes);
                    }
                    for (String candidate : method.parameterReferencedTypes()) {
                        addDependencyIfResolved(newPrDependencies, sourceFqn, candidate, RelationshipKind.METHOD_PARAMETER,
                            filePath, currentPackage, imports, activeTypes);
                    }
                }
            }
        }

        // 7. Combine preserved base dependencies with new PR dependencies
        Set<TypeDependency> combinedDependencies = new LinkedHashSet<>(preservedBaseDependencies);
        combinedDependencies.addAll(newPrDependencies);

        return new GraphOverlayResult(
            new ArrayList<>(combinedDependencies),
            activeTypes,
            parseErrors
        );
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
                String pkgPrefix = imp.substring(0, imp.length() - 1);
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

    private boolean isJavaFile(String filePath) {
        return filePath != null && filePath.endsWith(".java");
    }

    private String buildFqn(String packageName, String typeName) {
        return (packageName != null && !packageName.isBlank())
            ? packageName + "." + typeName
            : typeName;
    }

    private record ParsedFileContext(
        String filePath,
        ParsedJavaFile parsed
    ) {}
}
