package com.changescope.service;

import com.changescope.dto.BaseGraphSnapshot;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RepositoryIndexLoader {

    private final RepositoryEntityRepository repositoryEntityRepository;
    private final RepositoryIndexRepository repositoryIndexRepository;
    private final IndexedTypeRepository indexedTypeRepository;
    private final IndexedTypeDependencyRepository indexedTypeDependencyRepository;

    public RepositoryIndexLoader(
        RepositoryEntityRepository repositoryEntityRepository,
        RepositoryIndexRepository repositoryIndexRepository,
        IndexedTypeRepository indexedTypeRepository,
        IndexedTypeDependencyRepository indexedTypeDependencyRepository
    ) {
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.repositoryIndexRepository = repositoryIndexRepository;
        this.indexedTypeRepository = indexedTypeRepository;
        this.indexedTypeDependencyRepository = indexedTypeDependencyRepository;
    }

    @Transactional(readOnly = true)
    public RepositoryIndexLoadResult loadIndex(String owner, String repo, String baseCommitSha) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank() || baseCommitSha == null || baseCommitSha.isBlank()) {
            return new RepositoryIndexLoadResult.NotFound(
                baseCommitSha != null ? baseCommitSha.trim() : null,
                "Repository owner, name, and base commit SHA must not be blank"
            );
        }

        String cleanBaseSha = baseCommitSha.trim();
        Optional<RepositoryEntity> repoOpt = repositoryEntityRepository.findByOwnerAndName(owner.trim(), repo.trim());
        if (repoOpt.isEmpty()) {
            return new RepositoryIndexLoadResult.NotFound(
                cleanBaseSha,
                "Repository " + owner + "/" + repo + " is not registered"
            );
        }

        RepositoryEntity repository = repoOpt.get();
        // NEVER fall back to repository.getLastIndexedCommit() or another commit SHA
        Optional<RepositoryIndexEntity> indexOpt = repositoryIndexRepository.findByRepositoryIdAndCommitSha(
            repository.getId(),
            cleanBaseSha
        );

        if (indexOpt.isEmpty()) {
            return new RepositoryIndexLoadResult.NotFound(
                cleanBaseSha,
                "No persistent index found for base commit " + cleanBaseSha
            );
        }

        RepositoryIndexEntity index = indexOpt.get();
        RepositoryIndexStatus status = index.getStatus();

        if (status == RepositoryIndexStatus.PENDING || status == RepositoryIndexStatus.INDEXING) {
            return new RepositoryIndexLoadResult.Unusable(
                cleanBaseSha,
                status,
                "Repository index for commit " + cleanBaseSha + " is currently in state " + status
            );
        }

        if (status == RepositoryIndexStatus.FAILED) {
            return new RepositoryIndexLoadResult.Unusable(
                cleanBaseSha,
                status,
                "Repository index for commit " + cleanBaseSha + " failed: "
                    + (index.getFailureReason() != null ? index.getFailureReason() : "unknown failure")
            );
        }

        if (status == RepositoryIndexStatus.COMPLETED || status == RepositoryIndexStatus.PARTIAL) {
            // Load indexed types and dependencies using existing repository query methods,
            // avoiding lazy collection traversal and N+1 queries
            List<IndexedTypeEntity> typeEntities = indexedTypeRepository.findByIndexId(index.getId());
            List<IndexedTypeDependencyEntity> depEntities = indexedTypeDependencyRepository.findByIndexId(index.getId());

            BaseGraphSnapshot snapshot = BaseGraphSnapshot.fromEntities(typeEntities, depEntities);

            return new RepositoryIndexLoadResult.Usable(
                cleanBaseSha,
                status,
                index.isTruncatedOrCapped(),
                index.getTotalFilesDiscovered(),
                index.getTotalFilesIndexed(),
                snapshot
            );
        }

        return new RepositoryIndexLoadResult.Unusable(
            cleanBaseSha,
            status,
            "Repository index for commit " + cleanBaseSha + " is in unexpected state " + status
        );
    }
}
