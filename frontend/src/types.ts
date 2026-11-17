export type WorkflowStatus = 'ACTIVE' | 'FAILED' | 'COMPLETED'
export type RecoveryAction = 'NONE' | 'RESUME_AFTER_FAILURE' | 'REBUILD_PROJECTION'

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

export interface Detail {
  workflow: Workflow
  events: WorkflowEvent[]
  checkpointCount: number
}

export interface Preview {
  projectionMatches: boolean
  diagnosis: string
  action: RecoveryAction
  expectedStep: string
  expectedStatus: WorkflowStatus
  expectedSequence: number
  verifiedCheckpoints: number
  simulatedStep: string
  simulatedStatus: WorkflowStatus
}
