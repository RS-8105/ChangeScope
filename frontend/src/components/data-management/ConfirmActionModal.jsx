import { useState, useEffect } from 'react'
import { AlertTriangle, X, Loader2 } from 'lucide-react'

export default function ConfirmActionModal({
  isOpen,
  onClose,
  onConfirm,
  title,
  targetName,
  description,
  impactPoints = [],
  confirmButtonText = 'Confirm Deletion',
  confirmTextToType = null,
  isDestructive = true,
  isLoading = false,
  error = null,
}) {
  const [typedConfirmation, setTypedConfirmation] = useState('')

  useEffect(() => {
    if (isOpen) {
      setTypedConfirmation('')
    }
  }, [isOpen])

  // Escape key listener
  useEffect(() => {
    if (!isOpen) return
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && !isLoading) {
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, isLoading, onClose])

  if (!isOpen) return null

  const isConfirmationValid = !confirmTextToType || typedConfirmation.trim() === confirmTextToType.trim()

  const handleConfirmClick = async () => {
    if (!isConfirmationValid || isLoading) return
    await onConfirm()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-xs">
      <div
        className="bg-[#242424] border border-[#303030] rounded-lg w-full max-w-md overflow-hidden shadow-2xl animate-in fade-in zoom-in-95 duration-150"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-modal-title"
      >
        {/* Header */}
        <div className="px-5 py-4 border-b border-[#303030] flex items-center justify-between bg-[#1C1C1C]">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded bg-[#FFB4AB]/15 border border-[#FFB4AB]/30 flex items-center justify-center text-[#FFB4AB]">
              <AlertTriangle className="w-4 h-4" />
            </div>
            <div>
              <h2 id="confirm-modal-title" className="text-sm font-semibold text-[#EDEDED]">
                {title}
              </h2>
              {targetName && (
                <div className="text-[11px] font-mono text-[#858585] mt-0.5">
                  Target: <span className="text-[#D4D4D4] font-medium">{targetName}</span>
                </div>
              )}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isLoading}
            className="text-[#858585] hover:text-[#D4D4D4] p-1 rounded hover:bg-[#2A2A2A] transition-colors disabled:opacity-50"
            aria-label="Close dialog"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="p-5 space-y-4 text-xs">
          {description && (
            <p className="text-[#858585] leading-relaxed">
              {description}
            </p>
          )}

          {impactPoints && impactPoints.length > 0 && (
            <div className="bg-[#1C1C1C] border border-[#2D2D2D] rounded-md p-3.5 space-y-2">
              <span className="text-[11px] font-medium text-[#D4D4D4] uppercase tracking-wider block">
                Consequences of this action:
              </span>
              <ul className="space-y-1.5 text-[11px] text-[#A3A3A3]">
                {impactPoints.map((point, idx) => (
                  <li key={idx} className="flex items-start gap-2">
                    <span className="text-[#FFB4AB] mt-0.5 leading-none">•</span>
                    <span>{point}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {confirmTextToType && (
            <div className="space-y-2 pt-1">
              <label htmlFor="confirm-typed-input" className="block text-[11px] text-[#858585]">
                To verify, type <span className="font-mono text-[#EDEDED] font-semibold">{confirmTextToType}</span> below:
              </label>
              <input
                id="confirm-typed-input"
                type="text"
                value={typedConfirmation}
                onChange={(e) => setTypedConfirmation(e.target.value)}
                placeholder={confirmTextToType}
                disabled={isLoading}
                autoFocus
                className="w-full bg-[#171717] border border-[#303030] focus:border-[#FFB4AB] rounded px-3 py-2 text-xs font-mono text-[#EDEDED] placeholder-[#525252] focus:outline-none transition-colors"
              />
            </div>
          )}

          {error && (
            <div className="bg-[#3D1414] border border-[#FFB4AB]/30 rounded p-3 text-[11px] text-[#FFB4AB] flex items-start gap-2">
              <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
              <div className="leading-relaxed">{error}</div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3.5 bg-[#1C1C1C] border-t border-[#303030] flex items-center justify-end gap-2.5">
          <button
            type="button"
            onClick={onClose}
            disabled={isLoading}
            className="px-3.5 py-1.5 text-xs font-medium text-[#858585] hover:text-[#D4D4D4] bg-[#242424] hover:bg-[#2A2A2A] border border-[#303030] rounded transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleConfirmClick}
            disabled={!isConfirmationValid || isLoading}
            className={`px-4 py-1.5 text-xs font-medium rounded transition-colors flex items-center gap-2 ${
              isDestructive
                ? 'bg-[#FFB4AB] text-[#171717] hover:bg-[#FF897D] disabled:bg-[#FFB4AB]/40 disabled:text-[#171717]/60'
                : 'bg-[#A78BFA] text-[#171717] hover:bg-[#906EE8] disabled:bg-[#A78BFA]/40 disabled:text-[#171717]/60'
            }`}
          >
            {isLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            <span>{confirmButtonText}</span>
          </button>
        </div>
      </div>
    </div>
  )
}
