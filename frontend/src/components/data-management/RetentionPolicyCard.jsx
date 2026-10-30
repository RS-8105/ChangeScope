import { ShieldCheck, Clock, CheckCircle2, AlertCircle, Info, Database } from 'lucide-react'

const RETENTION_RULES = [
  {
    id: 'completed-analyses',
    title: 'Completed PR Analyses',
    window: '14 Days',
    description: 'Deleted automatically after 14 days from completion timestamp (completedAt).',
    type: 'Scheduled Cleanup',
    badgeClass: 'bg-[#A78BFA]/15 text-[#A78BFA] border-[#A78BFA]/30',
    icon: CheckCircle2,
    iconColor: 'text-[#A78BFA]',
  },
  {
    id: 'failed-analyses',
    title: 'Failed PR Analyses',
    window: '7 Days',
    description: 'Deleted automatically after 7 days from completion timestamp or startedAt fallback.',
    type: 'Scheduled Cleanup',
    badgeClass: 'bg-[#F9BD22]/15 text-[#F9BD22] border-[#F9BD22]/30',
    icon: AlertCircle,
    iconColor: 'text-[#F9BD22]',
  },
  {
    id: 'webhook-deliveries',
    title: 'Webhook Deliveries',
    window: '7 Days',
    description: 'Deleted automatically after 7 days from initial receipt timestamp.',
    type: 'Scheduled Cleanup',
    badgeClass: 'bg-[#858585]/15 text-[#D4D4D4] border-[#858585]/30',
    icon: Clock,
    iconColor: 'text-[#858585]',
  },
  {
    id: 'repository-indexes',
    title: 'Repository AST Indexes',
    window: 'Indefinite',
    description: 'Retained until manually deleted. Never automatically purged by background scheduler.',
    type: 'Manual Only',
    badgeClass: 'bg-[#45DFA4]/15 text-[#45DFA4] border-[#45DFA4]/30',
    icon: Database,
    iconColor: 'text-[#45DFA4]',
  },
  {
    id: 'connected-repositories',
    title: 'Connected Repositories',
    window: 'Indefinite',
    description: 'Retained until manually disconnected or deleted by an administrator.',
    type: 'Manual Only',
    badgeClass: 'bg-[#45DFA4]/15 text-[#45DFA4] border-[#45DFA4]/30',
    icon: ShieldCheck,
    iconColor: 'text-[#45DFA4]',
  },
]

export default function RetentionPolicyCard() {
  return (
    <div className="bg-[#242424] border border-[#303030] rounded-lg overflow-hidden shadow-sm">
      {/* Policy Card Header */}
      <div className="px-5 py-4 border-b border-[#303030] bg-[#1C1C1C] flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded bg-[#A78BFA]/15 border border-[#A78BFA]/30 flex items-center justify-center text-[#A78BFA]">
            <Clock className="w-4 h-4" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-[#D4D4D4] flex items-center gap-2">
              Fixed Automated Data Retention Policy
            </h2>
            <p className="text-[11px] text-[#858585] mt-0.5">
              ChangeScope executes an automated batch cleanup daily at 03:00 UTC. Active and running tasks are never purged.
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded text-[11px] font-mono bg-[#171717] border border-[#303030] text-[#858585] shrink-0 self-start sm:self-auto">
          <Info className="w-3.5 h-3.5 text-[#A78BFA]" />
          <span>Fixed System Configuration</span>
        </div>
      </div>

      {/* Rules Grid */}
      <div className="p-5 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3.5">
        {RETENTION_RULES.map((rule) => {
          const Icon = rule.icon
          return (
            <div
              key={rule.id}
              className="bg-[#1C1C1C] border border-[#2D2D2D] rounded-md p-4 flex flex-col justify-between hover:border-[#383838] transition-colors"
            >
              <div>
                <div className="flex items-start justify-between gap-2 mb-2">
                  <div className="flex items-center gap-2">
                    <Icon className={`w-4 h-4 shrink-0 ${rule.iconColor}`} />
                    <span className="text-xs font-medium text-[#EDEDED]">{rule.title}</span>
                  </div>
                  <span
                    className={`text-[10px] font-mono font-medium px-2 py-0.5 rounded border shrink-0 ${rule.badgeClass}`}
                  >
                    {rule.window}
                  </span>
                </div>
                <p className="text-[11px] text-[#858585] leading-relaxed">
                  {rule.description}
                </p>
              </div>

              <div className="mt-3 pt-2.5 border-t border-[#262626] flex items-center justify-between text-[10px] text-[#5A5A5A] font-mono">
                <span>Lifecycle</span>
                <span className="text-[#858585]">{rule.type}</span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
