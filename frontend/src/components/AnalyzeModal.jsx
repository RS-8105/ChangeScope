import { useState } from 'react'
import {
  X,
  Sparkles,
  ArrowRight,
  AlertCircle,
  FileCode2,
  Plus,
  Trash2,
  RefreshCw,
  Layers,
  Info,
  CheckCircle2,
  ArrowUpRight,
  GitCommit,
} from 'lucide-react'
import { analyzeImpact } from '../lib/api.js'

const DEFAULT_SAMPLE_FILES = [
  {
    filePath: 'com/changescope/order/OrderRepository.java',
    sourceCode: `package com.changescope.order;

public class OrderRepository {
    public void save() {}
}`,
  },
  {
    filePath: 'com/changescope/order/OrderService.java',
    sourceCode: `package com.changescope.order;

public class OrderService {
    private OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }
}`,
  },
  {
    filePath: 'com/changescope/order/OrderController.java',
    sourceCode: `package com.changescope.order;

public class OrderController {
    private OrderService orderService;
}`,
  },
]

const DEFAULT_SAMPLE_CHANGED = 'com.changescope.order.OrderRepository'

export default function AnalyzeModal({
  isOpen,
  onClose,
  lastReport,
  onReportGenerated,
}) {
  const [files, setFiles] = useState(DEFAULT_SAMPLE_FILES)
  const [changedTypesInput, setChangedTypesInput] = useState(DEFAULT_SAMPLE_CHANGED)
  const [isLoading, setIsLoading] = useState(false)
  const [apiError, setApiError] = useState(null)
  const [validationError, setValidationError] = useState(null)
  const [activeTab, setActiveTab] = useState(lastReport ? 'results' : 'input')

  if (!isOpen) return null

  const handleAddFile = () => {
    setFiles([
      ...files,
      {
        filePath: `com/changescope/example/NewClass${files.length + 1}.java`,
        sourceCode: `package com.changescope.example;\n\npublic class NewClass${files.length + 1} {\n}`,
      },
    ])
  }

  const handleRemoveFile = (index) => {
    if (files.length <= 1) return
    setFiles(files.filter((_, i) => i !== index))
  }

  const handleFileChange = (index, field, value) => {
    const updated = [...files]
    updated[index] = { ...updated[index], [field]: value }
    setFiles(updated)
  }

  const handleLoadSample = () => {
    setFiles(DEFAULT_SAMPLE_FILES)
    setChangedTypesInput(DEFAULT_SAMPLE_CHANGED)
    setValidationError(null)
    setApiError(null)
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setValidationError(null)
    setApiError(null)

    // Parse changed types
    const parsedChangedTypes = changedTypesInput
      .split(/[\n,]+/)
      .map((t) => t.trim())
      .filter((t) => t.length > 0)

    if (parsedChangedTypes.length === 0) {
      setValidationError('Please specify at least one changed fully qualified type name.')
      return
    }

    if (files.length === 0) {
      setValidationError('Please provide at least one Java source file.')
      return
    }

    for (let i = 0; i < files.length; i++) {
      if (!files[i].filePath || !files[i].filePath.trim()) {
        setValidationError(`File #${i + 1} has an empty file path.`)
        return
      }
    }

    setIsLoading(true)

    try {
      const payload = {
        files: files.map((f) => ({
          filePath: f.filePath.trim(),
          sourceCode: f.sourceCode || '',
        })),
        changedTypes: parsedChangedTypes,
      }

      const report = await analyzeImpact(payload)
      onReportGenerated?.(report)
      setActiveTab('results')
    } catch (err) {
      const details = err.details && err.details.length > 0 ? `: ${err.details.join(', ')}` : ''
      setApiError((err.message || 'Analysis request failed') + details)
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 overflow-y-auto">
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm transition-opacity"
        onClick={onClose}
      />

      {/* Modal Dialog */}
      <div className="relative w-full max-w-4xl bg-white rounded-2xl shadow-2xl border border-slate-200 overflow-hidden z-10 flex flex-col max-h-[92vh] animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between bg-white shrink-0">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 shrink-0">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h2 className="text-base font-bold text-slate-900">Java Source Impact Analysis</h2>
                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 border border-indigo-200">
                  AST & Blast Radius
                </span>
              </div>
              <p className="text-xs text-slate-500">
                Connected to real backend pipeline &bull; <code className="text-[11px] font-mono text-slate-600">POST /api/v1/analysis/impact</code>
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            {lastReport && (
              <div className="flex rounded-lg border border-slate-200 bg-slate-100/80 p-0.5 text-xs mr-2">
                <button
                  type="button"
                  onClick={() => setActiveTab('input')}
                  className={`px-3 py-1 rounded-md font-medium text-xs transition-colors ${
                    activeTab === 'input'
                      ? 'bg-white text-slate-900 shadow-2xs font-semibold'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  Configure Input
                </button>
                <button
                  type="button"
                  onClick={() => setActiveTab('results')}
                  className={`px-3 py-1 rounded-md font-medium text-xs transition-colors ${
                    activeTab === 'results'
                      ? 'bg-white text-slate-900 shadow-2xs font-semibold'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  View Report
                </button>
              </div>
            )}

            <button
              onClick={onClose}
              className="p-1.5 rounded-md text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              aria-label="Close modal"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div className="p-6 overflow-y-auto space-y-5 flex-1">
          {/* Metadata & Architecture Notice Banner */}
          <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200 flex items-start space-x-3 text-xs text-slate-600">
            <Info className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
            <div className="leading-relaxed">
              <span className="font-semibold text-slate-800">Source-Level Impact Engine:</span> This pipeline statically parses Java ASTs, resolves type dependencies across provided source files, and traverses the inverted caller graph to compute blast radius. Direct GitHub repo cloning and automatic pull-request diff ingestion are upcoming milestones.
            </div>
          </div>

          {/* Error Banners */}
          {validationError && (
            <div className="p-3.5 rounded-xl bg-amber-50 border border-amber-200 text-xs text-amber-800 flex items-start space-x-2.5">
              <AlertCircle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
              <span>{validationError}</span>
            </div>
          )}

          {apiError && (
            <div className="p-3.5 rounded-xl bg-rose-50 border border-rose-200 text-xs text-rose-800 flex items-start space-x-2.5">
              <AlertCircle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" />
              <div className="flex-1">
                <p className="font-semibold">Backend Analysis Error</p>
                <p className="mt-0.5 text-rose-700">{apiError}</p>
              </div>
            </div>
          )}

          {/* VIEW: Configuration / Input Form */}
          {activeTab === 'input' && (
            <form onSubmit={handleSubmit} className="space-y-5">
              {/* Changed Types Input */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                    <GitCommit className="w-3.5 h-3.5 text-indigo-600" />
                    Changed Fully Qualified Types <span className="text-rose-500">*</span>
                  </label>
                  <button
                    type="button"
                    onClick={handleLoadSample}
                    className="text-[11px] text-indigo-600 hover:text-indigo-800 font-medium underline"
                  >
                    Reset to Sample Template
                  </button>
                </div>
                <input
                  type="text"
                  required
                  placeholder="e.g. com.changescope.order.OrderRepository"
                  value={changedTypesInput}
                  onChange={(e) => setChangedTypesInput(e.target.value)}
                  className="w-full text-xs font-mono bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-slate-800 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
                />
                <p className="text-[11px] text-slate-400 mt-1">
                  Comma-separated list of types that were changed or modified.
                </p>
              </div>

              {/* Source Files List */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                    <FileCode2 className="w-3.5 h-3.5 text-indigo-600" />
                    Java Source Files Scope ({files.length})
                  </span>
                  <button
                    type="button"
                    onClick={handleAddFile}
                    className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-md bg-indigo-50 hover:bg-indigo-100 text-indigo-700 text-xs font-semibold transition-colors"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    <span>Add File</span>
                  </button>
                </div>

                <div className="space-y-4">
                  {files.map((file, idx) => (
                    <div
                      key={idx}
                      className="p-3.5 rounded-xl border border-slate-200 bg-slate-50/60 space-y-2.5"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <input
                          type="text"
                          required
                          placeholder="e.g. com/changescope/order/OrderService.java"
                          value={file.filePath}
                          onChange={(e) => handleFileChange(idx, 'filePath', e.target.value)}
                          className="flex-1 text-xs font-mono bg-white border border-slate-200 rounded-lg px-2.5 py-1.5 text-slate-800 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                        />
                        {files.length > 1 && (
                          <button
                            type="button"
                            onClick={() => handleRemoveFile(idx)}
                            className="p-1.5 text-slate-400 hover:text-rose-600 hover:bg-rose-50 rounded-md transition-colors"
                            title="Remove file"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </div>

                      <textarea
                        rows={6}
                        required
                        placeholder="Java source code here..."
                        value={file.sourceCode}
                        onChange={(e) => handleFileChange(idx, 'sourceCode', e.target.value)}
                        className="w-full text-xs font-mono bg-white border border-slate-200 rounded-lg p-2.5 text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-1 focus:ring-indigo-500 leading-relaxed"
                      />
                    </div>
                  ))}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="pt-3 border-t border-slate-200 flex items-center justify-end space-x-2.5">
                <button
                  type="button"
                  onClick={onClose}
                  className="px-4 py-2 text-xs font-semibold text-slate-600 hover:text-slate-800 rounded-lg hover:bg-slate-100"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isLoading}
                  className="inline-flex items-center space-x-1.5 px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold shadow-xs disabled:opacity-50 transition-colors"
                >
                  {isLoading ? (
                    <>
                      <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                      <span>Analyzing AST & Traversing Blast Radius...</span>
                    </>
                  ) : (
                    <>
                      <span>Run Impact Analysis</span>
                      <ArrowRight className="w-3.5 h-3.5" />
                    </>
                  )}
                </button>
              </div>
            </form>
          )}

          {/* VIEW: Real Report Results */}
          {activeTab === 'results' && lastReport && (
            <div className="space-y-6">
              {/* Stat Summary Cards */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200">
                  <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                    Changed Types
                  </span>
                  <span className="text-xl font-bold text-slate-900 mt-1 block">
                    {lastReport.totalChangedTypes}
                  </span>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200">
                  <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                    Affected Types
                  </span>
                  <span className={`text-xl font-bold mt-1 block ${lastReport.totalAffectedTypes > 0 ? 'text-indigo-600' : 'text-slate-900'}`}>
                    {lastReport.totalAffectedTypes}
                  </span>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200">
                  <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                    Dependencies
                  </span>
                  <span className="text-xl font-bold text-slate-900 mt-1 block">
                    {lastReport.dependencies?.length || 0}
                  </span>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200">
                  <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                    Parse Errors
                  </span>
                  <span className={`text-xl font-bold mt-1 block ${lastReport.parseErrors?.length > 0 ? 'text-rose-600' : 'text-slate-900'}`}>
                    {lastReport.parseErrors?.length || 0}
                  </span>
                </div>
              </div>

              {/* Parsing Errors Banner if any */}
              {lastReport.parseErrors && lastReport.parseErrors.length > 0 && (
                <div className="p-4 rounded-xl bg-rose-50 border border-rose-200 space-y-2">
                  <div className="flex items-center space-x-2 text-rose-800 text-xs font-bold">
                    <AlertCircle className="w-4 h-4 text-rose-600" />
                    <span>Partial Parsing Errors Detected ({lastReport.parseErrors.length})</span>
                  </div>
                  <div className="space-y-1 text-xs">
                    {lastReport.parseErrors.map((err, i) => (
                      <div key={i} className="font-mono text-[11px] text-rose-700 bg-white/70 p-2 rounded border border-rose-200">
                        <span className="font-bold">{err.filePath}</span>: {err.errorMessage}
                      </div>
                    ))}
                  </div>
                  <p className="text-[11px] text-rose-600">
                    Remaining valid files in the batch were successfully analyzed.
                  </p>
                </div>
              )}

              {/* Changed Types Section */}
              <div className="space-y-2">
                <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Root Changed Types ({lastReport.changedTypes?.length || 0})
                </h3>
                <div className="flex flex-wrap gap-2">
                  {lastReport.changedTypes && lastReport.changedTypes.length > 0 ? (
                    lastReport.changedTypes.map((type, i) => (
                      <span
                        key={i}
                        className="inline-flex items-center px-2.5 py-1 rounded-md text-xs font-mono bg-indigo-50 text-indigo-700 border border-indigo-200"
                      >
                        <GitCommit className="w-3 h-3 mr-1 text-indigo-500" />
                        {type}
                      </span>
                    ))
                  ) : (
                    <span className="text-xs text-slate-400">None specified</span>
                  )}
                </div>
              </div>

              {/* Affected Types & Blast Radius Section */}
              <div className="space-y-2">
                <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Transitively Affected Types ({lastReport.affectedTypes?.length || 0})
                </h3>
                {lastReport.affectedTypes && lastReport.affectedTypes.length > 0 ? (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {lastReport.affectedTypes.map((aff, i) => (
                      <div
                        key={i}
                        className="p-3 rounded-xl border border-slate-200 bg-white shadow-2xs flex items-center justify-between"
                      >
                        <span className="font-mono text-xs text-slate-800 font-semibold truncate" title={aff.typeName}>
                          {aff.typeName}
                        </span>
                        <span
                          className={`text-[11px] font-semibold px-2 py-0.5 rounded-full shrink-0 ml-2 ${
                            aff.distance === 1
                              ? 'bg-amber-50 text-amber-700 border border-amber-200'
                              : 'bg-indigo-50 text-indigo-700 border border-indigo-200'
                          }`}
                        >
                          {aff.distance === 1 ? 'Direct (1 hop)' : `Transitive (${aff.distance} hops)`}
                        </span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="p-4 rounded-xl bg-slate-50 border border-slate-200 text-center text-xs text-slate-500">
                    No downstream types are affected by the specified changes.
                  </div>
                )}
              </div>

              {/* Discovered Relationships Table */}
              <div className="space-y-2">
                <h3 className="text-xs font-bold text-slate-800 uppercase tracking-wider">
                  Discovered Dependency Relationships ({lastReport.dependencies?.length || 0})
                </h3>
                {lastReport.dependencies && lastReport.dependencies.length > 0 ? (
                  <div className="border border-slate-200 rounded-xl overflow-hidden max-h-56 overflow-y-auto">
                    <table className="w-full text-left text-xs">
                      <thead className="bg-slate-50 text-[10px] font-bold text-slate-500 uppercase tracking-wider border-b border-slate-200 sticky top-0">
                        <tr>
                          <th className="py-2 px-3">Dependent (Caller)</th>
                          <th className="py-2 px-3">Dependency (Callee)</th>
                          <th className="py-2 px-3">Relationship</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-150 font-mono text-[11px]">
                        {lastReport.dependencies.map((dep, i) => (
                          <tr key={i} className="hover:bg-slate-50/70">
                            <td className="py-2 px-3 text-slate-800">{dep.sourceType}</td>
                            <td className="py-2 px-3 text-slate-600">{dep.targetType}</td>
                            <td className="py-2 px-3">
                              <span className="px-1.5 py-0.5 rounded text-[10px] font-sans font-semibold bg-slate-100 text-slate-700 border border-slate-200">
                                {dep.kind}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="p-4 rounded-xl bg-slate-50 border border-slate-200 text-center text-xs text-slate-500">
                    No cross-type dependencies found in the provided files.
                  </div>
                )}
              </div>

              {/* Report Actions */}
              <div className="pt-3 border-t border-slate-200 flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setActiveTab('input')}
                  className="px-3.5 py-2 text-xs font-semibold text-indigo-600 hover:text-indigo-800 rounded-lg hover:bg-indigo-50 transition-colors"
                >
                  &larr; Modify Input Files
                </button>
                <button
                  type="button"
                  onClick={onClose}
                  className="px-4 py-2 rounded-lg bg-slate-800 hover:bg-slate-900 text-white text-xs font-semibold shadow-xs transition-colors"
                >
                  Done
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
