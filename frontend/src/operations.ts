import type { HealthState, Workflow, WorkflowHealth } from './types'

export type FleetFilter = 'ALL' | 'ATTENTION' | 'ACTIVE' | 'COMPLETED'

export const healthLabels: Record<HealthState, string> = {
  HEALTHY: 'Verified',
  FAILED: 'Needs recovery',
  DRIFTED: 'State mismatch',
  HISTORY_INVALID: 'History invalid',
  ARMED: 'Fault armed'
}

export function needsAttention(state: HealthState): boolean {
  return state !== 'HEALTHY'
}

const severity: Record<HealthState, number> = {
  HISTORY_INVALID: 0, DRIFTED: 1, FAILED: 2, ARMED: 3, HEALTHY: 4
}

export function triage(items: WorkflowHealth[]): WorkflowHealth[] {
  return [...items].sort((a, b) => severity[a.state] - severity[b.state] || a.name.localeCompare(b.name))
}

export function filterFleet(items: WorkflowHealth[], query: string, filter: FleetFilter): WorkflowHealth[] {
  const term = query.trim().toLocaleLowerCase()
  return triage(items.filter(item => {
    const matches = !term || item.name.toLocaleLowerCase().includes(term) || item.id.toLocaleLowerCase().includes(term)
    if (!matches) return false
    if (filter === 'ATTENTION') return needsAttention(item.state)
    if (filter === 'ACTIVE') return item.status === 'ACTIVE'
    if (filter === 'COMPLETED') return item.status === 'COMPLETED'
    return true
  }))
}

export function pipeline(workflows: Workflow[]): Array<{ label: string; value: number }> {
  const stages = ['CREATED', 'VALIDATED', 'PACKED', 'SHIPPED']
  return stages.map(label => ({
    label: label.charAt(0) + label.slice(1).toLowerCase(),
    value: workflows.filter(w => w.step === label).length
  }))
}

