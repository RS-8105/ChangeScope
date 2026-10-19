package com.changescope.service.github;

import com.changescope.dto.ChangedTypeDetection;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GitDiffExtractionResult;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.SourceFileContent;
import com.changescope.dto.TypeChangeEvidence;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitDiffTypeExtractor {

    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile("^@@\\s+-[0-9]+(?:,[0-9]+)?\\s+\\+([0-9]+)(?:,([0-9]+))?\\s+@@.*");

    private final JavaParser javaParser;

    public GitDiffTypeExtractor() {
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(configuration);
    }

    public GitDiffExtractionResult extractChangedTypes(
        List<PullRequestFileChange> fileChanges,
        List<SourceFileContent> headSources
    ) {
        Map<String, String> headSourcesMap = new HashMap<>();
        if (headSources != null) {
            for (SourceFileContent src : headSources) {
                if (src != null && src.filePath() != null) {
                    headSourcesMap.put(src.filePath(), src.content());
                }
            }
        }
        return extractChangedTypes(fileChanges, headSourcesMap, Map.of());
    }

    public GitDiffExtractionResult extractChangedTypes(
        List<PullRequestFileChange> fileChanges,
        Map<String, String> headSources
    ) {
        return extractChangedTypes(fileChanges, headSources, Map.of());
    }

    public GitDiffExtractionResult extractChangedTypes(
        List<PullRequestFileChange> fileChanges,
        Map<String, String> headSources,
        Map<String, String> previousSources
    ) {
        if (fileChanges == null || fileChanges.isEmpty()) {
            return new GitDiffExtractionResult(List.of(), List.of(), List.of());
        }

        Map<String, String> safeHeadSources = headSources != null ? headSources : Map.of();
        Map<String, String> safePrevSources = previousSources != null ? previousSources : Map.of();

        Set<String> changedTypesSet = new LinkedHashSet<>();
        List<ChangedTypeDetection> detections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PullRequestFileChange fileChange : fileChanges) {
            if (fileChange == null || !fileChange.isJavaSource()) {
                continue;
            }

            String filePath = fileChange.filePath();

            // 1. Handle DELETED files
            if (fileChange.status() == FileChangeStatus.DELETED) {
                handleDeletedFile(filePath, safePrevSources, changedTypesSet, detections, warnings);
                continue;
            }

            // 2. Handle RENAMED files without patch changes (pure rename)
            if (fileChange.status() == FileChangeStatus.RENAMED && !fileChange.hasPatch()) {
                handleRenamedFile(filePath, fileChange.previousFilePath(), safeHeadSources, changedTypesSet, detections, warnings);
                continue;
            }

            // 3. Handle ADDED or MODIFIED files
            String headSource = safeHeadSources.get(filePath);
            if (headSource == null) {
                warnings.add("Source content not provided for file '" + filePath + "' at PR head commit.");
                continue;
            }

            if (!fileChange.hasPatch()) {
                // Missing or unavailable patch fallback
                handleMissingPatchFallback(filePath, headSource, changedTypesSet, detections, warnings);
                continue;
            }

            // Parse hunk line ranges from patch
            Set<Integer> changedLinesInHead = parsePatchChangedLines(fileChange.patch());
            if (changedLinesInHead.isEmpty()) {
                warnings.add("Patch text could not be parsed for '" + filePath + "'; falling back to all declared types.");
                handleMissingPatchFallback(filePath, headSource, changedTypesSet, detections, warnings);
                continue;
            }

            // Inspect AST source ranges against changed lines
            inspectAstRanges(filePath, headSource, changedLinesInHead, changedTypesSet, detections, warnings);
        }

        // Sort results deterministically
        List<String> sortedChangedTypes = new ArrayList<>(changedTypesSet);
        Collections.sort(sortedChangedTypes);

        detections.sort(Comparator
            .comparing(ChangedTypeDetection::filePath)
            .thenComparing(ChangedTypeDetection::fullyQualifiedName));

        Collections.sort(warnings);

        return new GitDiffExtractionResult(sortedChangedTypes, detections, warnings);
    }

    private void inspectAstRanges(
        String filePath,
        String sourceCode,
        Set<Integer> changedLines,
        Set<String> changedTypesSet,
        List<ChangedTypeDetection> detections,
        List<String> warnings
    ) {
        ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(sourceCode);
        } catch (Exception ex) {
            warnings.add("Failed to parse source file '" + filePath + "': " + ex.getMessage());
            return;
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            warnings.add("Failed to parse source file '" + filePath + "': syntax errors prevented AST inspection.");
            return;
        }

        CompilationUnit cu = parseResult.getResult().get();
        List<TypeDeclaration<?>> typeDeclarations = findAllTypeDeclarations(cu);

        if (typeDeclarations.isEmpty()) {
            return;
        }

        boolean anyMatched = false;
        for (TypeDeclaration<?> typeDecl : typeDeclarations) {
            if (typeDecl.getRange().isEmpty()) {
                continue;
            }
            Range range = typeDecl.getRange().get();
            int startLine = range.begin.line;
            int endLine = range.end.line;

            List<Integer> overlappingLines = changedLines.stream()
                .filter(l -> l >= startLine && l <= endLine)
                .sorted()
                .toList();

            if (!overlappingLines.isEmpty()) {
                anyMatched = true;
                String fqn = getFqn(typeDecl, cu);
                changedTypesSet.add(fqn);
                detections.add(new ChangedTypeDetection(
                    fqn,
                    filePath,
                    TypeChangeEvidence.PATCH_LINE_MATCH,
                    "Type range [" + startLine + ".." + endLine + "] overlaps patch changes at line(s): " + overlappingLines
                ));
            }
        }

        // If changes occurred in header/imports outside any type declaration
        if (!anyMatched) {
            for (TypeDeclaration<?> typeDecl : typeDeclarations) {
                String fqn = getFqn(typeDecl, cu);
                changedTypesSet.add(fqn);
                detections.add(new ChangedTypeDetection(
                    fqn,
                    filePath,
                    TypeChangeEvidence.PATCH_LINE_MATCH,
                    "Header or import changes outside type boundaries at line(s): " + changedLines
                ));
            }
        }
    }

    private void handleDeletedFile(
        String filePath,
        Map<String, String> previousSources,
        Set<String> changedTypesSet,
        List<ChangedTypeDetection> detections,
        List<String> warnings
    ) {
        String prevSource = previousSources.get(filePath);
        if (prevSource != null && !prevSource.isBlank()) {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(prevSource);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();
                for (TypeDeclaration<?> typeDecl : findAllTypeDeclarations(cu)) {
                    String fqn = getFqn(typeDecl, cu);
                    changedTypesSet.add(fqn);
                    detections.add(new ChangedTypeDetection(
                        fqn,
                        filePath,
                        TypeChangeEvidence.DELETED_FILE_PREVIOUS_SOURCE,
                        "Type in deleted file extracted from previous commit source"
                    ));
                }
                return;
            } else {
                warnings.add("Deleted file '" + filePath + "' previous source has syntax errors; cannot extract types.");
            }
        }

        // Previous source unavailable: infer from file path if possible
        String inferredFqn = inferFqnFromPath(filePath);
        if (inferredFqn != null) {
            changedTypesSet.add(inferredFqn);
            detections.add(new ChangedTypeDetection(
                inferredFqn,
                filePath,
                TypeChangeEvidence.DELETED_FILE_INFERRED_PATH,
                "Previous source not provided; inferred candidate type from file path: " + inferredFqn
            ));
            warnings.add("Deleted file '" + filePath + "' has no previous source text; inferred candidate type from path: " + inferredFqn);
        } else {
            warnings.add("Deleted file '" + filePath + "' has no previous source text provided; cannot determine deleted types.");
        }
    }

    private void handleRenamedFile(
        String filePath,
        String prevFilePath,
        Map<String, String> headSources,
        Set<String> changedTypesSet,
        List<ChangedTypeDetection> detections,
        List<String> warnings
    ) {
        String source = headSources.get(filePath);
        if (source == null) {
            warnings.add("Renamed file '" + filePath + "' has no source content available at head commit.");
            return;
        }

        ParseResult<CompilationUnit> parseResult = javaParser.parse(source);
        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
            CompilationUnit cu = parseResult.getResult().get();
            for (TypeDeclaration<?> typeDecl : findAllTypeDeclarations(cu)) {
                String fqn = getFqn(typeDecl, cu);
                changedTypesSet.add(fqn);
                detections.add(new ChangedTypeDetection(
                    fqn,
                    filePath,
                    TypeChangeEvidence.RENAMED_FILE,
                    "File was renamed from '" + prevFilePath + "'"
                ));
            }
        } else {
            warnings.add("Renamed file '" + filePath + "' has syntax errors; cannot extract types.");
        }
    }

    private void handleMissingPatchFallback(
        String filePath,
        String sourceCode,
        Set<String> changedTypesSet,
        List<ChangedTypeDetection> detections,
        List<String> warnings
    ) {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceCode);
        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
            CompilationUnit cu = parseResult.getResult().get();
            for (TypeDeclaration<?> typeDecl : findAllTypeDeclarations(cu)) {
                String fqn = getFqn(typeDecl, cu);
                changedTypesSet.add(fqn);
                detections.add(new ChangedTypeDetection(
                    fqn,
                    filePath,
                    TypeChangeEvidence.FILE_LEVEL_FALLBACK,
                    "Patch text unavailable; conservative file-level fallback used"
                ));
            }
            warnings.add("Patch text unavailable for '" + filePath + "'; falling back to all declared types in file.");
        } else {
            warnings.add("Failed to parse source file '" + filePath + "': syntax errors prevented AST inspection.");
        }
    }

    public Set<Integer> parsePatchChangedLines(String patch) {
        if (patch == null || patch.isBlank()) {
            return Collections.emptySet();
        }

        Set<Integer> changedLines = new HashSet<>();
        String[] lines = patch.split("\\r?\\n");
        int currentNewLine = -1;
        boolean inHunk = false;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
                if (matcher.find()) {
                    currentNewLine = Integer.parseInt(matcher.group(1));
                    inHunk = true;
                } else {
                    inHunk = false;
                }
                continue;
            }

            if (!inHunk) {
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                changedLines.add(currentNewLine);
                currentNewLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // Deletion occurred at current position in head file
                changedLines.add(currentNewLine);
            } else if (line.startsWith(" ")) {
                currentNewLine++;
            }
        }

        return changedLines;
    }

    private List<TypeDeclaration<?>> findAllTypeDeclarations(CompilationUnit cu) {
        List<TypeDeclaration<?>> result = new ArrayList<>();
        result.addAll(cu.findAll(ClassOrInterfaceDeclaration.class));
        result.addAll(cu.findAll(RecordDeclaration.class));
        result.addAll(cu.findAll(EnumDeclaration.class));
        return result;
    }

    private String inferFqnFromPath(String path) {
        if (path == null || !path.endsWith(".java")) {
            return null;
        }

        String normalized = path.replace('\\', '/');
        int javaIdx = normalized.indexOf("java/");
        String pkgAndClass;
        if (javaIdx >= 0) {
            pkgAndClass = normalized.substring(javaIdx + 5);
        } else {
            pkgAndClass = normalized;
        }

        if (pkgAndClass.endsWith(".java")) {
            pkgAndClass = pkgAndClass.substring(0, pkgAndClass.length() - 5);
        }

        pkgAndClass = pkgAndClass.replaceAll("^/+", "").replace('/', '.');
        return pkgAndClass.isBlank() ? null : pkgAndClass;
    }

    private String getFqn(TypeDeclaration<?> typeDecl, CompilationUnit cu) {
        if (typeDecl.getFullyQualifiedName().isPresent()) {
            return typeDecl.getFullyQualifiedName().get();
        }
        String pkg = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse(null);
        return (pkg != null && !pkg.isBlank()) ? pkg + "." + typeDecl.getNameAsString() : typeDecl.getNameAsString();
    }
}
