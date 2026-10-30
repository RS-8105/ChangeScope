import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000, // 60-second request timeout for long-running AST analysis
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  },
})

// Global response interceptor for standardized error formatting
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isCancel(error) || error.name === 'CanceledError' || error.code === 'ERR_CANCELED') {
      error.isCanceled = true
      return Promise.reject(error)
    }

    const errorData = error.response?.data
    const message =
      (errorData && typeof errorData === 'object' && (errorData.message || errorData.error)) ||
      error.message ||
      'An unexpected error occurred'

    error.formattedMessage = message
    error.status = error.response?.status
    return Promise.reject(error)
  }
)

export default apiClient
