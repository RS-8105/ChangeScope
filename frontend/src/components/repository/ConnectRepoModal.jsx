import { useState, useEffect } from 'react'
import { X, Plus, AlertCircle, CheckCircle2, GitBranch } from 'lucide-react'
import repositoryService from '../../services/repositoryService.js'

const REPO_REGEX = /^[a-zA-Z0-9_.-]+$/

export default function ConnectRepoModal({ isOpen, onClose, onRepositoryCreated }) {
  const [formData, setFormData] = useState({
    owner: '',
    name: '',
    defaultBranch: 'main',
    installationId: '',
    githubId: '',
  })

  const [errors, setErrors] = useState({})
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [apiError, setApiError] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)

  // Reset form when opened or closed
  useEffect(() => {
    if (isOpen) {
      setFormData({
        owner: '',
        name: '',
        defaultBranch: 'main',
        installationId: '',
        githubId: '',
      })
      setErrors({})
      setApiError(null)
      setSuccessMessage(null)
    }
  }, [isOpen])

  // Close on Escape key
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && isOpen && !isSubmitting) {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, isSubmitting, onClose])

  if (!isOpen) return null

  const validate = () => {
    const newErrors = {}

    if (!formData.owner.trim()) {
      newErrors.owner = 'Repository owner is required'
    } else if (formData.owner.length > 100) {
      newErrors.owner = 'Owner cannot exceed 100 characters'
    } else if (!REPO_REGEX.test(formData.owner.trim())) {
      newErrors.owner = 'Only alphanumeric characters, underscores, dots, and hyphens allowed'
    }

    if (!formData.name.trim()) {
      newErrors.name = 'Repository name is required'
    } else if (formData.name.length > 100) {
      newErrors.name = 'Repository name cannot exceed 100 characters'
    } else if (!REPO_REGEX.test(formData.name.trim())) {
      newErrors.name = 'Only alphanumeric characters, underscores, dots, and hyphens allowed'
    }

    if (formData.defaultBranch && formData.defaultBranch.length > 100) {
      newErrors.defaultBranch = 'Default branch cannot exceed 100 characters'
    }

    if (formData.installationId && (!Number.isInteger(Number(formData.installationId)) || Number(formData.installationId) <= 0)) {
      newErrors.installationId = 'Installation ID must be a positive integer'
    }

    if (formData.githubId && (!Number.isInteger(Number(formData.githubId)) || Number(formData.githubId) <= 0)) {
      newErrors.githubId = 'GitHub ID must be a positive integer'
    }

    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setApiError(null)
    setSuccessMessage(null)

    if (!validate()) return

    setIsSubmitting(true)

    try {
      const created = await repositoryService.createRepository({
        owner: formData.owner,
        name: formData.name,
        defaultBranch: formData.defaultBranch || 'main',
        installationId: formData.installationId ? Number(formData.installationId) : undefined,
        githubId: formData.githubId ? Number(formData.githubId) : undefined,
      })

      setSuccessMessage(`Repository ${created.owner}/${created.name} registered successfully!`)

      setTimeout(() => {
        if (onRepositoryCreated) {
          onRepositoryCreated(created)
        }
        onClose()
      }, 700)
    } catch (err) {
      if (err.status === 409) {
        setApiError(`A repository with owner "${formData.owner}" and name "${formData.name}" is already registered (409 Conflict).`)
      } else if (err.status === 400) {
        setApiError(err.formattedMessage || 'Validation error: please verify your input fields.')
      } else {
        setApiError(err.formattedMessage || 'Failed to connect repository. Please verify backend availability.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs">
      <div
        className="bg-[#242424] border border-[#303030] rounded-lg w-full max-w-md overflow-hidden shadow-2xl animate-in fade-in zoom-in-95 duration-150"
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
      >
        {/* Header */}
        <div className="px-5 py-4 border-b border-[#303030] flex items-center justify-between bg-[#1C1C1C]">
          <div>
            <h2 id="modal-title" className="text-sm font-semibold text-[#D4D4D4] flex items-center gap-2">
              <Plus className="w-4 h-4 text-[#A78BFA]" />
              Connect Repository
            </h2>
            <p className="text-[11px] text-[#858585] mt-0.5">
              Register a GitHub repository for AST blast-radius indexing
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="text-[#858585] hover:text-[#D4D4D4] p-1 rounded hover:bg-[#2A2A2A] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Form Body */}
        <form onSubmit={handleSubmit} className="p-5 space-y-4">
          {/* API Error Alert */}
          {apiError && (
            <div className="p-3 bg-[#FFB4AB]/10 border border-[#FFB4AB]/30 rounded text-xs text-[#FFB4AB] flex items-start gap-2">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
              <div className="flex-1">{apiError}</div>
            </div>
          )}

          {/* Success Banner */}
          {successMessage && (
            <div className="p-3 bg-[#45DFA4]/10 border border-[#45DFA4]/30 rounded text-xs text-[#45DFA4] flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 shrink-0" />
              <span>{successMessage}</span>
            </div>
          )}

          {/* Owner & Name (2 Column) */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-[11px] font-medium uppercase tracking-wider text-[#858585] mb-1">
                Owner <span className="text-[#FFB4AB]">*</span>
              </label>
              <input
                type="text"
                placeholder="e.g. changescope"
                value={formData.owner}
                onChange={(e) => setFormData({ ...formData, owner: e.target.value })}
                disabled={isSubmitting}
                className={`w-full px-2.5 py-1.5 bg-[#1C1C1C] border ${
                  errors.owner ? 'border-[#FFB4AB]' : 'border-[#303030]'
                } focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors`}
              />
              {errors.owner && (
                <p className="text-[10px] text-[#FFB4AB] mt-1 leading-tight">{errors.owner}</p>
              )}
            </div>

            <div>
              <label className="block text-[11px] font-medium uppercase tracking-wider text-[#858585] mb-1">
                Repository <span className="text-[#FFB4AB]">*</span>
              </label>
              <input
                type="text"
                placeholder="e.g. order-service"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                disabled={isSubmitting}
                className={`w-full px-2.5 py-1.5 bg-[#1C1C1C] border ${
                  errors.name ? 'border-[#FFB4AB]' : 'border-[#303030]'
                } focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors`}
              />
              {errors.name && (
                <p className="text-[10px] text-[#FFB4AB] mt-1 leading-tight">{errors.name}</p>
              )}
            </div>
          </div>

          {/* Default Branch */}
          <div>
            <label className="block text-[11px] font-medium uppercase tracking-wider text-[#858585] mb-1">
              Default Branch
            </label>
            <div className="relative">
              <GitBranch className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-[#5A5A5A]" />
              <input
                type="text"
                placeholder="main"
                value={formData.defaultBranch}
                onChange={(e) => setFormData({ ...formData, defaultBranch: e.target.value })}
                disabled={isSubmitting}
                className={`w-full pl-8 pr-2.5 py-1.5 bg-[#1C1C1C] border ${
                  errors.defaultBranch ? 'border-[#FFB4AB]' : 'border-[#303030]'
                } focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden transition-colors`}
              />
            </div>
            {errors.defaultBranch && (
              <p className="text-[10px] text-[#FFB4AB] mt-1">{errors.defaultBranch}</p>
            )}
            <p className="text-[10px] text-[#5A5A5A] mt-1">
              Push webhooks against this branch trigger full AST dependency indexing.
            </p>
          </div>

          {/* Optional GitHub IDs Section */}
          <div className="pt-2 border-t border-[#2A2A2A]">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-[#5A5A5A] block mb-2">
              Authentication & Metadata (Optional)
            </span>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-[10px] text-[#858585] mb-1">
                  Installation ID
                </label>
                <input
                  type="number"
                  min="1"
                  placeholder="e.g. 54321987"
                  value={formData.installationId}
                  onChange={(e) => setFormData({ ...formData, installationId: e.target.value })}
                  disabled={isSubmitting}
                  className="w-full px-2.5 py-1 bg-[#1C1C1C] border border-[#303030] focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden"
                />
                {errors.installationId && (
                  <p className="text-[10px] text-[#FFB4AB] mt-1">{errors.installationId}</p>
                )}
              </div>

              <div>
                <label className="block text-[10px] text-[#858585] mb-1">
                  GitHub ID
                </label>
                <input
                  type="number"
                  min="1"
                  placeholder="e.g. 98765432"
                  value={formData.githubId}
                  onChange={(e) => setFormData({ ...formData, githubId: e.target.value })}
                  disabled={isSubmitting}
                  className="w-full px-2.5 py-1 bg-[#1C1C1C] border border-[#303030] focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden"
                />
                {errors.githubId && (
                  <p className="text-[10px] text-[#FFB4AB] mt-1">{errors.githubId}</p>
                )}
              </div>
            </div>
          </div>

          {/* Action Buttons */}
          <div className="pt-3 border-t border-[#303030] flex items-center justify-end gap-2.5">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="px-3 py-1.5 bg-[#242424] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] text-xs font-medium rounded transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] text-xs font-semibold rounded transition-colors disabled:opacity-50"
            >
              {isSubmitting ? (
                <>
                  <span className="w-3.5 h-3.5 border-2 border-[#121212] border-t-transparent rounded-full animate-spin" />
                  Connecting...
                </>
              ) : (
                'Connect Repository'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
