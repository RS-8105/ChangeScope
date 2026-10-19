package com.changescope.service;

import com.changescope.dto.AffectedType;
import com.changescope.dto.BlastRadiusResult;
import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.TypeDependency;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

@Service
public class BlastRadiusAnalysisService {

    public BlastRadiusResult analyze(DependencyAnalysisResult dependencyResult, List<String> changedTypes) {
        List<TypeDependency> dependencies = dependencyResult != null ? dependencyResult.dependencies() : List.of();
        return analyze(dependencies, changedTypes);
    }

    public BlastRadiusResult analyze(DependencyAnalysisResult dependencyResult, String... changedTypes) {
        List<String> list = changedTypes != null ? List.of(changedTypes) : List.of();
        return analyze(dependencyResult, list);
    }

    public BlastRadiusResult analyze(List<TypeDependency> dependencies, List<String> changedTypes) {
        if (changedTypes == null || changedTypes.isEmpty()) {
            return new BlastRadiusResult(List.of(), List.of());
        }

        // Deduplicate and filter out nulls/blanks while preserving input order
        List<String> cleanChanged = changedTypes.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();

        if (cleanChanged.isEmpty()) {
            return new BlastRadiusResult(List.of(), List.of());
        }

        if (dependencies == null || dependencies.isEmpty()) {
            return new BlastRadiusResult(cleanChanged, List.of());
        }

        // Build reverse dependency graph: targetType -> Set of sourceTypes (dependents)
        Map<String, Set<String>> dependentsByTarget = new HashMap<>();
        for (TypeDependency dep : dependencies) {
            if (dep.sourceType() != null && dep.targetType() != null) {
                dependentsByTarget
                    .computeIfAbsent(dep.targetType(), k -> new TreeSet<>())
                    .add(dep.sourceType());
            }
        }

        // Set of explicitly changed types
        Set<String> changedSet = new HashSet<>(cleanChanged);

        // BFS traversal tracking shortest distance
        Map<String, Integer> shortestDistance = new HashMap<>();
        Queue<QueueNode> queue = new ArrayDeque<>();

        // Initialize queue with all changed types at distance 0
        for (String changed : cleanChanged) {
            queue.add(new QueueNode(changed, 0));
        }

        while (!queue.isEmpty()) {
            QueueNode current = queue.poll();
            String currentType = current.typeName();
            int currentDistance = current.distance();

            Set<String> directDependents = dependentsByTarget.getOrDefault(currentType, Collections.emptySet());

            for (String dependent : directDependents) {
                // If the dependent is one of the explicitly changed types, do not mark as affected
                if (changedSet.contains(dependent)) {
                    continue;
                }

                int nextDistance = currentDistance + 1;
                // If not visited yet, shortest path in unweighted graph is guaranteed by BFS
                if (!shortestDistance.containsKey(dependent)) {
                    shortestDistance.put(dependent, nextDistance);
                    queue.add(new QueueNode(dependent, nextDistance));
                }
            }
        }

        // Build deterministic output: sort by distance ascending, then by typeName alphabetically
        List<AffectedType> affectedTypes = shortestDistance.entrySet().stream()
            .map(entry -> new AffectedType(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(AffectedType::distance).thenComparing(AffectedType::typeName))
            .toList();

        return new BlastRadiusResult(cleanChanged, affectedTypes);
    }

    private record QueueNode(String typeName, int distance) {}
}
