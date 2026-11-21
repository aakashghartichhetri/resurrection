import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { buildEvidence, formatDate, formatTime, recoveryLabel } from './evidence'
import { explainApiError } from './httpErrors'
import { filterFleet, healthLabels, needsAttention, pipeline, triage } from './operations'
import type { FleetFilter } from './operations'
import type { Detail, FaultAudit, HealthState, Operations, Preview, Workflow, WorkflowEvent, WorkflowHealth, WorkflowStatus } from './types'

type Page = 'overview' | 'workflows' | 'investigate' | 'activity' | 'guide'
type Fault = 'FAIL_NEXT_STEP' | 'CORRUPT_PROJECTION'
type Dialog = 'create' | 'fault' | 'approve' | null
type ActivityTab = 'events' | 'approvals' | 'faults'
type IconName = 'home' | 'layers' | 'shield' | 'clock' | 'book' | 'plus' | 'search' | 'refresh' | 'check' | 'warning' | 'x' | 'play' | 'download' | 'arrow' | 'lock' | 'logout' | 'menu' | 'box' | 'database' | 'pulse' | 'filter' | 'copy' | 'eye' | 'bolt' | 'calendar' | 'check-circle' | 'external' | 'info' | 'chevron' | 'server' | 'stop'

const stages = ['CREATED', 'VALIDATED', 'PACKED', 'SHIPPED']
const pages: { id: Page; label: string; icon: IconName }[] = [
  { id: 'overview', label: 'Overview', icon: 'home' },
  { id: 'workflows', label: 'Workflow register', icon: 'layers' },
  { id: 'investigate', label: 'Investigations', icon: 'shield' },
  { id: 'activity', label: 'Audit history', icon: 'clock' },
  { id: 'guide', label: 'Practice a recovery', icon: 'book' }
]
const pageInfo: Record<Page, { title: string; subtitle: string; eyebrow: string }> = {
  overview: { title: 'Operations overview', subtitle: 'Understand what happened, what needs attention, and what to do next.', eyebrow: 'YOUR WORKSPACE / OVERVIEW' },
  workflows: { title: 'Workflow register', subtitle: 'Search dispatches, inspect progress, and continue the work safely.', eyebrow: 'YOUR WORKSPACE / DISPATCHES' },
  investigate: { title: 'Recovery investigation', subtitle: 'Reconstruct the last safe state and review the evidence before acting.', eyebrow: 'YOUR WORKSPACE / RECOVERY' },
  activity: { title: 'Activity & audit', subtitle: 'A clear record of transitions, fault tests, and recovery decisions.', eyebrow: 'YOUR WORKSPACE / AUDIT' },
  guide: { title: 'Practice a recovery', subtitle: 'Try a controlled incident from start to finish, with no external side effects.', eyebrow: 'GUIDED EXERCISE / SCENARIO' }
}
const eventLabel: Record<string, string> = {
  CREATED: 'Dispatch created', ADVANCED: 'Step committed', FAULT_ARMED: 'Failure armed', STEP_FAILED: 'Transition failed',
  RECOVERED: 'Work resumed', PROJECTION_REPAIRED: 'Stored state rebuilt'
}
const statusLabel: Record<WorkflowStatus, string> = { ACTIVE: 'In progress', FAILED: 'Stopped', COMPLETED: 'Complete' }

function Icon({ name, size = 18, className = '' }: { name: IconName; size?: number; className?: string }) {
  const paths: Record<IconName, ReactNode> = {
    home: <><rect x="3" y="3" width="7" height="7" rx="1.3"/><rect x="14" y="3" width="7" height="7" rx="1.3"/><rect x="3" y="14" width="7" height="7" rx="1.3"/><rect x="14" y="14" width="7" height="7" rx="1.3"/></>,
    layers: <><rect x="3" y="4" width="18" height="6" rx="1.6"/><rect x="3" y="14" width="18" height="6" rx="1.6"/></>,
    shield: <><path d="M12 2.7 20 6v5.7c0 4.5-3.2 7.7-8 9.6-4.8-1.9-8-5.1-8-9.6V6Z"/><path d="m8.5 11.8 2.6 2.6 4.9-5"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></>,
    book: <><path d="M4 5a2 2 0 0 1 2-2h14v17H6a2 2 0 0 0-2 2z"/><path d="M4 5v17M8 8h8M8 12h6"/></>,
    plus: <path d="M12 5v14M5 12h14"/>,
    search: <><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.3 9a7 7 0 0 1 12-2l2.7 5M4 12l2.7 5a7 7 0 0 0 12-2"/></>,
    check: <path d="m5 12 4 4 10-10"/>, warning: <><path d="m12 3 10 18H2z"/><path d="M12 9v5m0 3v.5"/></>,
    x: <path d="M5 5 19 19M19 5 5 19"/>, play: <path d="m8 5 11 7-11 7z"/>,
    download: <><path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/></>,
    arrow: <path d="M5 12h14m-6-6 6 6-6 6"/>,
    lock: <><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></>,
    logout: <><path d="M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5"/><path d="m15 7 5 5-5 5M9 12h11"/></>,
    menu: <path d="M4 6h16M4 12h16M4 18h16"/>, box: <><path d="m12 2 9 5-9 5-9-5zM3 7v10l9 5 9-5V7M12 12v10"/></>,
    database: <><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/></>,
    pulse: <path d="M2 12h4l3-7 5 14 3-7h5"/>, filter: <path d="M4 6h16M7 12h10M10 18h4"/>,
    copy: <><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></>,
    eye: <><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></>,
    bolt: <path d="m13 2-9 12h7l-1 8 10-12h-7z"/>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 2v6M17 2v6M3 10h18"/></>,
    'check-circle': <><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></>,
    external: <><path d="M13 5h6v6M19 5l-9 9"/><path d="M18 14v5H5V6h5"/></>,
    info: <><circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/></>,
    chevron: <path d="m9 5 7 7-7 7"/>,
    server: <><rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 17.5h.01M11 7.5h7M11 17.5h7"/></>,
    stop: <rect x="6" y="6" width="12" height="12" rx="2"/>
  }
  return <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>
}

function BrandMark() {
  return <span className="resurrection-mark" aria-hidden="true"><svg viewBox="0 0 64 64" focusable="false">
    <rect x="3" y="3" width="58" height="58" rx="18" fill="currentColor"/>
    <path d="M20 47V18h15c8 0 12 4 12 11 0 7-4 10-12 10H20m16-1 12 10" fill="none" stroke="#FFFFFF" strokeWidth="5.8" strokeLinecap="round" strokeLinejoin="round"/>
    <circle cx="48" cy="15" r="5" fill="#FFD478"/>
  </svg></span>
}

function HealthTag({ state }: { state: HealthState }) {
  return <span className={`health-tag health-${state.toLowerCase()}`}><span className="tag-dot"/>{healthLabels[state]}</span>
}
function StatusTag({ status }: { status: WorkflowStatus }) {
  return <span className={`status-tag status-${status.toLowerCase()}`}><span className="tag-dot"/>{statusLabel[status]}</span>
}
function CardTitle({ eyebrow, title, right }: { eyebrow?: string; title: string; right?: ReactNode }) {
  return <div className="card-title"><div>{eyebrow && <span className="overline">{eyebrow}</span>}<h2>{title}</h2></div>{right}</div>
}
function Empty({ icon, title, description, action }: { icon: IconName; title: string; description: string; action?: ReactNode }) {
  return <div className="empty"><span className="empty-icon"><Icon name={icon} size={25}/></span><strong>{title}</strong><p>{description}</p>{action}</div>
}
function Modal({ title, subtitle, children, close }: { title: string; subtitle: string; children: ReactNode; close: () => void }) {
  const dialogRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const selector = 'button:not([disabled]), input:not([disabled])'
    const firstControl = dialogRef.current?.querySelector<HTMLElement>('[data-initial-focus]')
      ?? dialogRef.current?.querySelector<HTMLElement>(selector)
    firstControl?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return
      const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(selector) ?? [])
      if (!focusable.length) return
      const first = focusable[0], last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = originalOverflow; previous?.focus() }
  }, [])
  return <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) close() }}>
    <section role="dialog" aria-modal="true" aria-labelledby="modal-title" className="dialog" ref={dialogRef}>
      <div className="dialog-header"><div><span className="overline">OPERATOR ACTION</span><h2 id="modal-title">{title}</h2><p>{subtitle}</p></div><button className="icon-button" onClick={close} aria-label="Close dialog"><Icon name="x"/></button></div>
      {children}
    </section>
  </div>
}
function Timeline({ events, onSelect, limit = 100 }: { events: WorkflowEvent[]; onSelect?: () => void; limit?: number }) {
  if (!events.length) return <Empty icon="clock" title="Nothing recorded yet" description="Committed transitions and failures will appear here."/>
  return <div className="timeline">{[...events].reverse().slice(0, limit).map(e => <div className="timeline-row" key={e.sequence}>
    <div className={`timeline-glyph ${e.type === 'STEP_FAILED' ? 'timeline-alert' : ''}`}><Icon name={e.type === 'STEP_FAILED' ? 'warning' : e.type === 'PROJECTION_REPAIRED' || e.type === 'RECOVERED' ? 'shield' : 'check'} size={14}/></div>
    <div className="timeline-text"><div className="timeline-row-top"><strong>{eventLabel[e.type] ?? e.type}</strong><time title={formatDate(e.createdAt)}>{formatTime(e.createdAt)}</time></div><p>{e.message}</p><span className="mono light">EVENT #{String(e.sequence).padStart(3, '0')} · {e.hash.slice(0, 12)}</span></div>
  </div>)}{onSelect && events.length > limit && <button className="text-link" onClick={onSelect}>See all {events.length} events <Icon name="arrow" size={15}/></button>}</div>
}
function Progress({ stepNumber, status }: { stepNumber: number; status: WorkflowStatus }) {
  return <div className="stage-track">{stages.map((stage, index) => <div className={`stage ${index < stepNumber ? 'stage-done' : index === stepNumber ? 'stage-now' : 'stage-later'}`} key={stage}>
    <span className="stage-symbol">{index < stepNumber ? <Icon name="check" size={16}/> : String(index + 1).padStart(2, '0')}</span>
    <div><span>{stage.charAt(0) + stage.slice(1).toLowerCase()}</span>{index === stepNumber && <small>{status === 'FAILED' ? 'Stopped here' : status === 'COMPLETED' ? 'Finished' : 'Current step'}</small>}</div>
  </div>)}</div>
}
function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)
  return <button className="icon-button small" title={copied ? 'Copied' : 'Copy'} aria-label="Copy value" onClick={() => {
    if (navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(value).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1600) }).catch(() => setCopied(false))
    }
  }}><Icon name={copied ? 'check' : 'copy'} size={15}/></button>
}

export default function App() {
  const [username, setUsername] = useState('resurrection')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [connected, setConnected] = useState(false)
  const [online, setOnline] = useState(true)
  const [page, setPage] = useState<Page>('overview')
  const [mobileNav, setMobileNav] = useState(false)
  const [operations, setOperations] = useState<Operations | null>(null)
  const [rows, setRows] = useState<Workflow[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [faultsEnabled, setFaultsEnabled] = useState(false)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<FleetFilter>('ALL')
  const [activityTab, setActivityTab] = useState<ActivityTab>('events')
  const [dialog, setDialog] = useState<Dialog>(null)
  const [newName, setNewName] = useState('')
  const [fault, setFault] = useState<Fault>('FAIL_NEXT_STEP')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [lastSync, setLastSync] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)
  const busyRef = useRef(false)
  const selectedRef = useRef<string | null>(null)
  const requestVersion = useRef(0)
  const current = detail?.workflow ?? null
  const health = operations?.workflows.find(w => w.id === selectedId) ?? null
  const filtered = useMemo(() => filterFleet(operations?.workflows ?? [], query, filter), [operations, query, filter])
  const urgent = useMemo(() => triage(operations?.workflows ?? []).filter(w => needsAttention(w.state)), [operations])
  const stagesData = useMemo(() => pipeline(rows), [rows])
  const totalMax = Math.max(1, ...stagesData.map(d => d.value))
  const hasStalePreview = !!(preview && current && current.lastSequence !== preview.expectedSequence)

  const request = useCallback(async <T,>(path: string, method = 'GET', body?: object, key?: string): Promise<T> => {
    const raw = new TextEncoder().encode(`${username}:${password}`)
    const encoded = btoa(Array.from(raw, byte => String.fromCharCode(byte)).join(''))
    const response = await fetch('/api' + path, {
      method,
      headers: {
        Authorization: `Basic ${encoded}`,
        'X-Request-Id': crypto.randomUUID(),
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(key ? { 'Idempotency-Key': key } : {})
      },
      ...(body ? { body: JSON.stringify(body) } : {}), cache: 'no-store'
    })
    if (!response.ok) {
      const data = await response.json().catch(() => ({})) as { error?: string }
      throw new Error(explainApiError(response.status, data.error, response.headers.get('X-Request-Id')))
    }
    return response.json() as Promise<T>
  }, [username, password])

  const reload = useCallback(async (preferred?: string, clearPreview = false) => {
    const serial = ++requestVersion.current
    const [op, workflows] = await Promise.all([
      request<Operations>('/operations'), request<Workflow[]>('/workflows')
    ])
    const priorId = selectedRef.current
    const id = preferred ?? priorId ?? workflows[0]?.id ?? null
    const selected = id ? await request<Detail>(`/workflows/${id}`) : null
    if (serial !== requestVersion.current) return
    setOperations(op)
    setRows(workflows)
    setSelectedId(id)
    selectedRef.current = id
    setDetail(selected)
    if (clearPreview || id !== priorId) setPreview(null)
    setLastSync(op.checkedAt)
    setOnline(true)
  }, [request])

  const run = async (task: () => Promise<void>) => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setNotice('')
    setError('')
    try { await task() }
    catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong. Please try again.') }
    finally { busyRef.current = false; setBusy(false) }
  }

  const signIn = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void run(async () => {
      await reload(undefined, true)
      const settings = await request<{ faultInjectionEnabled: boolean }>('/settings')
      setFaultsEnabled(settings.faultInjectionEnabled)
      setConnected(true)
      setNotice('')
    })
  }
  const signOut = () => {
    ++requestVersion.current
    setConnected(false)
    setPassword('')
    setOperations(null)
    setRows([])
    setSelectedId(null)
    selectedRef.current = null
    setDetail(null)
    setPreview(null)
    setPage('overview')
    setNotice('')
    setError('')
  }
  const go = (next: Page) => { setPage(next); setMobileNav(false); setError('') }
  const findWorkflow = () => { go('workflows'); window.setTimeout(() => searchRef.current?.focus(), 30) }
  const selectWorkflow = (id: string, destination: Page = 'workflows') => {
    selectedRef.current = id
    setSelectedId(id)
    setDetail(null)
    setPreview(null)
    go(destination)
    void run(async () => await reload(id, true))
  }
  const inspect = (id = selectedId) => {
    if (!id) return
    void run(async () => {
      const result = await request<Preview>(`/workflows/${id}/recovery-preview`)
      setPreview(result)
      go('investigate')
      setNotice(result.action === 'NONE' ? 'Replay finished. No repair is needed.' : 'Replay finished. Review the proposed recovery below.')
    })
  }
  const advance = () => {
    if (!selectedId) return
    void run(async () => {
      await request(`/workflows/${selectedId}/advance`, 'POST', undefined, crypto.randomUUID())
      await reload(selectedId, true)
      setNotice('Transition committed. The event was saved to PostgreSQL.')
    })
  }
  const create = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const name = newName.trim()
    if (!name) return
    void run(async () => {
      const workflow = await request<Workflow>('/workflows', 'POST', { name })
      await reload(workflow.id, true)
      setDialog(null)
      setNewName('')
      go('workflows')
      setNotice('Dispatch created. Its first event is now in the history.')
    })
  }
  const inject = () => {
    if (!selectedId) return
    void run(async () => {
      await request(`/workflows/${selectedId}/faults`, 'POST', { type: fault })
      await reload(selectedId, true)
      setDialog(null)
      if (fault === 'CORRUPT_PROJECTION') {
        const result = await request<Preview>(`/workflows/${selectedId}/recovery-preview`)
        setPreview(result)
        go('investigate')
        setNotice('Stored state changed. Replay found the mismatch; new transitions are blocked.')
      } else setNotice('Failure armed. Advance the workflow to record a failed transition.')
    })
  }
  const approve = () => {
    if (!selectedId || !preview || preview.action === 'NONE' || hasStalePreview) return
    void run(async () => {
      await request(`/workflows/${selectedId}/recover`, 'POST', {
        action: preview.action,
        expectedSequence: preview.expectedSequence,
        expectedHash: preview.expectedHash,
        projectionFingerprint: preview.projectionFingerprint
      })
      await reload(selectedId, true)
      const next = await request<Preview>(`/workflows/${selectedId}/recovery-preview`)
      setPreview(next)
      setDialog(null)
      go('investigate')
      setNotice('Recovery recorded. Replay now confirms the stored state is consistent.')
    })
  }
  const runDrill = () => {
    if (!faultsEnabled) return
    void run(async () => {
      const created = await request<Workflow>('/workflows', 'POST', { name: `Dispatch drill · ${new Date().toLocaleString('en', { dateStyle: 'short', timeStyle: 'short' })}` })
      await reload(created.id, true)
      await request(`/workflows/${created.id}/advance`, 'POST', undefined, crypto.randomUUID())
      await request(`/workflows/${created.id}/faults`, 'POST', { type: 'FAIL_NEXT_STEP' })
      await request(`/workflows/${created.id}/advance`, 'POST', undefined, crypto.randomUUID())
      await reload(created.id, true)
      const result = await request<Preview>(`/workflows/${created.id}/recovery-preview`)
      setPreview(result)
      go('investigate')
      setNotice('Demo ready. A dispatch failed after validation. Review its evidence before approving recovery.')
    })
  }
  const exportReport = () => {
    if (!detail) return
    void run(async () => {
      let result = preview
      if (!result) {
        try { result = await request<Preview>(`/workflows/${detail.workflow.id}/recovery-preview`) }
        catch { result = null } // Keep the raw history even when replay reports an integrity error.
      }
      const data = buildEvidence(detail, result, new Date().toISOString())
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `resurrection-${detail.workflow.id.slice(0, 8)}-evidence.json`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
      setNotice(result ? 'Evidence exported with a verified replay.' : 'Raw history exported. Replay could not be verified; see report limitations.')
    })
  }

  useEffect(() => {
    if (!connected) return
    const interval = window.setInterval(() => {
      if (document.visibilityState !== 'visible' || busyRef.current) return
      void reload().catch(() => setOnline(false))
    }, 15000)
    return () => window.clearInterval(interval)
  }, [connected, reload])
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setDialog(null); setMobileNav(false) }
      if (event.key === '/' && connected && !dialog && !['INPUT', 'TEXTAREA'].includes((event.target as HTMLElement)?.tagName)) {
        event.preventDefault()
        go('workflows')
        window.setTimeout(() => searchRef.current?.focus(), 20)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [connected, dialog])

  if (!connected) return <div className="login-page">
    <header className="login-header">
      <div className="login-brand">
        <BrandMark/>
        <div>
          <strong>RESURRECTION</strong>
          <span>Workflow recovery, made clear.</span>
        </div>
      </div>
      <div className="login-edition"><span className="edition-rule"/> OPERATIONS PLATFORM <b> / </b> REPLAY & RECOVERY</div>
    </header>

    <main className="login-main">
      <section className="login-intro" aria-labelledby="login-headline">
        <span className="login-eyebrow"><span className="login-eyebrow-line"/> Resilient operations, made human</span>
        <h1 id="login-headline">Know what failed.<br/><span>Recover with confidence.</span></h1>
        <p className="login-lede">See the events behind a failed workflow. Rebuild the last trustworthy state, then make a considered decision about what happens next.</p>

        <div className="login-value-grid" aria-label="How the platform helps">
          <div><span className="value-glyph value-teal"><Icon name="database" size={17}/></span><strong>Trace</strong><small>Follow every committed step</small></div>
          <div><span className="value-glyph value-purple"><Icon name="shield" size={17}/></span><strong>Verify</strong><small>Check events and checkpoints</small></div>
          <div><span className="value-glyph value-amber"><Icon name="check-circle" size={17}/></span><strong>Restore</strong><small>Approve the right next action</small></div>
        </div>
        <div className="login-sample" aria-label="Example incident">
          <div className="login-sample-header"><div><span className="login-small-label">A RECONSTRUCTED INCIDENT / EXAMPLE</span><strong>Warehouse dispatch A-104</strong></div><span className="login-incident-tag"><Icon name="warning" size={14}/> Needs review</span></div>
          <div className="login-sample-steps">
            <div><span className="login-step-icon is-done"><Icon name="check" size={16}/></span><span>Created</span><small>Recorded</small></div>
            <div><span className="login-step-icon is-done"><Icon name="check" size={16}/></span><span>Validated</span><small>Recorded</small></div>
            <div><span className="login-step-icon is-stopped"><Icon name="warning" size={16}/></span><span>Packing</span><small>Interrupted</small></div>
          </div>
          <div className="login-sample-footer"><span><Icon name="shield" size={17}/> Last confirmed state</span><strong>Validated</strong></div>
        </div>
      </section>

      <section className="login-card" aria-labelledby="login-title">
        <div className="login-card-icon"><Icon name="lock" size={21}/></div>
        <span className="login-small-label">SECURE WORKSPACE ACCESS</span>
        <h2 id="login-title">Your operations desk, ready.</h2>
        <p>Sign in to inspect dispatches, investigate failures, and keep a clear audit trail.</p>
        <form className="login-form" onSubmit={signIn}>
          <label htmlFor="user">Username</label>
          <input id="user" name="username" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} maxLength={100} required/>
          <div className="login-label-row"><label htmlFor="pass">Password</label><span>Set in your .env file</span></div>
          <div className="login-password-field"><input id="pass" name="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required/><button type="button" onClick={() => setShowPassword(v => !v)} aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword}>{showPassword ? 'Hide' : 'Show'}</button></div>
          {error && <div role="alert" className="inline-error"><Icon name="warning" size={17}/>{error}</div>}
          <button className="btn btn-primary login-submit" disabled={busy} type="submit">{busy ? 'Signing in…' : 'Open workspace'}</button>
        </form>
        <div className="login-card-note"><Icon name="shield" size={17}/><span>Credentials stay in memory during this session. No account is created.</span></div>
      </section>
    </main>

    <section className="login-how" aria-label="How recovery works">
      <div className="login-how-heading"><span className="login-small-label">HOW RECOVERY WORKS</span><p>Three steps, one decision point</p></div>
      <div className="login-how-grid">
        <div><span className="login-how-number">01</span><div><strong>Read the record</strong><p>Review the durable event stream and the last trusted checkpoint.</p></div></div>
        <div><span className="login-how-number">02</span><div><strong>Reconstruct the state</strong><p>Replay the history to determine what the application can actually prove.</p></div></div>
        <div><span className="login-how-number">03</span><div><strong>Approve the fix</strong><p>Only after inspection does the operator commit a recovery action.</p></div></div>
      </div>
    </section>
    <footer className="login-footer"><span>RESURRECTION · Recovery engineering lab</span><span>Simulated dispatches · No external systems are changed</span></footer>
  </div>

  return <div className="shell">
    {mobileNav && <button className="nav-scrim" aria-label="Close navigation" onClick={() => setMobileNav(false)}/>}
    <aside className={`sidebar ${mobileNav ? 'sidebar-open' : ''}`}>
      <div className="brand"><BrandMark/><div><strong>RESURRECTION</strong><small>WORKFLOW INTELLIGENCE</small></div></div>
      <div className="side-label">YOUR TOOLS</div>
      <nav aria-label="Primary navigation" className="primary-nav">{pages.map(item => <button key={item.id} className={`nav-item ${page === item.id ? 'nav-active' : ''}`} aria-current={page === item.id ? 'page' : undefined} onClick={() => go(item.id)}><Icon name={item.icon} size={19}/><span>{item.label}</span>{item.id === 'overview' && !!operations?.attention && <em className="nav-alert-count">{operations.attention}</em>}</button>)}</nav>
      <div className="sidebar-bottom"><div className="side-foot-card"><div className="side-foot-top"><Icon name="pulse" size={18}/><span>GOOD RECOVERY STARTS HERE</span></div><strong>Evidence before action.</strong><p>Understand the event history before changing a saved workflow.</p><button onClick={() => go('guide')}>Try a guided recovery <Icon name="arrow" size={14}/></button></div></div>
    </aside>
    <div className="main-wrap">
      <header className="topbar"><div className="topbar-left"><button className="icon-button mobile-menu" aria-label="Open navigation" aria-expanded={mobileNav} onClick={() => setMobileNav(true)}><Icon name="menu" size={22}/></button><div className="crumbs"><span>Workspace</span><Icon name="chevron" size={15}/><strong>{pages.find(p => p.id === page)?.label}</strong></div></div>
        <div className="topbar-right"><button className="global-search" onClick={findWorkflow} aria-label="Search workflows"><Icon name="search" size={17}/><span>Find a workflow</span><kbd>/</kbd></button><div className="operator"><span className="operator-avatar">{username.charAt(0).toUpperCase()}</span><span>{username}<small>Operator</small></span></div><button className="icon-button" title="Sign out" aria-label="Sign out" onClick={signOut}><Icon name="logout" size={19}/></button></div>
      </header>
      <main className="main-content">
        <div className="page-heading"><div><span className="overline">{pageInfo[page].eyebrow}</span><h1>{pageInfo[page].title}</h1><p>{pageInfo[page].subtitle}</p></div><div className="page-heading-actions">{page !== 'guide' && <button className="btn btn-outline" onClick={() => void run(() => reload())} disabled={busy}><Icon name="refresh" size={16}/>Refresh</button>}<button className="btn btn-primary" onClick={() => setDialog('create')}><Icon name="plus" size={17}/>New workflow</button></div></div>
        {!online && <div className="banner banner-warning" role="status"><Icon name="warning" size={18}/>The API is not responding. The last successful data is still shown; actions may fail until the connection returns.</div>}
        {error && <div className="banner banner-error" role="alert"><Icon name="warning" size={18}/><span>{error}</span><button aria-label="Dismiss error" onClick={() => setError('')}><Icon name="x" size={17}/></button></div>}
        {notice && <div className="toast" role="status"><Icon name="check-circle" size={18}/><span>{notice}</span><button aria-label="Dismiss notice" onClick={() => setNotice('')}><Icon name="x" size={16}/></button></div>}

        {page === 'overview' && <>
          <section className="hero">
            <div className="hero-body">
              <span className="hero-kicker"><span className="hero-kicker-dot"/> THE RECOVERY WORKSPACE</span>
              <h2>Less guesswork.<br/><em>More certainty.</em></h2>
              <p>See exactly where a workflow stopped, verify the history, and decide what should happen next—all in one place.</p>
              <div className="hero-actions"><button className="btn btn-light" onClick={() => { setFilter('ALL'); go('workflows') }}><Icon name="layers" size={16}/>Review workflows</button><button className="btn btn-ghost-light" onClick={runDrill} disabled={!faultsEnabled || busy}><Icon name="play" size={15}/>{faultsEnabled ? 'Run a recovery drill' : 'Drills unavailable'}</button></div>
              <p className="hero-footnote">Event replay · Verified checkpoints · Reviewed recovery</p>
            </div>
            <div className="hero-art">
              <div className={`priority-feature ${urgent.length ? 'priority-issue' : 'priority-clear'}`}>
                <div className="priority-feature-header"><span className="priority-label">NEXT TO REVIEW</span><span className="priority-index">{urgent.length ? `01 / ${String(urgent.length).padStart(2, '0')}` : 'ALL CLEAR'}</span></div>
                <span className={`priority-feature-icon ${urgent.length ? 'priority-feature-warning' : ''}`}><Icon name={urgent.length ? 'warning' : 'check-circle'} size={23}/></span>
                <h3>{urgent[0]?.name ?? 'No incidents need attention'}</h3>
                <p>{urgent[0]?.reason ?? 'The latest check found no workflows that require operator review.'}</p>
                {urgent[0] && <div className="priority-feature-health"><HealthTag state={urgent[0].state}/><span>Recorded #{urgent[0].lastSequence}</span></div>}
                <button className="priority-feature-action" onClick={() => urgent[0] ? selectWorkflow(urgent[0].id, 'investigate') : go('workflows')}>
                  {urgent[0] ? 'Open this investigation' : 'Explore all workflows'} <Icon name="arrow" size={18}/>
                </button>
              </div>
            </div>
          </section>
          <div className="stat-grid" aria-label="Workflow overview">
            <button className="stat-card stat-total" onClick={() => { setFilter('ALL'); go('workflows') }}><div className="stat-head"><span>Total workflows</span><span className="stat-icon"><Icon name="layers" size={20}/></span></div><strong>{operations?.total ?? '—'}</strong><small>View every dispatch <Icon name="arrow" size={13}/></small></button>
            <button className="stat-card stat-attention" onClick={() => { setFilter('ATTENTION'); go('workflows') }}><div className="stat-head"><span>Needs attention</span><span className="stat-icon"><Icon name="warning" size={20}/></span></div><strong>{operations?.attention ?? '—'}</strong><small>Review flagged work <Icon name="arrow" size={13}/></small></button>
            <button className="stat-card stat-completed" onClick={() => { setFilter('COMPLETED'); go('workflows') }}><div className="stat-head"><span>Completed</span><span className="stat-icon"><Icon name="check-circle" size={20}/></span></div><strong>{operations?.completed ?? '—'}</strong><small>See finished dispatches <Icon name="arrow" size={13}/></small></button>
            <button className="stat-card stat-events" onClick={() => go('activity')}><div className="stat-head"><span>Durable events</span><span className="stat-icon"><Icon name="database" size={20}/></span></div><strong>{operations?.totalEvents ?? '—'}</strong><small>Open the audit history <Icon name="arrow" size={13}/></small></button>
          </div>
          <div className="overview-grid">
            <section className="card incident-card"><CardTitle eyebrow="TRIAGE QUEUE" title="Items worth a closer look" right={<span className="card-counter">{urgent.length} flagged</span>}/>
              {urgent.length ? <div className="incident-list">{urgent.slice(0, 7).map(item => <button className="incident-row" key={item.id} onClick={() => selectWorkflow(item.id, 'investigate')}><span className={`incident-icon incident-${item.state.toLowerCase()}`}><Icon name={item.state === 'HISTORY_INVALID' ? 'stop' : 'warning'} size={18}/></span><span className="incident-copy"><strong>{item.name}</strong><small>{item.reason}</small></span><HealthTag state={item.state}/><Icon name="chevron" className="incident-chevron" size={17}/></button>)}</div> : <Empty icon="shield" title="Nothing needs review" description="Every workflow matched its event history during the most recent scan. New failures will appear here automatically."/>}
              <div className="card-bottom"><span>Last checked {operations ? formatTime(operations.checkedAt) : '—'}</span><button className="text-link" onClick={() => { setFilter('ATTENTION'); go('workflows') }}>All workflows <Icon name="arrow" size={15}/></button></div>
            </section>
            <div className="overview-right">
              <section className="card pipeline-card"><CardTitle eyebrow="CURRENT WORKLOAD" title="Where dispatches stand"/><div className="pipeline-bars">{stagesData.map((d, i) => <div className="pipeline-row" key={d.label}><div className="pipeline-label"><span>{String(i + 1).padStart(2, '0')} <strong>{d.label}</strong></span><strong>{d.value}</strong></div><svg className="pipeline-track" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label={`${d.value} ${d.label.toLowerCase()} workflows`}><rect className="pipeline-background" width="100" height="8" rx="4"/><rect className="pipeline-fill" width={Math.max(d.value ? 4 : 0, d.value / totalMax * 100)} height="8" rx="4"/></svg></div>)}</div><p className="card-caption">Counts show the current step, not every step previously visited.</p></section>
              <section className="card coverage-card"><div className="coverage-icon"><Icon name="shield" size={22}/></div><div><span className="overline">LATEST INTEGRITY SCAN</span><h3>{operations ? `${operations.total - operations.attention} of ${operations.total} clear` : 'Checking event history'}</h3><p>{operations?.integrityErrors ? `${operations.integrityErrors} workflow(s) have unverified event history. Recovery is blocked for those records.` : 'The system independently checks history before it proposes a repair.'}</p></div></section>
            </div>
          </div>
          <section className="card recent-card"><CardTitle eyebrow="RECENT ACTIVITY" title="What happened lately" right={<button className="text-link" onClick={() => go('activity')}>Open history <Icon name="arrow" size={15}/></button>}/>
            {operations?.recentActivity.length ? <div className="recent-list">{operations.recentActivity.slice(0, 6).map(item => <button key={`${item.workflowId}-${item.sequence}`} className="recent-row" onClick={() => selectWorkflow(item.workflowId, 'activity')}><span className={`recent-icon ${item.type === 'STEP_FAILED' ? 'recent-failed' : ''}`}><Icon name={item.type === 'STEP_FAILED' ? 'warning' : 'pulse'} size={17}/></span><span className="recent-copy"><strong>{eventLabel[item.type] ?? item.type}</strong><small>{item.workflowName} · Event {item.sequence}</small></span><time title={formatDate(item.createdAt)}>{formatTime(item.createdAt)}</time><Icon name="chevron" className="recent-chevron" size={17}/></button>)}</div> : <Empty icon="clock" title="No recent events" description="Create a dispatch or run a drill to see activity here."/>}
          </section>
        </>}

        {page === 'workflows' && <div className="workspace-grid">
          <section className="card queue-card"><CardTitle eyebrow="DISPATCH REGISTER" title="Saved workflows" right={<span className="card-counter">{filtered.length} shown</span>}/>
            <div className="queue-search"><Icon name="search" size={17}/><input ref={searchRef} aria-label="Search workflows" placeholder="Search by name or ID  /" value={query} onChange={e => setQuery(e.target.value)}/>{query && <button aria-label="Clear search" onClick={() => setQuery('')}><Icon name="x" size={15}/></button>}</div>
            <div className="filter-pills" role="group" aria-label="Filter workflows">{(['ALL', 'ATTENTION', 'ACTIVE', 'COMPLETED'] as FleetFilter[]).map(f => <button key={f} className={filter === f ? 'filter-active' : ''} aria-pressed={filter === f} onClick={() => setFilter(f)}>{({ ALL: 'All', ATTENTION: 'Review', ACTIVE: 'Active', COMPLETED: 'Done' } as const)[f]}</button>)}</div>
            <div className="queue-list">{filtered.length ? filtered.map(item => <button className={`queue-item ${selectedId === item.id ? 'queue-selected' : ''}`} onClick={() => selectWorkflow(item.id)} key={item.id}><span className="queue-item-head"><strong>{item.name}</strong><Icon name="chevron" size={16}/></span><span className="queue-item-bottom"><span>{item.step === 'UNVERIFIED' ? 'Unverified' : item.step.charAt(0) + item.step.slice(1).toLowerCase()} · #{item.lastSequence}</span><HealthTag state={item.state}/></span></button>) : <Empty icon="search" title="No matching workflows" description="Try another search or choose a different filter."/>}</div>
          </section>
          <div className="workspace-detail">{!current ? <section className="card"><Empty icon="layers" title="Choose a workflow" description="Select a dispatch to review its progress and recorded events."/></section> : <>
            <section className="card workflow-card"><div className="workflow-head"><div><span className="overline">DISPATCH / {current.id.slice(0, 8).toUpperCase()}</span><h2>{current.name}</h2><span className="created-time">Created {formatDate(current.createdAt)}</span></div><StatusTag status={current.status}/></div>
              <div className="workflow-meta"><div><span>Last durable step</span><strong>{current.step.charAt(0) + current.step.slice(1).toLowerCase()}</strong></div><div><span>Event position</span><strong>#{String(current.lastSequence).padStart(3, '0')}</strong></div><div><span>Current integrity</span>{health ? <HealthTag state={health.state}/> : <strong>—</strong>}</div></div>
              <div className="block-subtitle"><h3>Fulfillment progress</h3><span>{current.stepNumber + 1} / 4</span></div><Progress stepNumber={current.stepNumber} status={current.status}/>
              <div className="workflow-actions"><div><strong>Next step</strong><p>Advance records a new event and updates the saved state in one transaction.</p></div><button className="btn btn-primary" disabled={busy || current.status !== 'ACTIVE' || health?.state === 'DRIFTED' || health?.state === 'HISTORY_INVALID'} onClick={advance}><Icon name="play" size={15}/>Advance workflow</button></div>
              {current.failNextStep && <div className="banner banner-warning inline-banner"><Icon name="warning" size={17}/>The next transition is set to fail. Advancing will record that failure.</div>}
              <div className="fault-actions"><div><strong>Test a failure</strong><p>Simulation controls affect only this database-backed demo workflow.</p></div><div className="fault-buttons"><button className="btn btn-outline" disabled={busy || !faultsEnabled || current.status !== 'ACTIVE' || current.failNextStep || health?.state === 'DRIFTED' || health?.state === 'HISTORY_INVALID'} onClick={() => { setFault('FAIL_NEXT_STEP'); setDialog('fault') }}><Icon name="bolt" size={16}/>Fail next step</button><button className="btn btn-danger-outline" disabled={busy || !faultsEnabled || health?.state === 'DRIFTED' || health?.state === 'HISTORY_INVALID'} onClick={() => { setFault('CORRUPT_PROJECTION'); setDialog('fault') }}><Icon name="warning" size={16}/>Change stored state</button></div></div>
              {!faultsEnabled && <p className="read-only-hint"><Icon name="lock" size={15}/>Fault injection is disabled on this deployment.</p>}
              <div className="workflow-foot"><button className="text-link" onClick={() => inspect()}><Icon name="shield" size={16}/>Run integrity check <Icon name="arrow" size={14}/></button><button className="text-link" onClick={() => go('activity')}>View all events <Icon name="arrow" size={14}/></button></div>
            </section>
            <section className="card"><CardTitle eyebrow="EVIDENCE" title="Latest recorded events" right={<span className="card-counter">{detail?.events.length ?? 0} total</span>}/><Timeline events={detail?.events ?? []} limit={4} onSelect={() => go('activity')}/></section>
          </>}</div>
        </div>}

        {page === 'investigate' && <>
          <div className="context-strip"><div><span className="overline">CURRENT INVESTIGATION</span><strong>{current?.name ?? 'Select a workflow'}</strong>{current && <small>{current.id}</small>}</div><button className="btn btn-outline" onClick={() => go('workflows')}><Icon name="layers" size={16}/>Choose workflow</button></div>
          {!current ? <section className="card"><Empty icon="shield" title="No workflow selected" description="Choose a dispatch from the workflow register before starting an investigation." action={<button className="btn btn-primary" onClick={() => go('workflows')}>Browse workflows</button>}/></section> : <div className="investigation-grid">
            <section className="card investigation-main"><CardTitle eyebrow="DETERMINISTIC REPLAY" title="Reconstruct from evidence" right={<button className="btn btn-primary" disabled={busy || health?.state === 'HISTORY_INVALID'} onClick={() => inspect()}><Icon name="refresh" size={16}/>{preview ? 'Run replay again' : 'Run integrity check'}</button>}/>
              {health?.state === 'HISTORY_INVALID' && <div className="integrity-blocked"><Icon name="stop" size={23}/><div><strong>History could not be verified</strong><p>{health.reason}. Automatic repair is not available. Review the data and restore it from a trusted backup if necessary.</p></div></div>}
              {!preview ? (health?.state === 'HISTORY_INVALID' ? null : <div className="inspect-placeholder"><span className="inspect-placeholder-icon"><Icon name="eye" size={29}/></span><h3>The event log is ready to check</h3><p>Run replay to verify event order, hashes, and checkpoints. We'll compare the reconstructed state with the saved database projection without changing anything.</p><div className="inspect-placeholder-tags"><span><Icon name="database" size={15}/> Read-only</span><span><Icon name="shield" size={15}/> Checks the full history</span></div></div>) : <>
                <div className={`replay-result ${preview.projectionMatches ? 'replay-match' : 'replay-drift'}`}><span className="replay-result-icon"><Icon name={preview.projectionMatches ? 'check-circle' : 'warning'} size={23}/></span><div><strong>{preview.projectionMatches ? 'Saved state agrees with replay' : 'Saved state does not match replay'}</strong><p>{preview.diagnosis}</p></div></div>
                <div className="compare-grid"><div className="compare-pane"><span className="overline">DATABASE PROJECTION</span><h3>{current.step}</h3><StatusTag status={current.status}/><p>The quick-to-read state currently held in PostgreSQL.</p></div><div className="compare-swap"><Icon name="arrow" size={21}/></div><div className="compare-pane compare-verified"><span className="overline">VERIFIED RECONSTRUCTION</span><h3>{preview.expectedStep}</h3><StatusTag status={preview.expectedStatus}/><p>Recreated by applying every recorded event in order.</p></div></div>
                <div className="verify-meta"><span><Icon name="check-circle" size={16}/>Hash chain verified</span><span><Icon name="database" size={16}/>{preview.verifiedCheckpoints} checkpoints checked</span><span>Through #{preview.expectedSequence}</span></div>
                <div className="proposal"><span className="overline">RECOVERY DECISION</span>{preview.action === 'NONE' ? <div className="proposal-clear"><Icon name="check-circle" size={23}/><div><h3>No repair needed</h3><p>The projection matches its recorded history. The workflow can continue if it's active.</p></div></div> : <><h3>{recoveryLabel(preview.action)}</h3><p>{preview.action === 'REBUILD_PROJECTION' ? 'Rebuild the saved view from verified events. The event log itself stays unchanged, apart from the repair audit event.' : 'The failed transition did not commit. Resume at the last successful step so it can be attempted again.'}</p><div className="proposal-outcome"><span>After approval</span><strong>{preview.simulatedStep} <span>/</span> {statusLabel[preview.simulatedStatus]}</strong></div><button className="btn btn-primary" disabled={busy || hasStalePreview} onClick={() => setDialog('approve')}><Icon name="shield" size={16}/>Review approval</button>{hasStalePreview && <small>New events arrived. Run replay again before approving.</small>}</>}</div>
              </>}
            </section>
            <div className="investigation-aside"><section className="card"><CardTitle eyebrow="CAUSE & EFFECT" title="Causal timeline" right={<span className="card-counter">{preview?.causalTrace.length ?? 0} shown</span>}/><Timeline events={preview?.causalTrace ?? detail?.events.slice(-4) ?? []} limit={6}/></section>
              <section className="card evidence-card"><span className="evidence-card-icon"><Icon name="download" size={22}/></span><h3>Keep the evidence</h3><p>Export the event timeline, recovery decision, and audit records as a readable JSON snapshot.</p><button className="btn btn-outline" onClick={exportReport} disabled={busy}><Icon name="download" size={16}/>Export report</button></section>
            </div>
          </div>}
        </>}

        {page === 'activity' && <>
          <div className="context-strip"><div><span className="overline">WORKFLOW RECORD</span><strong>{current?.name ?? 'Select a workflow'}</strong>{current && <small>{current.id}</small>}</div><div className="context-actions"><button className="btn btn-outline" onClick={() => go('workflows')}><Icon name="layers" size={16}/>Change workflow</button><button className="btn btn-outline" onClick={exportReport} disabled={!detail || busy}><Icon name="download" size={16}/>Export JSON</button></div></div>
          <section className="card audit-card"><div className="audit-card-heading"><CardTitle eyebrow="TRACEABLE OPERATIONS" title="Event and operator history"/><div className="audit-tabs" role="group" aria-label="History type"><button className={activityTab === 'events' ? 'tab-current' : ''} onClick={() => setActivityTab('events')}>Events <span>{detail?.events.length ?? 0}</span></button><button className={activityTab === 'approvals' ? 'tab-current' : ''} onClick={() => setActivityTab('approvals')}>Approvals <span>{detail?.approvals.length ?? 0}</span></button><button className={activityTab === 'faults' ? 'tab-current' : ''} onClick={() => setActivityTab('faults')}>Fault tests <span>{detail?.faultAudits.length ?? 0}</span></button></div></div>
            {activityTab === 'events' && <><p className="audit-intro">Events are displayed newest first. A valid hash chain verifies ordering and detects changes in individual records; it is not an externally anchored signature.</p><Timeline events={detail?.events ?? []}/>{!!detail?.events.length && <div className="audit-hash"><span>Latest event SHA-256</span><code>{detail.events.at(-1)?.hash}</code><CopyButton value={detail.events.at(-1)?.hash ?? ''}/></div>}</>}
            {activityTab === 'approvals' && (detail?.approvals.length ? <div className="audit-records">{[...detail.approvals].reverse().map(a => <div className="audit-record" key={a.id}><span className="audit-record-mark"><Icon name="shield" size={20}/></span><div><div className="audit-record-head"><strong>{recoveryLabel(a.action as Preview['action'])}</strong><time>{formatDate(a.approvedAt)}</time></div><p>Approved by <b>{a.actor}</b> after verifying history through event #{a.approvedSequence}.</p><small>Before: {a.beforeStatus.toLowerCase()}, step {a.beforeStep + 1} · After: {a.afterStatus.toLowerCase()}, step {a.afterStep + 1}</small></div></div>)}</div> : <Empty icon="shield" title="No recovery approvals" description="Each approved repair will appear here with its operator and before-and-after state."/>)}
            {activityTab === 'faults' && (detail?.faultAudits.length ? <div className="audit-records">{[...detail.faultAudits].reverse().map((a: FaultAudit) => <div className="audit-record" key={a.id}><span className="audit-record-mark audit-fault"><Icon name="bolt" size={20}/></span><div><div className="audit-record-head"><strong>{a.type === 'FAIL_NEXT_STEP' ? 'One-time transition failure' : 'Stored state corruption'}</strong><time>{formatDate(a.createdAt)}</time></div><p>Triggered by <b>{a.actor}</b> at step {a.beforeStep + 1} while the workflow was {a.beforeStatus.toLowerCase()}.</p><small>Controlled test · #{a.id}</small></div></div>)}</div> : <Empty icon="bolt" title="No fault tests recorded" description="Deliberate failures and state corruption will be recorded separately from the trusted workflow history."/>)}
          </section>
        </>}

        {page === 'guide' && <div className="guide-grid"><section className="card guide-primary"><span className="overline">A FIVE-MINUTE RECOVERY WALKTHROUGH</span><h2>One failed dispatch.<br/>One clear explanation.</h2><p className="guide-intro">Imagine a warehouse application fails between validation and packing. The operator needs to know whether the package was actually advanced, not just whether the server restarted.</p><div className="guide-steps">{[
          ['01', 'Create a dispatch', 'Every new workflow begins with a durable CREATED event.'],
          ['02', 'Complete validation', 'Advance writes an event and updates the current state in one database transaction.'],
          ['03', 'Simulate the failure', 'The next transition stops before committing PACKED. No outside shipping services are called.'],
          ['04', 'Review the evidence', 'Replay verifies event order and checkpoint data, then reconstructs the last committed state.'],
          ['05', 'Approve recovery', 'The operator reviews a read-only preview before resuming the workflow. The approval is recorded.'],
          ['06', 'Finish and export', 'Continue through packing and shipping, then export the audit trail for review.']
        ].map(item => <div className="guide-step" key={item[0]}><span>{item[0]}</span><div><h3>{item[1]}</h3><p>{item[2]}</p></div></div>)}</div>
          <button className="btn btn-primary guide-cta" disabled={busy || !faultsEnabled} onClick={runDrill}><Icon name="play" size={17}/>{faultsEnabled ? 'Create and run the failed dispatch' : 'Drills are disabled in this environment'}<Icon name="arrow" size={17}/></button><p className="guide-help">The drill creates a new workflow and stops before recovery approval. It never affects an existing dispatch.</p>
        </section><aside className="guide-aside"><section className="card"><CardTitle eyebrow="WHAT TO LOOK FOR" title="The proof points"/><div className="guide-fact"><Icon name="database" size={18}/><div><strong>Every step is durable</strong><p>PostgreSQL commits the event and projection together.</p></div></div><div className="guide-fact"><Icon name="refresh" size={18}/><div><strong>Replay is deterministic</strong><p>The same verified events reconstruct the same state.</p></div></div><div className="guide-fact"><Icon name="shield" size={18}/><div><strong>Recovery needs approval</strong><p>Drift repair and failure resumption are separate decisions.</p></div></div></section><section className="card guide-caution"><Icon name="info" size={21}/><h3>What this does not do</h3><p>RESURRECTION reconstructs its own simulated fulfillment workflow. It does not reverse payments, undo shipping-provider actions, or automatically fix arbitrary services. That boundary is intentional.</p></section></aside></div>}
        <footer className="page-footer"><span>RESURRECTION / Workflow recovery</span><span>{lastSync ? `Last scan ${formatDate(lastSync)}` : 'Local operator workspace'} · Single-operator demo</span></footer>
      </main>
    </div>
    {dialog === 'create' && <Modal title="Create a dispatch" subtitle="Start a workflow with a durable first event." close={() => setDialog(null)}><form className="dialog-form" onSubmit={create}><label htmlFor="dispatch-name">Workflow name</label><input id="dispatch-name" data-initial-focus maxLength={100} required placeholder="Example: Warehouse dispatch A-104" value={newName} onChange={e => setNewName(e.target.value)}/><p>Choose something you'd recognize later in the event history.</p><div className="dialog-actions"><button type="button" className="btn btn-outline" onClick={() => setDialog(null)}>Cancel</button><button className="btn btn-primary" disabled={busy || !newName.trim()} type="submit"><Icon name="plus" size={16}/>Create workflow</button></div></form></Modal>}
    {dialog === 'fault' && current && <Modal title={fault === 'FAIL_NEXT_STEP' ? 'Simulate a failed transition' : 'Simulate inconsistent stored state'} subtitle={`Test scenario for ${current.name}`} close={() => setDialog(null)}><div className="dialog-info danger-info"><Icon name="warning" size={20}/><p>{fault === 'FAIL_NEXT_STEP' ? 'The next advance will stop before committing the new step. A STEP_FAILED event will explain what happened.' : 'The stored workflow step will be altered without a corresponding workflow event. New advances will be blocked until an operator repairs the mismatch.'}</p></div><p className="dialog-note">This change is intentional and will appear in the fault-test audit. It does not call outside systems.</p><div className="dialog-actions"><button className="btn btn-outline" onClick={() => setDialog(null)}>Cancel</button><button className="btn btn-danger" onClick={inject} disabled={busy}><Icon name="bolt" size={16}/>Run the fault test</button></div></Modal>}
    {dialog === 'approve' && current && preview && <Modal title="Approve this recovery?" subtitle={`You're changing the saved state of ${current.name}.`} close={() => setDialog(null)}><div className="approval-dialog-details"><div><span>Verified through event</span><strong>#{preview.expectedSequence}</strong></div><div><span>Proposed action</span><strong>{recoveryLabel(preview.action)}</strong></div><div><span>State after recovery</span><strong>{preview.simulatedStep} / {statusLabel[preview.simulatedStatus]}</strong></div></div><p className="dialog-note">The approval is checked against the current event history again before it commits. A stale or changed history will be rejected.</p><div className="dialog-actions"><button className="btn btn-outline" onClick={() => setDialog(null)}>Review again</button><button className="btn btn-primary" disabled={busy || hasStalePreview} onClick={approve}><Icon name="shield" size={16}/>Approve and record</button></div></Modal>}
  </div>
}
