export type WorkflowStatus = 'ACTIVE' | 'FAILED' | 'COMPLETED'
export type RecoveryAction = 'NONE' | 'RESUME_AFTER_FAILURE' | 'REBUILD_PROJECTION'
export type HealthState = 'HEALTHY' | 'FAILED' | 'DRIFTED' | 'HISTORY_INVALID' | 'ARMED'

export interface Workflow {
  id: string
  name: string
  step: string
  stepNumber: number
  status: WorkflowStatus
  lastSequence: number
  failNextStep: boolean
  createdAt: string
}
export interface WorkflowEvent {
  sequence: number
  type: string
  message: string
  createdAt: string
  hash: string
}
export interface ApprovalAudit {
  id: number
  actor: string
  action: string
  approvedSequence: number
  beforeStep: number
  beforeStatus: WorkflowStatus
  afterStep: number
  afterStatus: WorkflowStatus
  approvedAt: string
}
export interface FaultAudit {
  id: number
  actor: string
  type: 'FAIL_NEXT_STEP' | 'CORRUPT_PROJECTION'
  beforeStep: number
  beforeStatus: WorkflowStatus
  createdAt: string
}
export interface Detail {
  workflow: Workflow
  events: WorkflowEvent[]
  checkpointCount: number
  approvals: ApprovalAudit[]
  faultAudits: FaultAudit[]
}
export interface Preview {
  projectionMatches: boolean
  diagnosis: string
  action: RecoveryAction
  expectedStep: string
  expectedStatus: WorkflowStatus
  expectedSequence: number
  expectedFaultArmed: boolean
  expectedHash: string
  projectionFingerprint: string
  verifiedCheckpoints: number
  simulatedStep: string
  simulatedStatus: WorkflowStatus
  causalTrace: WorkflowEvent[]
}
export interface WorkflowHealth {
  id: string
  name: string
  state: HealthState
  reason: string
  step: string
  status: WorkflowStatus
  lastSequence: number
  createdAt: string
}
export interface Activity {
  workflowId: string
  workflowName: string
  sequence: number
  type: string
  message: string
  createdAt: string
}
export interface Operations {
  checkedAt: string
  total: number
  active: number
  failed: number
  completed: number
  attention: number
  integrityErrors: number
  totalEvents: number
  workflows: WorkflowHealth[]
  recentActivity: Activity[]
}
