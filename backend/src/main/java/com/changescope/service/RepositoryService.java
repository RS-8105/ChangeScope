package com.changescope.service;

import com.changescope.dto.CreateRepositoryRequest;
import com.changescope.dto.RepositoryResponse;
import com.changescope.exception.DuplicateResourceException;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class RepositoryService {

    private final RepositoryEntityRepository repositoryEntityRepository;

    public RepositoryService(RepositoryEntityRepository repositoryEntityRepository) {
        this.repositoryEntityRepository = repositoryEntityRepository;
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
        if (!repositoryEntityRepository.existsById(id)) {
            throw new ResourceNotFoundException("Repository not found with id: " + id);
        }
        repositoryEntityRepository.deleteById(id);
    }
}
