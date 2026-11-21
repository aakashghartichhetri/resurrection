import test from 'node:test'
import assert from 'node:assert/strict'
import { explainApiError } from '../src/httpErrors.ts'

test('incorrect credentials do not expose internal details', () => {
  assert.equal(explainApiError(401, 'internal-auth-stack', 'request-123'),
    'That username or password did not work.')
})

test('a recovery conflict includes a safe correlation reference', () => {
  assert.equal(explainApiError(409, 'Recovery approval is stale.', 'incident-12345678'),
    'Recovery approval is stale. Reference: incident-12345678')
})

test('missing or unsafe identifiers are not shown', () => {
  assert.equal(explainApiError(500, undefined, '<script>'), 'The request failed (HTTP 500).')
  assert.equal(explainApiError(503), 'The request failed (HTTP 503).')
})
