package com.changescope.service;

import com.changescope.dto.CreateRepositoryRequest;
import com.changescope.dto.RepositoryResponse;
import com.changescope.exception.DuplicateResourceException;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class RepositoryService {

    private final RepositoryEntityRepository repositoryEntityRepository;
    private final RepositoryIndexRepository repositoryIndexRepository;
    private final IndexedTypeRepository indexedTypeRepository;
    private final IndexedTypeDependencyRepository indexedTypeDependencyRepository;
    private final PullRequestAnalysisRepository pullRequestAnalysisRepository;

    public RepositoryService(
        RepositoryEntityRepository repositoryEntityRepository,
        RepositoryIndexRepository repositoryIndexRepository,
        IndexedTypeRepository indexedTypeRepository,
        IndexedTypeDependencyRepository indexedTypeDependencyRepository,
        PullRequestAnalysisRepository pullRequestAnalysisRepository
    ) {
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.repositoryIndexRepository = repositoryIndexRepository;
        this.indexedTypeRepository = indexedTypeRepository;
        this.indexedTypeDependencyRepository = indexedTypeDependencyRepository;
        this.pullRequestAnalysisRepository = pullRequestAnalysisRepository;
    }

    @Transactional
    public RepositoryResponse createRepository(CreateRepositoryRequest request) {
        String owner = request.owner().trim();
        String name = request.name().trim();

        if (repositoryEntityRepository.existsByOwnerAndName(owner, name)) {
            throw new DuplicateResourceException(
                "Repository already exists with owner '" + owner + "' and name '" + name + "'"
            );
        }

        String defaultBranch = (request.defaultBranch() != null && !request.defaultBranch().isBlank())
            ? request.defaultBranch().trim()
            : "main";

        RepositoryEntity entity = new RepositoryEntity(owner, name, defaultBranch, request.githubId(), request.installationId());
        RepositoryEntity saved = repositoryEntityRepository.save(entity);

        return RepositoryResponse.fromEntity(saved);
    }

    public List<RepositoryResponse> getAllRepositories() {
        return repositoryEntityRepository.findAll()
            .stream()
            .map(RepositoryResponse::fromEntity)
            .toList();
    }

    public RepositoryResponse getRepositoryById(Long id) {
        RepositoryEntity entity = repositoryEntityRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + id));
        return RepositoryResponse.fromEntity(entity);
    }

    @Transactional
    public void deleteRepository(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        RepositoryEntity repository = repositoryEntityRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + id));

        // 1. Delete all analysis records belonging to this repository
        pullRequestAnalysisRepository.deleteByRepositoryId(id);

        // 2. Delete all dependency indexes, types, and dependencies belonging to this repository
        deleteRepositoryIndexInternal(id);

        // 3. Delete the repository itself
        repositoryEntityRepository.delete(repository);
    }

    @Transactional
    public void deleteRepositoryIndex(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        RepositoryEntity repository = repositoryEntityRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + id));

        // 1. Delete all dependency indexes, types, and dependencies belonging to this repository
        deleteRepositoryIndexInternal(id);

        // 2. Reset the repository's indexing state and last-indexed commit
        repository.setLastIndexedCommit(null);
        repositoryEntityRepository.save(repository);
    }

    private void deleteRepositoryIndexInternal(Long repositoryId) {
        List<RepositoryIndexEntity> indices = repositoryIndexRepository.findByRepositoryId(repositoryId);
        for (RepositoryIndexEntity index : indices) {
            indexedTypeDependencyRepository.deleteByIndexId(index.getId());
            indexedTypeRepository.deleteByIndexId(index.getId());
        }
        repositoryIndexRepository.deleteAll(indices);
    }
}
