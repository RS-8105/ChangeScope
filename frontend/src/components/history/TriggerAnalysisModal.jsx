import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { X, GitPullRequest, ArrowRight, AlertCircle } from 'lucide-react'

export default function TriggerAnalysisModal({
  isOpen,
  onClose,
  repositories = [],
  initialRepoId = '',
}) {
  const navigate = useNavigate()
  const [selectedRepoId, setSelectedRepoId] = useState(initialRepoId || '')
  const [prNumber, setPrNumber] = useState('')
  const [error, setError] = useState(null)

  useEffect(() => {
    if (isOpen) {
      setSelectedRepoId(initialRepoId || (repositories[0]?.id ? String(repositories[0].id) : ''))
      setPrNumber('')
      setError(null)
    }
  }, [isOpen, initialRepoId, repositories])

  if (!isOpen) return null

  const handleSubmit = (e) => {
    e.preventDefault()
    setError(null)

    if (!selectedRepoId) {
      setError('Please select a repository')
      return
    }

    const num = parseInt(prNumber, 10)
    if (isNaN(num) || num <= 0) {
      setError('Please enter a valid positive pull request number')
      return
    }

    onClose()
    navigate(`/repositories/${selectedRepoId}/pulls/${num}`)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs">
      <div
        className="bg-[#242424] border border-[#303030] rounded-lg w-full max-w-md overflow-hidden shadow-2xl animate-in fade-in zoom-in-95 duration-150"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trigger-modal-title"
      >
        {/* Header */}
        <div className="px-5 py-4 border-b border-[#303030] flex items-center justify-between bg-[#1C1C1C]">
          <div>
            <h2 id="trigger-modal-title" className="text-sm font-semibold text-[#D4D4D4] flex items-center gap-2">
              <GitPullRequest className="w-4 h-4 text-[#A78BFA]" />
              Analyze Pull Request
            </h2>
            <p className="text-[11px] text-[#858585] mt-0.5">
              Launch AST semantic diff and blast-radius traversal on a PR
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-[#858585] hover:text-[#D4D4D4] p-1 rounded hover:bg-[#2A2A2A] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Form Body */}
        <form onSubmit={handleSubmit} className="p-5 space-y-4">
          {error && (
            <div className="p-3 bg-[#FFB4AB]/10 border border-[#FFB4AB]/30 rounded text-xs text-[#FFB4AB] flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {/* Repository Selection */}
          <div>
            <label className="block text-[11px] font-medium uppercase tracking-wider text-[#858585] mb-1">
              Repository <span className="text-[#FFB4AB]">*</span>
            </label>
            <select
              value={selectedRepoId}
              onChange={(e) => setSelectedRepoId(e.target.value)}
              className="w-full px-2.5 py-1.5 bg-[#1C1C1C] border border-[#303030] focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] font-mono outline-hidden"
            >
              {repositories.length === 0 ? (
                <option value="">No repositories available</option>
              ) : (
                repositories.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.owner}/{r.name} (ID: {r.id})
                  </option>
                ))
              )}
            </select>
          </div>

          {/* PR Number */}
          <div>
            <label className="block text-[11px] font-medium uppercase tracking-wider text-[#858585] mb-1">
              Pull Request Number <span className="text-[#FFB4AB]">*</span>
            </label>
            <input
              type="number"
              min="1"
              placeholder="e.g. 142"
              value={prNumber}
              onChange={(e) => setPrNumber(e.target.value)}
              className="w-full px-2.5 py-1.5 bg-[#1C1C1C] border border-[#303030] focus:border-[#A78BFA] rounded text-xs text-[#D4D4D4] placeholder:text-[#525252] font-mono outline-hidden"
            />
            <p className="text-[10px] text-[#5A5A5A] mt-1">
              Retrieves the PR diff and calculates downstream class/method blast radius.
            </p>
          </div>

          {/* Footer Actions */}
          <div className="pt-3 border-t border-[#303030] flex items-center justify-end gap-2.5">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 bg-[#242424] hover:bg-[#2A2A2A] text-[#D4D4D4] border border-[#303030] text-xs font-medium rounded transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={repositories.length === 0}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#A78BFA] hover:bg-[#906EE8] text-[#121212] text-xs font-semibold rounded transition-colors disabled:opacity-50"
            >
              <span>Analyze PR</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
