package com.changescope.service;

import com.changescope.dto.CreateRepositoryRequest;
import com.changescope.dto.RepositoryResponse;
import com.changescope.exception.DuplicateResourceException;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepositoryServiceTest {

    @Mock
    private RepositoryEntityRepository repositoryEntityRepository;

    @InjectMocks
    private RepositoryService repositoryService;

    private RepositoryEntity sampleEntity;

    @BeforeEach
    void setUp() {
        sampleEntity = new RepositoryEntity("changescope-org", "order-service", "main", 101L);
        sampleEntity.setId(1L);
        sampleEntity.setCreatedAt(Instant.now());
        sampleEntity.setUpdatedAt(Instant.now());
    }

    @Test
    void createRepository_success() {
        CreateRepositoryRequest request = new CreateRepositoryRequest(101L, "changescope-org", "order-service", "main");
        when(repositoryEntityRepository.existsByOwnerAndName("changescope-org", "order-service")).thenReturn(false);
        when(repositoryEntityRepository.save(any(RepositoryEntity.class))).thenReturn(sampleEntity);

        RepositoryResponse response = repositoryService.createRepository(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.owner()).isEqualTo("changescope-org");
        assertThat(response.name()).isEqualTo("order-service");
        assertThat(response.fullName()).isEqualTo("changescope-org/order-service");
        assertThat(response.defaultBranch()).isEqualTo("main");
        assertThat(response.installationId()).isNull();
        verify(repositoryEntityRepository).save(any(RepositoryEntity.class));
    }

    @Test
    void createRepository_withInstallationId_persistsDistinctFromGithubId() {
        CreateRepositoryRequest request = new CreateRepositoryRequest(101L, 9999L, "changescope-org", "order-service", "main");
        RepositoryEntity entityWithInst = new RepositoryEntity("changescope-org", "order-service", "main", 101L, 9999L);
        entityWithInst.setId(1L);
        entityWithInst.setCreatedAt(Instant.now());
        entityWithInst.setUpdatedAt(Instant.now());

        when(repositoryEntityRepository.existsByOwnerAndName("changescope-org", "order-service")).thenReturn(false);
        when(repositoryEntityRepository.save(any(RepositoryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RepositoryResponse response = repositoryService.createRepository(request);

        assertThat(response.githubId()).isEqualTo(101L);
        assertThat(response.installationId()).isEqualTo(9999L);
        assertThat(response.installationId()).isNotEqualTo(response.githubId());
    }

    @Test
    void createRepository_invalidInstallationId_failsValidation() {
        CreateRepositoryRequest reqNegative = new CreateRepositoryRequest(101L, -5L, "changescope-org", "order-service", "main");
        assertThatThrownBy(() -> repositoryService.createRepository(reqNegative))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Installation ID must be a positive integer");

        CreateRepositoryRequest reqZero = new CreateRepositoryRequest(101L, 0L, "changescope-org", "order-service", "main");
        assertThatThrownBy(() -> repositoryService.createRepository(reqZero))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Installation ID must be a positive integer");
    }

    @Test
    void createRepository_invalidGithubId_failsValidation() {
        CreateRepositoryRequest req = new CreateRepositoryRequest(-10L, 9999L, "changescope-org", "order-service", "main");
        assertThatThrownBy(() -> repositoryService.createRepository(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GitHub repository ID must be a positive integer");
    }

    @Test
    void createRepository_duplicate_throwsDuplicateResourceException() {
        CreateRepositoryRequest request = new CreateRepositoryRequest(101L, "changescope-org", "order-service", "main");
        when(repositoryEntityRepository.existsByOwnerAndName("changescope-org", "order-service")).thenReturn(true);

        assertThatThrownBy(() -> repositoryService.createRepository(request))
            .isInstanceOf(DuplicateResourceException.class)
            .hasMessageContaining("already exists");

        verify(repositoryEntityRepository, never()).save(any());
    }

    @Test
    void getAllRepositories_returnsMappedList() {
        when(repositoryEntityRepository.findAll()).thenReturn(List.of(sampleEntity));

        List<RepositoryResponse> list = repositoryService.getAllRepositories();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("order-service");
    }

    @Test
    void getRepositoryById_found_returnsResponse() {
        when(repositoryEntityRepository.findById(1L)).thenReturn(Optional.of(sampleEntity));

        RepositoryResponse response = repositoryService.getRepositoryById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.fullName()).isEqualTo("changescope-org/order-service");
    }

    @Test
    void getRepositoryById_notFound_throwsResourceNotFoundException() {
        when(repositoryEntityRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryService.getRepositoryById(999L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found with id: 999");
    }

    @Test
    void deleteRepository_success() {
        when(repositoryEntityRepository.existsById(1L)).thenReturn(true);
        doNothing().when(repositoryEntityRepository).deleteById(1L);

        repositoryService.deleteRepository(1L);

        verify(repositoryEntityRepository).deleteById(1L);
    }

    @Test
    void deleteRepository_notFound_throwsResourceNotFoundException() {
        when(repositoryEntityRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> repositoryService.deleteRepository(999L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found with id: 999");

        verify(repositoryEntityRepository, never()).deleteById(any());
    }
}
