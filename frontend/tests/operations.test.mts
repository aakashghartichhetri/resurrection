import test from 'node:test'
import assert from 'node:assert/strict'
import { filterFleet, healthLabels, needsAttention, pipeline, triage } from '../src/operations.ts'
import type { WorkflowHealth, Workflow } from '../src/types.ts'

const health = (id: string, state: WorkflowHealth['state'], name: string): WorkflowHealth => ({
  id, name, state, reason: 'Sample', step: 'VALIDATED', status: state === 'FAILED' ? 'FAILED' : 'ACTIVE',
  lastSequence: 2, createdAt: '2026-11-18T12:00:00Z'
})
const fleet = [health('1','HEALTHY','North depot'), health('2','FAILED','East depot'),
  health('3','HISTORY_INVALID','West depot'), health('4','DRIFTED','South depot'),
  health('5','ARMED','Dock A')]

test('critical integrity errors sort before recoverable incidents', () => {
  assert.deepEqual(triage(fleet).map(w => w.id), ['3','4','2','5','1'])
})
test('attention filter and case-insensitive search are independent', () => {
  assert.equal(filterFleet(fleet, '', 'ATTENTION').length, 4)
  assert.deepEqual(filterFleet(fleet, 'EAST', 'ALL').map(w => w.id), ['2'])
  assert.deepEqual(filterFleet(fleet, 'north', 'ATTENTION'), [])
  assert.equal(filterFleet(fleet, '', 'COMPLETED').length, 0)
})
test('all non-healthy classifications prompt investigation', () => {
  assert.equal(needsAttention('HEALTHY'), false)
  for (const state of ['FAILED','DRIFTED','HISTORY_INVALID','ARMED'] as const) {
    assert.equal(needsAttention(state), true)
    assert.notEqual(healthLabels[state], 'Verified')
  }
})
test('pipeline counts current state rather than transition history', () => {
  const workflow = (step: string): Workflow => ({ id: step, name: step, step, stepNumber: 0, status: 'ACTIVE',
    lastSequence: 9, failNextStep: false, createdAt: '2026-11-18T12:00:00Z' })
  assert.deepEqual(pipeline([workflow('CREATED'), workflow('VALIDATED'), workflow('VALIDATED'), workflow('SHIPPED')]).map(d => d.value), [1,2,0,1])
})
