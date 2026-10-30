import apiClient from './apiClient.js'

/**
 * Service for triggering PR blast-radius analyses and retrieving historical impact reports.
 */
export const impactAnalysisService = {
  /**
   * Triggers a synchronous AST & graph-overlay impact analysis for a specific PR.
   * POST /api/v1/repositories/{id}/pulls/{number}/analyze
   * @param {number|string} repositoryId
   * @param {number|string} pullNumber
   * @param {AbortSignal} [signal] - Optional AbortSignal for request cancellation
   * @returns {Promise<PullRequestImpactResult>}
   */
  async analyzePullRequest(repositoryId, pullNumber, signal = null) {
    const response = await apiClient.post(
      `/api/v1/repositories/${repositoryId}/pulls/${pullNumber}/analyze`,
      null,
      {
        signal: signal || undefined,
      }
    )
    return response.data
  },

  /**
   * Retrieves a paginated list of historical analysis summaries for a repository.
   * GET /api/v1/repositories/{id}/analyses?page={page}&size={size}
   * @param {number|string} repositoryId
   * @param {number} page
   * @param {number} size
   * @param {AbortSignal} [signal] - Optional AbortSignal for request cancellation
   * @returns {Promise<{content: Array<AnalysisSummaryResponse>, totalElements: number, totalPages: number, number: number, size: number}>}
   */
  async getRepositoryAnalyses(repositoryId, page = 0, size = 20, signal = null) {
    const response = await apiClient.get(
      `/api/v1/repositories/${repositoryId}/analyses`,
      {
        params: { page, size },
        signal: signal || undefined,
      }
    )
    return response.data
  },

  /**
   * Retrieves a previously persisted full PR impact analysis report snapshot.
   * GET /api/v1/repositories/{id}/analyses/{analysisId}
   * @param {number|string} repositoryId
   * @param {number|string} analysisId
   * @param {AbortSignal} [signal] - Optional AbortSignal for request cancellation
   * @returns {Promise<PullRequestImpactResult>}
   */
  async getAnalysisSnapshot(repositoryId, analysisId, signal = null) {
    const response = await apiClient.get(
      `/api/v1/repositories/${repositoryId}/analyses/${analysisId}`,
      {
        signal: signal || undefined,
      }
    )
    return response.data
  },
}

export default impactAnalysisService
