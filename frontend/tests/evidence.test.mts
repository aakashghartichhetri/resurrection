import test from 'node:test'
import assert from 'node:assert/strict'
import { buildEvidence, recoveryLabel } from '../src/evidence.ts'
import type { Workflow, Detail, Preview } from '../src/types.ts'

const workflow = (id: string, name: string, status: Workflow['status'], lastSequence = 2): Workflow => ({
  id, name, status, lastSequence, step: 'VALIDATED', stepNumber: 1, failNextStep: false,
  createdAt: '2026-11-18T12:00:00Z'
})

const rows = [workflow('aa01', 'Parcel East', 'ACTIVE'), workflow('bb02', 'West depot', 'FAILED', 4), workflow('cc03', 'Eastern freight', 'COMPLETED')]

test('evidence report does not include credentials and preserves audit and replay details', () => {
  const detail: Detail = { workflow: rows[1], events: [{ sequence: 1, type: 'CREATED', hash: 'abc', message: 'Created', createdAt: '2026-11-18T12:00:00Z' }], checkpointCount: 1, approvals: [], faultAudits: [] }
  const preview: Preview = { projectionMatches: true, action: 'RESUME_AFTER_FAILURE', diagnosis: 'Failed step', expectedStep: 'VALIDATED', expectedStatus: 'FAILED', expectedSequence: 4, expectedFaultArmed: false, expectedHash: 'def', projectionFingerprint: 'xyz', verifiedCheckpoints: 1, simulatedStep: 'VALIDATED', simulatedStatus: 'ACTIVE', causalTrace: [] }
  const evidence = buildEvidence(detail, preview, '2026-11-18T13:00:00Z')
  assert.equal(evidence.workflow.id, 'bb02')
  assert.equal(evidence.reconstruction.expectedHash, 'def')
  assert.equal(evidence.events.length, 1)
  assert.equal(evidence.checkpointsRecorded, 1)
  assert.equal(JSON.stringify(evidence).includes('password'), false)
  assert.equal(recoveryLabel(preview.action), 'Resume from last committed step')
})

test('unverified evidence is clearly marked without inventing a reconstructed state', () => {
  const detail: Detail = { workflow: rows[0], events: [], checkpointCount: 0, approvals: [], faultAudits: [] }
  const report = buildEvidence(detail, null, '2026-11-19T12:00:00Z')
  assert.equal(report.reconstruction, null)
  assert.match(report.scope, /verification did not succeed/)
})
