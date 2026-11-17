import { useState } from 'react'
import type { Detail, Preview, Workflow } from './types'

const steps = ['CREATED', 'VALIDATED', 'PACKED', 'SHIPPED']

export default function App() {
  const [username, setUsername] = useState('resurrection')
  const [password, setPassword] = useState('')
  const [connected, setConnected] = useState(false)
  const [workflows, setWorkflows] = useState<Workflow[]>([])
  const [detail, setDetail] = useState<Detail | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [newName, setNewName] = useState('My recovery experiment')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function request<T>(path: string, method = 'GET', body?: object): Promise<T> {
    const response = await fetch('/api' + path, {
      method,
      headers: {
        Authorization: 'Basic ' + btoa(username + ':' + password),
        ...(body ? { 'Content-Type': 'application/json' } : {})
      },
      ...(body ? { body: JSON.stringify(body) } : {})
    })
    if (!response.ok) {
      const message = await response.json().catch(() => ({})) as { error?: string }
      throw new Error(response.status === 401
        ? 'Authentication failed. Check your username and password.'
        : message.error || `Request failed (HTTP ${response.status})`)
    }
    return response.json() as Promise<T>
  }

  async function refresh(selectedId?: string) {
    const list = await request<Workflow[]>('/workflows')
    setWorkflows(list)
    const id = selectedId || detail?.workflow.id || list[0]?.id
    if (id) {
      const next = await request<Detail>(`/workflows/${id}`)
      setDetail(next)
    } else {
      setDetail(null)
    }
  }

  async function execute(action: () => Promise<void>) {
    setBusy(true)
    setError('')
    setNotice('')
    try { await action() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Unexpected error') }
    finally { setBusy(false) }
  }

  async function connect() {
    await execute(async () => {
      await refresh()
      setConnected(true)
      setNotice('Connected. Select a workflow to inspect its event history.')
    })
  }

  async function create() {
    await execute(async () => {
      const created = await request<Workflow>('/workflows', 'POST', { name: newName.trim() })
      setPreview(null)
      await refresh(created.id)
      setNotice('Workflow created with an initial durable event.')
    })
  }

  async function mutate(path: string, body?: object, message?: string) {
    if (!detail) return
    await execute(async () => {
      await request<Workflow>(`/workflows/${detail.workflow.id}${path}`, 'POST', body)
      await refresh(detail.workflow.id)
      setPreview(null)
      if (message) setNotice(message)
    })
  }

  async function inspect() {
    if (!detail) return
    await execute(async () => {
      const result = await request<Preview>(`/workflows/${detail.workflow.id}/recovery-preview`)
      setPreview(result)
      setNotice(result.action === 'NONE' ? 'No recovery is needed.' : 'Read-only reconstruction completed.')
    })
  }

  async function approve() {
    if (!detail || !preview || preview.action === 'NONE') return
    await mutate('/recover', { action: preview.action, expectedSequence: preview.expectedSequence },
      'Approved recovery was recorded. You can inspect the new audit event.')
  }

  const current = detail?.workflow

  return (
    <main className="shell">
      <header className="header">
        <div className="brand"><div className="brand-icon">R<span>.</span></div><div>
          <div className="eyebrow">ENGINEERING LAB / 001</div>
          <h1>RESURRECTION</h1>
          <div className="brand-subtitle">Software that can reconstruct its own failure.</div>
        </div></div>
        <div className="system-pill"><span className="signal" /> {connected ? 'OPERATOR CONNECTED' : 'LOCAL DEMO'}</div>
      </header>

      <section className="intro">
        <div><div className="eyebrow accent">EVENT-SOURCED RECOVERY · SIMULATED OPERATIONS</div>
          <h2>Break the workflow.<br /><em>Understand why.</em> Recover safely.</h2>
          <p>Every transition creates an immutable-intent event. When something goes wrong, replay the history, inspect a proposed fix, and approve recovery yourself.</p>
        </div>
        <div className="statline">
          <div><b>{workflows.length}</b><span>WORKFLOWS</span></div>
          <div><b>{workflows.filter(w => w.status === 'FAILED').length}</b><span>FAILED</span></div>
          <div><b>{workflows.filter(w => w.status === 'COMPLETED').length}</b><span>COMPLETE</span></div>
        </div>
      </section>

      <section className="credentials panel">
        <div><div className="eyebrow">01 / CONNECT</div><h3>Operator access</h3><p>Basic authentication for a local-only demo. Credentials are kept in this page's memory.</p></div>
        <form onSubmit={e => { e.preventDefault(); void connect() }} className="credential-form">
          <label>USERNAME<input value={username} autoComplete="username" onChange={e => setUsername(e.target.value)} /></label>
          <label>PASSWORD<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} placeholder="dev-only-change-me" /></label>
          <button className="btn btn-primary" disabled={busy || !password}>Connect</button>
        </form>
      </section>

      {error && <div role="alert" className="alert error">⚠ {error}</div>}
      {notice && <div role="status" className="alert notice">✓ {notice}</div>}

      {connected && <div className="workspace">
        <aside className="left panel">
          <div className="panel-heading"><div><div className="eyebrow">02 / WORKFLOWS</div><h3>Experiments</h3></div>
            <button className="btn btn-small btn-quiet" onClick={() => void execute(async () => { await refresh(); setNotice('Data refreshed.') })} disabled={busy}>↻ Refresh</button></div>
          <form className="new-form" onSubmit={e => { e.preventDefault(); void create() }}>
            <label>NEW WORKFLOW NAME<input maxLength={100} value={newName} onChange={e => setNewName(e.target.value)} /></label>
            <button className="btn btn-primary" disabled={busy || !newName.trim()}>+ Create workflow</button>
          </form>
          <div className="workflow-list">
            {workflows.map(workflow => <button key={workflow.id}
              className={`workflow-card ${current?.id === workflow.id ? 'selected' : ''}`}
              onClick={() => void execute(async () => { await refresh(workflow.id); setPreview(null) })} disabled={busy}>
              <span className="workflow-card-top"><b>{workflow.name}</b><span className={`badge status-${workflow.status.toLowerCase()}`}>{workflow.status}</span></span>
              <span className="workflow-card-meta">{workflow.step} · {workflow.lastSequence} EVENTS</span>
            </button>)}
          </div>
        </aside>

        <div className="right">
          {current ? <>
            <section className="panel stage-panel">
              <div className="panel-heading"><div><div className="eyebrow">03 / CURRENT PROJECTION</div><h3>{current.name}</h3></div>
                <span className={`badge large status-${current.status.toLowerCase()}`}>{current.status}</span></div>
              <div className="stepper">{steps.map((step, index) => <div className={`step ${index <= current.stepNumber ? 'reached' : ''}`} key={step}>
                <div className="step-dot">{index < current.stepNumber ? '✓' : String(index + 1).padStart(2, '0')}</div>
                <span>{step}</span>
              </div>)}</div>
              <div className="action-row">
                <button className="btn btn-primary" disabled={busy || current.status !== 'ACTIVE'}
                  onClick={() => void mutate('/advance')}>Advance one step →</button>
                <button className="btn" disabled={busy || current.status !== 'ACTIVE' || current.failNextStep}
                  onClick={() => void mutate('/faults', { type: 'FAIL_NEXT_STEP' }, 'Failure armed. Click Advance to trigger it.')}>Arm next-step failure</button>
                <button className="btn btn-danger" disabled={busy} onClick={() => void mutate('/faults',
                  { type: 'CORRUPT_PROJECTION' }, 'Projection corrupted. Try inspecting recovery.')}>Corrupt projection</button>
              </div>
              {current.failNextStep && <p className="tip">⚠ One-time fault armed: the next advance will fail before committing its transition.</p>}
              <p className="hint">All faults are simulated. No external systems or shipments are affected.</p>
            </section>

            <section className="panel recovery-panel">
              <div className="panel-heading"><div><div className="eyebrow">04 / RECONSTRUCTION</div><h3>Recovery inspection</h3></div>
                <button className="btn btn-outline" disabled={busy} onClick={() => void inspect()}>Inspect & replay →</button></div>
              {preview ? <div className="preview">
                <div className="preview-top"><span className={`badge ${preview.projectionMatches ? 'status-active' : 'status-failed'}`}>
                  {preview.projectionMatches ? 'PROJECTION VERIFIED' : 'DRIFT DETECTED'}</span>
                  <span className="mono">SEQ {String(preview.expectedSequence).padStart(3, '0')} · {preview.verifiedCheckpoints} CHECKPOINTS</span></div>
                <p>{preview.diagnosis}</p>
                <div className="expected"><span>REPLAY EXPECTS</span><b>{preview.expectedStep} / {preview.expectedStatus}</b></div>
                <div className="expected"><span>PROPOSED FIX DRY RUN</span><b>{preview.simulatedStep} / {preview.simulatedStatus} ✓</b></div>
                {preview.action !== 'NONE' ? <div className="approval"><div><div className="eyebrow">PROPOSED RECOVERY</div>
                    <strong>{preview.action.replaceAll('_', ' ')}</strong><small>Read-only preview first. Approval writes an audit event.</small></div>
                    <button className="btn btn-primary" onClick={() => void approve()} disabled={busy}>Approve recovery</button></div>
                  : <p className="all-good">✓ No intervention needed.</p>}
              </div> : <p className="empty">Run replay to verify the event hash chain and checkpoints, then compare the expected state with the stored projection.</p>}
            </section>

            <section className="panel log-panel">
              <div className="panel-heading"><div><div className="eyebrow">05 / APPEND-ONLY HISTORY</div><h3>Event timeline</h3></div>
                <span className="mono">{detail?.events.length ?? 0} EVENTS · {detail?.checkpointCount ?? 0} CHECKPOINTS</span></div>
              <div className="timeline">{detail?.events.map(event => <div className="event" key={event.sequence}>
                <span className="event-number">{String(event.sequence).padStart(3, '0')}</span>
                <div><div className="event-line"><strong>{event.type.replaceAll('_', ' ')}</strong><time>{new Date(event.createdAt).toLocaleString()}</time></div>
                  <p>{event.message}</p><code title={event.hash}>sha256:{event.hash.slice(0, 16)}…</code></div>
              </div>)}</div>
            </section>
          </> : <div className="panel empty">Choose or create a workflow.</div>}
        </div>
      </div>}
      <footer>RESURRECTION // A CONTROLLED FAILURE-RECOVERY DEMONSTRATION · JAVA + REACT + POSTGRESQL</footer>
    </main>
  )
}
