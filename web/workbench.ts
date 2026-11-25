import { HttpAgent, type Message } from '@ag-ui/client'
import { linkHorizontal, linkVertical, select as d3, zoom, zoomIdentity } from 'd3'

type Revision = 'adopted' | 'review'
type Entry = {
  id: string; lineage: string; version: number; title: string; rationale: string
  recordedAt: string; related: string | null; previous: string | null; restored: string | null
}
type Source = { title: string; filename: string; line: number; date: string | null; excerpt: string }
type DecisionNode = { id: string; kind: string; title: string; status: string; description: string; logic: string | null; sources: Source[] }
type Edge = { from: string; to: string; status: string; label: string }
type View = {
  revision: { key: Revision; label: string; at: string }
  graph: { nodes: DecisionNode[]; edges: Edge[]; hypothetical: boolean }
  posture: { label: string }; migration: { label: string }; history: Entry[]
}

function node<T extends HTMLElement = HTMLElement>(id: string): T {
  const found = document.getElementById(id)
  if (!found) throw new Error('Missing workbench element: ' + id)
  return found as T
}

const graph = node('graph')
const viewport = graph.parentElement!
const inspector = node('inspector')
const workbench = node('workbench')
const conversation = node('messages')
const question = node<HTMLInputElement>('question')
const scenario = node<HTMLInputElement>('scenario')
const approved = node<HTMLButtonElement>('approved')
const record = node<HTMLButtonElement>('record-decision')
const send = node<HTMLButtonElement>('send-question')
const notice = node('record-status')
const suggestions = node('suggestions')
const chatError = node('chat-error')
const progress = node('chat-progress')
const editor = node<HTMLDialogElement>('editor')
const decisionTitle = node<HTMLInputElement>('decision-title')
const rationale = node<HTMLTextAreaElement>('decision-rationale')
const relation = node<HTMLSelectElement>('decision-link')
const save = node<HTMLButtonElement>('save-decision')
const editorError = node('editor-error')

const dateTime = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
const lanes = [
  ['source', 'Source evidence'],
  ['condition', 'Conditions'],
  ['outcome', 'Rule outcomes'],
  ['decision', 'Decision log'],
]
const statusClass: Record<string, string> = {
  Verified: 'positive', Derived: 'positive', Gap: 'warning', Blocked: 'warning',
  Assumed: 'assumed', 'Ready in scenario': 'assumed', Unproven: 'muted',
  'No recorded proof': 'muted', Approved: 'positive', Current: 'positive', Superseded: 'muted',
}
const switches = [...document.querySelectorAll<HTMLButtonElement>('[data-revision]')]
const svgNamespace = 'http://www.w3.org/2000/svg'
let revision: Revision = 'review'
let view: View | undefined
let selection = 'migrate'
let loadNumber = 0
let chatNumber = 0
let threadId = crypto.randomUUID()
let asking = false
let chatFocus: string | undefined
let agent: HttpAgent | undefined
let transcript: Message[] = []
let edges: SVGSVGElement | undefined
let cards = new Map<string, HTMLButtonElement>()
let scale = 1
let editing: Entry | undefined
let restoring: Entry | undefined
let saving = false

const surface = d3<HTMLElement, unknown>(viewport)
const zoomBehavior = zoom<HTMLElement, unknown>()
  .scaleExtent([0.65, 2.25])
  .filter((event) => {
    if (event.type === 'wheel') return event.ctrlKey || event.metaKey
    if (event.type === 'touchstart') return event.touches.length >= 2
    return event.button === 0 && !(event.target instanceof Element && event.target.closest('button'))
  })
  .on('zoom', ({ transform }) => {
    scale = transform.k
    graph.style.transform = transform.toString()
    node('zoom-fit').textContent = Math.round(scale * 100) + '%'
  })
surface.call(zoomBehavior).on('dblclick.zoom', null)
node('zoom-in').addEventListener('click', () => surface.call(zoomBehavior.scaleBy, 1.25))
node('zoom-out').addEventListener('click', () => surface.call(zoomBehavior.scaleBy, 0.8))
node('zoom-fit').addEventListener('click', resetGraph)
const horizontal = linkHorizontal()
const vertical = linkVertical()

function resetGraph() {
  viewport.scrollLeft = 0
  surface.call(zoomBehavior.transform, zoomIdentity)
}

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string) {
  const result = document.createElement(tag)
  if (className) result.className = className
  if (text !== undefined) result.textContent = text
  return result
}

function add<K extends keyof HTMLElementTagNameMap>(parent: HTMLElement, tag: K, className?: string, text?: string) {
  const item = element(tag, className, text)
  parent.append(item)
  return item
}

function button(text: string | undefined, className: string, callback: () => void) {
  const result = element('button', className, text)
  result.type = 'button'
  result.addEventListener('click', callback)
  return result
}

function describe(id: string) {
  return view?.graph.nodes.find((entry) => entry.id === id)
}

function status(parent: HTMLElement, label: string) {
  return add(parent, 'span', 'node-status ' + (statusClass[label] ?? 'neutral'), label)
}

function renderGraph(current: View) {
  const canvas = document.createElementNS(svgNamespace, 'svg')
  canvas.classList.add('connections')
  canvas.setAttribute('aria-hidden', 'true')
  edges = canvas
  cards = new Map()
  graph.replaceChildren(canvas)

  for (const [kind, label] of lanes) {
    const column = add(graph, 'div', 'graph-column ' + kind)
    add(column, 'h3', undefined, label)
    const items = add(column, 'div', 'graph-items')
    for (const entry of current.graph.nodes.filter((candidate) => candidate.kind === kind)) {
      const card = button(undefined, 'graph-node ' + kind, () => select(entry.id))
      card.dataset.status = entry.status
      add(card, 'span', 'node-title', entry.title)
      status(card, entry.status)
      cards.set(entry.id, card)
      items.append(card)
    }
  }
  if (!describe(selection)) selection = 'migrate'
  select(selection, false, false)
}

function renderEdges() {
  if (!edges || !view) return
  const bounds = graph.getBoundingClientRect()
  if (!bounds.width || !bounds.height) return
  const links = view.graph.edges.filter(({ from, to }) => cards.has(from) && cards.has(to))
  d3(edges)
    .attr('viewBox', `0 0 ${bounds.width / scale} ${bounds.height / scale}`)
    .selectAll<SVGPathElement, Edge>('path')
    .data(links, ({ from, to, label }) => from + '/' + to + '/' + label)
    .join('path')
    .attr('d', ({ from, to }) => {
      const start = cards.get(from)!.getBoundingClientRect()
      const end = cards.get(to)!.getBoundingClientRect()
      const local = (x: number, y: number): [number, number] => [(x - bounds.left) / scale, (y - bounds.top) / scale]
      if (Math.abs(start.left - end.left) < 2) {
        return vertical({
          source: local(start.left + start.width * .5, start.bottom),
          target: local(end.left + end.width * .5, end.top),
        })
      }
      return horizontal({
        source: local(start.right, start.top + start.height / 2),
        target: local(end.left, end.top + end.height / 2),
      })
    })
    .attr('class', ({ from, to, status: state }) =>
      'connection ' + state + (from === selection || to === selection ? ' related' : ''))
}

function select(id: string, focus = false, forChat = true) {
  const selected = describe(id)
  if (!selected || !view) return
  selection = id
  if (forChat) chatFocus = id
  const neighbors = new Set(view.graph.edges.filter(({ from, to }) => from === id || to === id).flatMap(({ from, to }) => [from, to]))
  for (const [key, card] of cards) {
    card.setAttribute('aria-pressed', String(key === id))
    card.classList.toggle('related', neighbors.has(key))
  }
  approved.setAttribute('aria-pressed', String(id === 'adr'))
  renderInspector(selected, view)
  requestAnimationFrame(renderEdges)
  if (focus) inspector.focus()
}

function renderInspector(selected: DecisionNode, current: View) {
  const heading = element('div', 'inspector-heading')
  const label = add(heading, 'div')
  add(label, 'h2', undefined, selected.title)
  status(label, selected.status)
  heading.append(button('Ask about this ↗', 'ask-node', () => {
    chatFocus = selected.id
    question.value = 'What does this show?'
    question.focus()
  }))
  inspector.replaceChildren(heading, element('p', 'node-description', selected.description))
  const inputs = current.graph.edges.filter(({ to }) => to === selected.id)
  if (inputs.length) {
    const list = add(inspector, 'ul', 'input-list')
    for (const input of inputs) {
      const item = add(list, 'li')
      const target = describe(input.from)
      if (!target) continue
      const link = button(target.title, 'node-link', () => select(input.from))
      link.title = input.label
      item.append(link)
      const meaning = input.status === 'blocked' ? 'Unmet' : input.status === 'hypothetical' ? 'Assumed' : input.status === 'proved' ? 'Proven' : input.label
      add(item, 'span', 'input-state ' + input.status, meaning)
    }
  }
  if (selected.logic) {
    const trace = add(inspector, 'details', 'rule-trace')
    add(trace, 'summary', undefined, 'View Prolog rule')
    add(add(trace, 'pre'), 'code', undefined, selected.logic)
  }
  const entry = current.history.find(({ id }) => id === selected.id)
  if (entry) {
    renderHistory(current, entry)
    return
  }
  for (const source of selected.sources) {
    const figure = add(inspector, 'figure', 'source-excerpt')
    const caption = add(figure, 'figcaption', undefined, source.title)
    if (source.date) add(caption, 'time', undefined, source.date).dateTime = source.date
    add(figure, 'blockquote', undefined, source.excerpt)
    add(figure, 'code', undefined, source.line ? `${source.filename}:${source.line}` : source.filename)
  }
}

function renderHistory(current: View, entry: Entry) {
  const versions = current.history.filter(({ lineage }) => lineage === entry.lineage)
  const latest = versions.at(-1)!
  const row = add(inspector, 'div', 'version-history')
  add(row, 'strong', undefined, 'Version history')
  const timeline = add(row, 'div', 'version-list')
  for (const version of versions) {
    const tab = button(`v${version.version}`, 'version-tab', () => select(version.id))
    tab.title = dateTime.format(new Date(version.recordedAt)) + ' UTC'
    tab.setAttribute('aria-pressed', String(version.id === entry.id))
    timeline.append(tab)
  }
  add(row, 'p', 'revision-time', dateTime.format(new Date(entry.recordedAt)) + ' UTC')
  if (entry.restored) {
    const original = versions.find(({ id }) => id === entry.restored)
    if (original) add(row, 'p', 'revision-time', `Restored v${original.version}`)
  }
  if (current.graph.hypothetical) return
  const actions = add(row, 'div', 'version-actions')
  if (latest.id === entry.id) {
    actions.append(button('Revise decision', 'small-action', () => openEditor(entry)))
    const previous = versions.find(({ id }) => id === entry.previous)
    if (previous) actions.append(button(`Roll back to v${previous.version}`, 'small-action', () => openEditor(latest, previous)))
  } else {
    actions.append(button('Restore this version', 'small-action', () => openEditor(latest, entry)))
  }
}

function openEditor(head?: Entry, restore?: Entry) {
  if (!view || view.revision.key !== 'review' || view.graph.hypothetical || saving) return
  editing = head
  restoring = restore
  editorError.hidden = true
  node('editor-title').textContent = restore ? 'Restore decision version' : head ? 'Revise decision' : 'New decision'
  node('editor-fields').hidden = !!restore
  const explanation = node('restore-detail')
  explanation.hidden = !restore
  explanation.textContent = restore ? `Restore “${restore.title}” (v${restore.version}) as a new version. The current version stays in history.` : ''
  decisionTitle.required = !restore
  rationale.required = !restore
  decisionTitle.value = head?.title ?? ''
  rationale.value = head?.rationale ?? ''
  relation.replaceChildren(new Option('No link', ''))
  for (const item of view.graph.nodes) {
    relation.append(new Option(item.title + ' · ' + item.status, item.id))
  }
  relation.value = head ? head.related ?? '' : selection
  save.textContent = restore ? 'Restore version' : head ? 'Save revision' : 'Record decision'
  editor.showModal()
  if (restore) save.focus()
  else decisionTitle.focus()
}

async function problem(response: Response, fallback: string): Promise<string> {
  try {
    const result: { detail?: string } = await response.json()
    return result.detail || fallback
  } catch {
    return fallback
  }
}

async function saveVersion() {
  if (saving) return
  saving = true
  save.disabled = true
  editorError.hidden = true
  try {
    const response = await fetch('/api/decision/versions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        revision, replayDrill: scenario.checked, title: decisionTitle.value, rationale: rationale.value,
        related: relation.value || null, expectedHead: editing?.id, restoreFrom: restoring?.id,
      }),
    })
    if (!response.ok) {
      if (response.status === 409) void load('review', false)
      throw new Error(await problem(response, 'Could not save the decision. Try again.'))
    }
    const entry: Entry = await response.json()
    editor.close()
    selection = entry.id
    await load('review', false)
    inspector.focus()
  } catch (error) {
    editorError.textContent = errorText(error)
    editorError.hidden = false
  } finally {
    saving = false
    save.disabled = false
  }
}

function resetChat() {
  chatNumber++
  agent?.abortRun()
  agent = undefined
  threadId = crypto.randomUUID()
  asking = false
  transcript = []
  chatFocus = undefined
  conversation.replaceChildren()
  question.value = ''
  progress.textContent = ''
  chatError.hidden = true
  suggestions.hidden = false
  send.disabled = false
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : 'Something went wrong. Try again.'
}

async function load(next: Revision, replayDrill: boolean) {
  const request = ++loadNumber
  revision = next
  resetChat()
  resetGraph()
  switches.forEach((control) => control.setAttribute('aria-pressed', String(control.dataset.revision === next)))
  approved.disabled = true
  scenario.disabled = true
  record.disabled = true
  view = undefined
  workbench.hidden = true
  notice.hidden = false
  notice.textContent = 'Loading the decision record…'
  try {
    const response = await fetch('/api/decision?' + new URLSearchParams({ revision: next, replayDrill: String(replayDrill) }), { cache: 'no-store' })
    if (!response.ok) throw new Error(await problem(response, 'Could not load the decision record.'))
    const current: View = await response.json()
    if (request !== loadNumber) return
    view = current
    notice.hidden = true
    workbench.hidden = false
    approved.disabled = false
    scenario.disabled = false
    record.disabled = next !== 'review' || replayDrill
    record.title = record.disabled ? 'Switch to the latest review with what-if off to edit the graph' : ''
    node('revision-time').textContent = dateTime.format(new Date(current.revision.at)) + ' UTC'
    const graphStatus = node('graph-status')
    graphStatus.textContent = replayDrill ? 'What-if · ' + current.migration.label : current.posture.label
    graphStatus.classList.toggle('assumed', replayDrill)
    node('chat-scope').textContent = current.revision.label + (replayDrill ? ' · What-if' : '')
    renderGraph(current)
  } catch (error) {
    if (request !== loadNumber) return
    scenario.disabled = false
    notice.textContent = errorText(error) + ' '
    notice.append(button('Retry', 'retry', () => { void load(next, replayDrill) }))
  }
}

function scrollMessages() {
  conversation.scrollTop = conversation.scrollHeight
}

function message(role: 'user' | 'assistant', text: string) {
  const item = add(conversation, 'li', 'message ' + role)
  add(item, 'span', 'speaker', role === 'user' ? 'You' : 'Decision assistant')
  const body = add(item, 'p', undefined, text)
  scrollMessages()
  return { item, body }
}

function citations(item: HTMLElement, ids: unknown) {
  if (!Array.isArray(ids)) return
  const targets = ids.flatMap((id: unknown) => typeof id === 'string' ? describe(id) ?? [] : [])
  if (!targets.length) return
  const area = add(item, 'div', 'citations')
  for (const target of targets) {
    area.append(button(target.title + ' ↗', 'citation', () => select(target.id, true)))
  }
  scrollMessages()
}

async function ask(value: string) {
  const text = value.trim()
  if (!text || asking || !view) return
  const request = ++chatNumber
  const focus = chatFocus
  const questionMessage: Message = { id: crypto.randomUUID(), role: 'user', content: text }
  const run = new HttpAgent({
    url: '/api/decision/agent',
    threadId,
    initialState: { revision: view.revision.key, replayDrill: view.graph.hypothetical },
    initialMessages: [...transcript.slice(-16), questionMessage],
  })
  agent = run
  asking = true
  suggestions.hidden = true
  send.disabled = true
  progress.textContent = 'Working through the record…'
  chatError.hidden = true
  const user = message('user', text)
  let assistant: ReturnType<typeof message> | undefined
  let answerId = ''
  let failure = ''
  try {
    await run.runAgent({ forwardedProps: { focus } }, {
      onTextMessageContentEvent: ({ event }) => {
        if (request !== chatNumber) return
        answerId = event.messageId
        assistant ??= message('assistant', '')
        assistant.body.append(document.createTextNode(event.delta))
        scrollMessages()
      },
      onCustomEvent: ({ event }) => {
        if (request === chatNumber && assistant && event.name === 'decision.citations' && event.value?.messageId === answerId) {
          citations(assistant.item, event.value.nodeIds)
        }
      },
      onRunErrorEvent: ({ event }) => { failure = event.message },
    })
    if (request !== chatNumber) return
    const answer = assistant?.body.textContent?.trim()
    if (failure || !answer) throw new Error(failure || 'The assistant returned no answer. Try again.')
    if (chatFocus === focus) chatFocus = undefined
    transcript.push(questionMessage, { id: answerId, role: 'assistant', content: answer.slice(0, 1000) })
    if (question.value.trim() === text) question.value = ''
  } catch {
    if (request !== chatNumber) return
    user.item.remove()
    assistant?.item.remove()
    if (!question.value) question.value = text
    chatError.textContent = failure || 'The assistant could not answer. Try sending the question again.'
    chatError.hidden = false
    suggestions.hidden = transcript.length > 0
  } finally {
    if (request === chatNumber) {
      asking = false
      send.disabled = false
      progress.textContent = ''
    }
  }
}

new ResizeObserver(renderEdges).observe(graph)
for (const control of switches) {
  control.addEventListener('click', () => { void load(control.dataset.revision === 'adopted' ? 'adopted' : 'review', scenario.checked) })
}
scenario.addEventListener('change', () => { void load(revision, scenario.checked) })
approved.addEventListener('click', () => select('adr', true))
node('chat-form').addEventListener('submit', (event) => {
  event.preventDefault()
  void ask(question.value)
})
for (const suggestion of suggestions.querySelectorAll('button')) {
  suggestion.addEventListener('click', () => { void ask(suggestion.textContent ?? '') })
}
record.addEventListener('click', () => openEditor())
for (const id of ['close-editor', 'cancel-editor']) {
  node(id).addEventListener('click', () => { if (!saving) editor.close() })
}
editor.addEventListener('cancel', (event) => { if (saving) event.preventDefault() })
node('version-form').addEventListener('submit', (event) => {
  event.preventDefault()
  void saveVersion()
})
fetch('/api/decision/chat').then((response) => response.ok ? response.json() : null).then((config) => {
  if (config) node('chat-mode').textContent = config.mode === 'live' ? 'Live agent' : 'Guided chat'
}).catch(() => {})
void load('review', false)
