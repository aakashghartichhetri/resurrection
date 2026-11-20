import type { Detail, Preview } from './types'
export function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Unknown time' : new Intl.DateTimeFormat('en', {
    dateStyle: 'medium', timeStyle: 'short'
  }).format(date)
}
export function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('en', {
    hour: '2-digit', minute: '2-digit'
  }).format(date)
}
export function buildEvidence(detail: Detail, preview: Preview | null, exportedAt: string) {
  return {
    report: 'RESURRECTION / Workflow investigation',
    format: 2,
    exportedAt,
    scope: 'Workflow evidence snapshot. Reconstructed state is null when integrity verification did not succeed.',
    limitation: 'SHA-256 hash links identify unintentional changes; a privileged database writer could rewrite the chain. This is not an independently anchored audit log.',
    workflow: detail.workflow,
    reconstruction: preview,
    events: detail.events,
    checkpointsRecorded: detail.checkpointCount,
    recoveryApprovals: detail.approvals,
    faultInjections: detail.faultAudits
  }
}
export function recoveryLabel(action: Preview['action']) {
  if (action === 'REBUILD_PROJECTION') return 'Restore stored state'
  if (action === 'RESUME_AFTER_FAILURE') return 'Resume from last committed step'
  return 'No repair required'
}
