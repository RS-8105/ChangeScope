package com.changescope.controller;

import com.changescope.dto.CreateRepositoryRequest;
import com.changescope.dto.RepositoryResponse;
import com.changescope.exception.DuplicateResourceException;
import com.changescope.exception.GlobalExceptionHandler;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.service.RepositoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RepositoryController.class)
@Import(GlobalExceptionHandler.class)
class RepositoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RepositoryService repositoryService;

    @MockBean
    private com.changescope.service.github.PullRequestImpactService pullRequestImpactService;

    @MockBean
    private com.changescope.service.PullRequestAnalysisService pullRequestAnalysisService;

    @Test
    void createRepository_validRequest_returns201Created() throws Exception {
        CreateRepositoryRequest request = new CreateRepositoryRequest(101L, "changescope-org", "order-service", "main");
        RepositoryResponse response = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service", "changescope-org/order-service", "main", null, Instant.now(), Instant.now()
        );

        when(repositoryService.createRepository(any(CreateRepositoryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/repositories/1")))
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.fullName").value("changescope-org/order-service"))
            .andExpect(jsonPath("$.defaultBranch").value("main"));
    }

    @Test
    void createRepository_withInstallationId_returns201CreatedWithInstallationId() throws Exception {
        CreateRepositoryRequest request = new CreateRepositoryRequest(101L, 5555L, "changescope-org", "order-service", "main");
        RepositoryResponse response = new RepositoryResponse(
            1L, 101L, 5555L, "changescope-org", "order-service", "changescope-org/order-service", "main", null, Instant.now(), Instant.now()
        );

        when(repositoryService.createRepository(any(CreateRepositoryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.githubId").value(101))
            .andExpect(jsonPath("$.installationId").value(5555));
    }

    @Test
    void createRepository_negativeInstallationId_returns400BadRequest() throws Exception {
        String invalidPayload = """
            {
              "githubId": 101,
              "installationId": -5,
              "owner": "changescope-org",
              "name": "order-service",
              "defaultBranch": "main"
            }
            """;

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(repositoryService, never()).createRepository(any());
    }

    @Test
    void createRepository_blankOwnerOrName_returns400BadRequest() throws Exception {
        CreateRepositoryRequest request = new CreateRepositoryRequest(null, "", "", "main");

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray());

        verify(repositoryService, never()).createRepository(any());
    }

    @Test
    void createRepository_invalidCharacters_returns400BadRequest() throws Exception {
        CreateRepositoryRequest request = new CreateRepositoryRequest(null, "invalid owner spaces", "valid-name", "main");

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(repositoryService, never()).createRepository(any());
    }

    @Test
    void createRepository_duplicate_returns409Conflict() throws Exception {
        CreateRepositoryRequest request = new CreateRepositoryRequest(null, "changescope-org", "order-service", "main");
        when(repositoryService.createRepository(any(CreateRepositoryRequest.class)))
            .thenThrow(new DuplicateResourceException("Repository already exists with owner 'changescope-org' and name 'order-service'"));

        mockMvc.perform(post("/api/v1/repositories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void getAllRepositories_returns200WithList() throws Exception {
        RepositoryResponse response = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service", "changescope-org/order-service", "main", null, Instant.now(), Instant.now()
        );
        when(repositoryService.getAllRepositories()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/repositories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].name").value("order-service"));
    }

    @Test
    void getRepositoryById_found_returns200() throws Exception {
        RepositoryResponse response = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service", "changescope-org/order-service", "main", null, Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/repositories/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.fullName").value("changescope-org/order-service"));
    }

    @Test
    void getRepositoryById_notFound_returns404() throws Exception {
        when(repositoryService.getRepositoryById(999L))
            .thenThrow(new ResourceNotFoundException("Repository not found with id: 999"));

        mockMvc.perform(get("/api/v1/repositories/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void deleteRepository_success_returns204NoContent() throws Exception {
        doNothing().when(repositoryService).deleteRepository(1L);

        mockMvc.perform(delete("/api/v1/repositories/1"))
            .andExpect(status().isNoContent());

        verify(repositoryService).deleteRepository(1L);
    }

    @Test
    void deleteRepository_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Repository not found with id: 999"))
            .when(repositoryService).deleteRepository(999L);

        mockMvc.perform(delete("/api/v1/repositories/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
