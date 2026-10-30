import apiClient from './apiClient.js'

/**
 * Service for repository CRUD and analysis query operations against ChangeScope backend.
 */
export const repositoryService = {
  /**
   * Fetches all registered repositories.
   * GET /api/v1/repositories
   * @returns {Promise<Array<{id: number, owner: string, name: string, fullName: string, defaultBranch: string, lastIndexedCommit: string|null, createdAt: string, updatedAt: string}>>}
   */
  async getAllRepositories() {
    const response = await apiClient.get('/api/v1/repositories')
    return Array.isArray(response.data) ? response.data : []
  },

  /**
   * Fetches a single repository by its numeric ID.
   * GET /api/v1/repositories/{id}
   * @param {number|string} id
   */
  async getRepositoryById(id) {
    const response = await apiClient.get(`/api/v1/repositories/${id}`)
    return response.data
  },

  /**
   * Registers a new repository in ChangeScope.
   * POST /api/v1/repositories
   * Payload maps strictly to CreateRepositoryRequest DTO:
   * { owner: string, name: string, defaultBranch?: string, installationId?: number, githubId?: number }
   * @param {Object} repoData
   */
  async createRepository(repoData) {
    const payload = {
      owner: repoData.owner.trim(),
      name: repoData.name.trim(),
      defaultBranch: (repoData.defaultBranch || 'main').trim(),
    }

    if (repoData.installationId && Number(repoData.installationId) > 0) {
      payload.installationId = Number(repoData.installationId)
    }

    if (repoData.githubId && Number(repoData.githubId) > 0) {
      payload.githubId = Number(repoData.githubId)
    }

    const response = await apiClient.post('/api/v1/repositories', payload)
    return response.data
  },

  /**
   * Deletes a repository by ID.
   * DELETE /api/v1/repositories/{id}
   * @param {number|string} id
   */
  async deleteRepository(id) {
    await apiClient.delete(`/api/v1/repositories/${id}`)
    return true
  },

  /**
   * Deletes all historical analysis records for a repository.
   * DELETE /api/v1/repositories/{id}/analyses
   * @param {number|string} id
   * @returns {Promise<{repositoryId: number, deletedCount: number, count: number}>}
   */
  async deleteRepositoryAnalyses(id) {
    const response = await apiClient.delete(`/api/v1/repositories/${id}/analyses`)
    return response.data
  },

  /**
   * Deletes the dependency graph index and resets last indexed commit for a repository.
   * DELETE /api/v1/repositories/{id}/index
   * @param {number|string} id
   * @returns {Promise<boolean>}
   */
  async deleteRepositoryIndex(id) {
    await apiClient.delete(`/api/v1/repositories/${id}/index`)
    return true
  },

  /**
   * Fetches paginated historical analyses for a repository.
   * GET /api/v1/repositories/{id}/analyses?page={page}&size={size}
   * @param {number|string} id
   * @param {number} page
   * @param {number} size
   */
  async getRepositoryAnalyses(id, page = 0, size = 10) {
    const response = await apiClient.get(`/api/v1/repositories/${id}/analyses`, {
      params: { page, size },
    })
    return response.data
  },
}

export default repositoryService
