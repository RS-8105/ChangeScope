import MetricCard from '../ui/MetricCard.jsx'
import { History, ShieldAlert, CheckCircle, XCircle } from 'lucide-react'

export default function HistoryMetrics({
  totalAnalyses = 0,
  completedCount = 0,
  failedCount = 0,
  isLoading = false,
  hasRepository = true,
}) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {/* 1. Total Analyses */}
      <MetricCard
        label="Total Analyses"
        value={isLoading ? '—' : String(totalAnalyses)}
        subtext={
          hasRepository
            ? 'Recorded for selected repository'
            : 'Select a repository to view history'
        }
        icon={History}
        isUnavailable={!hasRepository || isLoading}
      />

      {/* 2. Completed Analyses */}
      <MetricCard
        label="Completed Analyses"
        value={isLoading ? '—' : String(completedCount)}
        subtext={
          completedCount === 1
            ? '1 report snapshot saved'
            : `${completedCount} report snapshots saved`
        }
        icon={CheckCircle}
        isUnavailable={!hasRepository || isLoading}
      />

      {/* 3. Failed Analyses */}
      <MetricCard
        label="Failed Analyses"
        value={isLoading ? '—' : String(failedCount)}
        subtext={
          failedCount > 0
            ? `${failedCount} analyses encountered errors`
            : 'Zero failure records'
        }
        icon={XCircle}
        isUnavailable={!hasRepository || isLoading}
      />

      {/* 4. High-Risk Analyses (Honest unavailable state) */}
      <MetricCard
        label="High-Risk Analyses"
        value="—"
        subtext="Requires individual report inspection"
        icon={ShieldAlert}
        isUnavailable={true}
      />
    </div>
  )
}
