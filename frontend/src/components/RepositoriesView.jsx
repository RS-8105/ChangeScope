import { useState } from 'react'
import {
  FolderGit2,
  Plus,
  Trash2,
  GitBranch,
  AlertCircle,
  Clock,
  Info,
  CheckCircle2,
  RefreshCw,
  X,
  Database,
} from 'lucide-react'

export default function RepositoriesView({
  repositories,
  isLoading,
  error,
  onRefresh,
  onCreate,
  onDelete,
}) {
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [formOwner, setFormOwner] = useState('')
  const [formName, setFormName] = useState('')
  const [formBranch, setFormBranch] = useState('main')
  const [formError, setFormError] = useState(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [deleteConfirmRepo, setDeleteConfirmRepo] = useState(null)
  const [deleteError, setDeleteError] = useState(null)
  const [isDeleting, setIsDeleting] = useState(false)

  const toggleCreateForm = () => {
    setShowCreateForm((prev) => {
      if (!prev) setFormError(null)
      return !prev
    })
  }

  const handleCreateSubmit = async (e) => {
    e.preventDefault()
    setFormError(null)

    if (!formOwner.trim() || !formName.trim()) {
      setFormError('Both owner and repository name are required.')
      return
    }

    setIsSubmitting(true)
    try {
      await onCreate({
        owner: formOwner.trim(),
        name: formName.trim(),
        defaultBranch: formBranch.trim() || 'main',
      })
      setFormOwner('')
      setFormName('')
      setFormBranch('main')
      setFormError(null)
      setShowCreateForm(false)
    } catch (err) {
      const details = err.details && err.details.length > 0 ? `: ${err.details.join(', ')}` : ''
      setFormError((err.message || 'Failed to create repository') + details)
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleDeleteSubmit = async () => {
    if (!deleteConfirmRepo) return
    setIsDeleting(true)
    setDeleteError(null)
    try {
      await onDelete(deleteConfirmRepo.id)
      setDeleteConfirmRepo(null)
    } catch (err) {
      setDeleteError(err.message || 'Failed to delete repository record')
    } finally {
      setIsDeleting(false)
    }
  }

  return (
    <div className="space-y-6">
      {/* 1. View Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-900 tracking-tight flex items-center gap-2">
            <FolderGit2 className="w-5 h-5 text-indigo-600" />
            Repository Management
          </h2>
          <p className="text-xs text-slate-500 mt-1">
            Connected Java / Spring Boot repository records persisted in PostgreSQL
          </p>
        </div>

        <div className="flex items-center space-x-2">
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="p-2 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 text-slate-600 text-xs font-medium shadow-2xs transition-colors"
            title="Refresh repository list"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
          </button>

          <button
            onClick={toggleCreateForm}
            className="inline-flex items-center space-x-1.5 px-3.5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold shadow-xs transition-colors"
          >
            <Plus className="w-4 h-4" />
            <span>Register Repository</span>
          </button>
        </div>
      </div>

      {/* 2. Metadata Notice Banner */}
      <div className="p-4 rounded-xl bg-indigo-50/60 border border-indigo-100 flex items-start space-x-3 text-xs text-indigo-900">
        <Info className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
        <div className="leading-relaxed">
          <span className="font-semibold text-indigo-950">Metadata Registration Phase:</span> Registering a repository stores project coordinates and default branch settings in PostgreSQL. It does <span className="font-semibold">not</span> clone source code or authenticate with GitHub yet. Repositories are designated as <span className="font-semibold text-slate-700 bg-white px-1.5 py-0.5 rounded border border-indigo-200">Not indexed</span> until code parsing and AST generators run.
        </div>
      </div>

      {/* 3. Global Network Error Banner if any */}
      {error && (
        <div className="p-4 rounded-xl bg-rose-50 border border-rose-200 flex items-start space-x-3 text-xs text-rose-800">
          <AlertCircle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="font-semibold">Could not load repositories from backend</p>
            <p className="text-rose-600 mt-0.5">{error}</p>
          </div>
          <button
            onClick={onRefresh}
            className="text-xs font-semibold underline text-rose-700 hover:text-rose-900 shrink-0"
          >
            Retry
          </button>
        </div>
      )}

      {/* 4. Registration Form (collapsible / toggleable) */}
      {showCreateForm && (
        <div className="p-5 bg-white rounded-xl border border-slate-200 shadow-sm animate-in fade-in duration-150">
          <div className="flex items-center justify-between pb-3 border-b border-slate-150 mb-4">
            <div className="flex items-center space-x-2">
              <Database className="w-4 h-4 text-indigo-600" />
              <h3 className="text-sm font-bold text-slate-900">Register Repository Record</h3>
            </div>
            <button
              onClick={() => {
                setShowCreateForm(false)
                setFormError(null)
              }}
              className="p-1 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          {formError && (
            <div className="p-3 mb-4 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700 flex items-center space-x-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{formError}</span>
            </div>
          )}

          <form onSubmit={handleCreateSubmit} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label className="block text-xs font-semibold text-slate-700 mb-1">
                  Owner / Org <span className="text-rose-500">*</span>
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. changescope-org"
                  value={formOwner}
                  onChange={(e) => setFormOwner(e.target.value)}
                  className="w-full text-xs bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 mb-1">
                  Repository Name <span className="text-rose-500">*</span>
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. order-service"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  className="w-full text-xs bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 mb-1">
                  Default Branch
                </label>
                <input
                  type="text"
                  placeholder="main"
                  value={formBranch}
                  onChange={(e) => setFormBranch(e.target.value)}
                  className="w-full text-xs bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
                />
              </div>
            </div>

            <div className="flex items-center justify-end space-x-2 pt-2">
              <button
                type="button"
                onClick={() => {
                  setShowCreateForm(false)
                  setFormError(null)
                }}
                className="px-3.5 py-1.5 text-xs font-medium text-slate-600 hover:text-slate-800 rounded-lg hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={isSubmitting}
                className="inline-flex items-center space-x-1.5 px-4 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold shadow-xs disabled:opacity-50"
              >
                {isSubmitting ? (
                  <>
                    <span className="w-3.5 h-3.5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    <span>Saving...</span>
                  </>
                ) : (
                  <span>Save Record</span>
                )}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* 5. Repositories Table */}
      <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden">
        <div className="p-4 border-b border-slate-200 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <span className="text-xs font-bold text-slate-900 uppercase tracking-wider">
              PostgreSQL Repositories
            </span>
            <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 font-medium">
              {repositories.length}
            </span>
          </div>
          <span className="text-[11px] text-slate-400">Database: changescope</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="bg-slate-50/75 border-b border-slate-200 text-[11px] font-semibold text-slate-500 uppercase tracking-wider">
                <th className="py-3 px-4">Repository</th>
                <th className="py-3 px-4">Default Branch</th>
                <th className="py-3 px-4">Indexing Status</th>
                <th className="py-3 px-4">Registered</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200">
              {isLoading && repositories.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-12 text-center text-slate-400">
                    <div className="w-6 h-6 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin mx-auto mb-2" />
                    <p className="font-medium text-slate-600 text-xs">Loading repositories from PostgreSQL...</p>
                  </td>
                </tr>
              ) : repositories.length > 0 ? (
                repositories.map((repo) => (
                  <tr key={repo.id} className="hover:bg-slate-50/70 transition-colors">
                    <td className="py-3.5 px-4">
                      <div className="flex items-center space-x-2.5">
                        <div className="w-7 h-7 rounded-lg bg-slate-100 flex items-center justify-center text-slate-600 shrink-0">
                          <FolderGit2 className="w-3.5 h-3.5" />
                        </div>
                        <div>
                          <p className="font-semibold text-slate-900 leading-snug">
                            {repo.fullName || `${repo.owner}/${repo.name}`}
                          </p>
                          <p className="text-[10px] text-slate-400 font-mono">
                            ID: {repo.id} {repo.githubId ? `• GitHub ID: ${repo.githubId}` : ''}
                          </p>
                        </div>
                      </div>
                    </td>

                    <td className="py-3.5 px-4 whitespace-nowrap">
                      <span className="inline-flex items-center space-x-1 font-mono text-[11px] text-slate-600 bg-slate-100/80 px-2 py-0.5 rounded">
                        <GitBranch className="w-3 h-3 text-slate-400" />
                        <span>{repo.defaultBranch || 'main'}</span>
                      </span>
                    </td>

                    <td className="py-3.5 px-4 whitespace-nowrap">
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 text-slate-600 border border-slate-200">
                        <Clock className="w-3 h-3 mr-1 text-slate-400" />
                        Not indexed
                      </span>
                    </td>

                    <td className="py-3.5 px-4 text-slate-500 whitespace-nowrap text-[11px]">
                      {repo.createdAt ? new Date(repo.createdAt).toLocaleDateString() : 'Recent'}
                    </td>

                    <td className="py-3.5 px-4 text-right whitespace-nowrap">
                      <button
                        onClick={() => {
                          setDeleteConfirmRepo(repo)
                          setDeleteError(null)
                        }}
                        className="p-1.5 rounded-md text-slate-400 hover:text-rose-600 hover:bg-rose-50 transition-colors"
                        title="Delete repository record"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={5} className="py-12 text-center text-slate-400">
                    <FolderGit2 className="w-9 h-9 text-slate-300 mx-auto mb-2" />
                    <p className="font-semibold text-slate-700 text-sm">No repositories registered yet</p>
                    <p className="text-xs text-slate-400 mt-1">
                      Register your first Java project to manage change impact boundaries.
                    </p>
                    <button
                      onClick={() => {
                        setShowCreateForm(true)
                        setFormError(null)
                      }}
                      className="mt-4 inline-flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-indigo-50 border border-indigo-200 text-indigo-700 text-xs font-semibold hover:bg-indigo-100 transition-colors"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      <span>Register Now</span>
                    </button>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 6. Delete Confirmation Modal */}
      {deleteConfirmRepo && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="fixed inset-0 bg-slate-900/40 backdrop-blur-sm"
            onClick={() => {
              if (!isDeleting) {
                setDeleteConfirmRepo(null)
                setDeleteError(null)
              }
            }}
          />

          <div className="relative w-full max-w-sm bg-white rounded-xl shadow-xl border border-slate-200 p-6 z-10 space-y-4">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 rounded-full bg-rose-100 text-rose-600 flex items-center justify-center shrink-0">
                <Trash2 className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-sm font-bold text-slate-900">Delete Repository Record?</h3>
                <p className="text-xs text-slate-500 mt-0.5">
                  Are you sure you want to delete <span className="font-semibold text-slate-800">{deleteConfirmRepo.fullName || `${deleteConfirmRepo.owner}/${deleteConfirmRepo.name}`}</span>?
                </p>
              </div>
            </div>

            <p className="text-[11px] text-slate-500 bg-slate-50 p-2.5 rounded-lg border border-slate-200">
              This removes the repository record and its associated metadata from the PostgreSQL database.
            </p>

            {deleteError && (
              <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700 flex items-center space-x-2">
                <AlertCircle className="w-4 h-4 shrink-0" />
                <span>{deleteError}</span>
              </div>
            )}

            <div className="flex items-center justify-end space-x-2 pt-2">
              <button
                type="button"
                disabled={isDeleting}
                onClick={() => {
                  setDeleteConfirmRepo(null)
                  setDeleteError(null)
                }}
                className="px-3.5 py-1.5 text-xs font-medium text-slate-600 hover:text-slate-800 rounded-lg hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={isDeleting}
                onClick={handleDeleteSubmit}
                className="inline-flex items-center space-x-1.5 px-4 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-xs font-semibold shadow-xs disabled:opacity-50"
              >
                {isDeleting ? (
                  <>
                    <span className="w-3.5 h-3.5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    <span>Deleting...</span>
                  </>
                ) : (
                  <span>Delete Record</span>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
