const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'

/**
 * Helper to execute a fetch request with timeout and standardized error handling.
 */
async function request(endpoint, options = {}, timeoutMs = 5000) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs)

  try {
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      ...options,
      headers: {
        Accept: 'application/json',
        ...(options.headers || {}),
      },
      signal: controller.signal,
    })

    if (response.status === 204) {
      return null
    }

    const contentType = response.headers.get('content-type') || ''
    const isJson = contentType.includes('application/json')
    const data = isJson ? await response.json() : await response.text()

    if (!response.ok) {
      const errorMsg =
        (data && typeof data === 'object' && data.message) ||
        (data && typeof data === 'object' && data.error) ||
        `HTTP ${response.status}: ${response.statusText || 'Request failed'}`
      const err = new Error(errorMsg)
      err.status = response.status
      err.details = data && typeof data === 'object' && data.details ? data.details : []
      throw err
    }

    return data
  } finally {
    clearTimeout(timeoutId)
  }
}

/**
 * Fetches the backend application status metadata from /api/v1/status.
 */
export async function fetchBackendStatus() {
  return request('/api/v1/status', { method: 'GET' }, 4000)
}

/**
 * Fetches all registered repositories from /api/v1/repositories.
 */
export async function fetchRepositories() {
  return request('/api/v1/repositories', { method: 'GET' })
}

/**
 * Creates a new repository record via POST /api/v1/repositories.
 */
export async function createRepository(repoData) {
  return request('/api/v1/repositories', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(repoData),
  })
}

/**
 * Retrieves a repository by ID via GET /api/v1/repositories/{id}.
 */
export async function fetchRepositoryById(id) {
  return request(`/api/v1/repositories/${id}`, { method: 'GET' })
}

/**
 * Deletes a repository by ID via DELETE /api/v1/repositories/{id}.
 */
export async function deleteRepository(id) {
  return request(`/api/v1/repositories/${id}`, { method: 'DELETE' })
}

/**
 * Submits Java source files and changed types for AST & blast-radius impact analysis.
 * Calls POST /api/v1/analysis/impact.
 * @param {Object} payload - { files: [{ filePath, sourceCode }], changedTypes: string[] }
 * @returns {Promise<Object>} ImpactReport
 */
export async function analyzeImpact(payload) {
  return request('/api/v1/analysis/impact', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  }, 15000)
}

export function getApiBaseUrl() {
  return API_BASE_URL
}
