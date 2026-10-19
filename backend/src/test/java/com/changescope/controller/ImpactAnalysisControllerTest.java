package com.changescope.controller;

import com.changescope.dto.AffectedType;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactAnalysisRequest;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.SourceFileDto;
import com.changescope.dto.TypeDependency;
import com.changescope.exception.GlobalExceptionHandler;
import com.changescope.service.ImpactReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ImpactAnalysisController.class)
@Import(GlobalExceptionHandler.class)
class ImpactAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ImpactReportService impactReportService;

    @Test
    void analyzeImpact_successfulAnalysis_returns200AndStructuredReport() throws Exception {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest(
            List.of(new SourceFileDto("OrderService.java", "public class OrderService {}")),
            List.of("com.changescope.payment.PaymentGateway")
        );

        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.payment.PaymentGateway"),
            List.of(new AffectedType("com.changescope.order.OrderService", 1)),
            List.of(new TypeDependency(
                "com.changescope.order.OrderService",
                "com.changescope.payment.PaymentGateway",
                RelationshipKind.FIELD,
                "OrderService.java"
            )),
            List.of()
        );

        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changedTypes[0]").value("com.changescope.payment.PaymentGateway"))
            .andExpect(jsonPath("$.totalChangedTypes").value(1))
            .andExpect(jsonPath("$.affectedTypes[0].typeName").value("com.changescope.order.OrderService"))
            .andExpect(jsonPath("$.affectedTypes[0].distance").value(1))
            .andExpect(jsonPath("$.totalAffectedTypes").value(1))
            .andExpect(jsonPath("$.dependencies[0].sourceType").value("com.changescope.order.OrderService"))
            .andExpect(jsonPath("$.dependencies[0].targetType").value("com.changescope.payment.PaymentGateway"))
            .andExpect(jsonPath("$.dependencies[0].kind").value("FIELD"))
            .andExpect(jsonPath("$.parseErrors").isEmpty());

        verify(impactReportService).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_transitiveImpact_returnsMultiHopAffectedTypes() throws Exception {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest(
            List.of(new SourceFileDto("OrderService.java", "class OrderService {}")),
            List.of("com.changescope.order.OrderRepository")
        );

        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.order.OrderRepository"),
            List.of(
                new AffectedType("com.changescope.order.OrderService", 1),
                new AffectedType("com.changescope.order.OrderController", 2)
            ),
            List.of(
                new TypeDependency("com.changescope.order.OrderService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "OrderService.java"),
                new TypeDependency("com.changescope.order.OrderController", "com.changescope.order.OrderService", RelationshipKind.FIELD, "OrderController.java")
            ),
            List.of()
        );

        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAffectedTypes").value(2))
            .andExpect(jsonPath("$.affectedTypes[0].typeName").value("com.changescope.order.OrderService"))
            .andExpect(jsonPath("$.affectedTypes[0].distance").value(1))
            .andExpect(jsonPath("$.affectedTypes[1].typeName").value("com.changescope.order.OrderController"))
            .andExpect(jsonPath("$.affectedTypes[1].distance").value(2))
            .andExpect(jsonPath("$.dependencies.length()").value(2));
    }

    @Test
    void analyzeImpact_partialParsingFailures_returnsErrorsAlongWithValidImpact() throws Exception {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest(
            List.of(
                new SourceFileDto("Valid.java", "class Valid {}"),
                new SourceFileDto("Broken.java", "class Broken {")
            ),
            List.of("com.changescope.order.OrderRepository")
        );

        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.order.OrderRepository"),
            List.of(new AffectedType("com.changescope.order.ValidService", 1)),
            List.of(new TypeDependency("com.changescope.order.ValidService", "com.changescope.order.OrderRepository", RelationshipKind.FIELD, "Valid.java")),
            List.of(new FileParseError("Broken.java", "Syntax error: premature end of file"))
        );

        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parseErrors[0].filePath").value("Broken.java"))
            .andExpect(jsonPath("$.parseErrors[0].errorMessage").value("Syntax error: premature end of file"))
            .andExpect(jsonPath("$.affectedTypes[0].typeName").value("com.changescope.order.ValidService"))
            .andExpect(jsonPath("$.totalAffectedTypes").value(1));
    }

    @Test
    void analyzeImpact_missingFilesField_returns400BadRequest() throws Exception {
        String invalidJson = """
            {
                "changedTypes": ["com.changescope.order.OrderRepository"]
            }
            """;

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray());

        verify(impactReportService, never()).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_missingChangedTypesField_returns400BadRequest() throws Exception {
        String invalidJson = """
            {
                "files": [
                    { "filePath": "Valid.java", "sourceCode": "class Valid {}" }
                ]
            }
            """;

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(impactReportService, never()).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_blankFilePathInFileItem_returns400BadRequest() throws Exception {
        String invalidJson = """
            {
                "files": [
                    { "filePath": "   ", "sourceCode": "class Valid {}" }
                ],
                "changedTypes": ["com.changescope.order.OrderRepository"]
            }
            """;

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(impactReportService, never()).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_nullSourceCodeInFileItem_returns400BadRequest() throws Exception {
        String invalidJson = """
            {
                "files": [
                    { "filePath": "Valid.java", "sourceCode": null }
                ],
                "changedTypes": ["com.changescope.order.OrderRepository"]
            }
            """;

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(impactReportService, never()).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_malformedJson_returns400BadRequest() throws Exception {
        String malformedJson = "{ broken json syntax ";

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("MALFORMED_JSON"));

        verify(impactReportService, never()).generateReport(anyList(), anyList());
    }

    @Test
    void analyzeImpact_emptyArrays_acceptedAndReturns200() throws Exception {
        ImpactAnalysisRequest request = new ImpactAnalysisRequest(List.of(), List.of());
        ImpactReport emptyReport = new ImpactReport(List.of(), List.of(), List.of(), List.of());

        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(emptyReport);

        mockMvc.perform(post("/api/v1/analysis/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalChangedTypes").value(0))
            .andExpect(jsonPath("$.totalAffectedTypes").value(0))
            .andExpect(jsonPath("$.changedTypes").isEmpty())
            .andExpect(jsonPath("$.affectedTypes").isEmpty());
    }
}
