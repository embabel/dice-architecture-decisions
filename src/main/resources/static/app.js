'use strict'

const $ = (id) => document.getElementById(id)
const graph = $('graph')
const workbench = $('workbench')
const inspector = $('inspector')
const dateTime = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC',
})
const columns = [
  ['source', 'Source evidence'],
  ['condition', 'Conditions'],
  ['outcome', 'Rule outcomes'],
]
const svgNamespace = 'http://www.w3.org/2000/svg'
const statusClass = {
  Verified: 'positive', Derived: 'positive', Gap: 'warning', Blocked: 'warning',
  Assumed: 'assumed', 'Ready in scenario': 'assumed', Unproven: 'muted',
  'No recorded proof': 'muted', Approved: 'positive',
}
const switches = [...document.querySelectorAll('[data-revision]')]
let requestedRevision = 'review'
let view
let selection = 'migrate'
let loadNumber = 0
let chatNumber = 0
let asking = false
let chatFocus
let chatAbort
let transcript = []
let edges
let buttons = new Map()

function element(tag, className, text) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text != null) node.textContent = text
  return node
}

function add(parent, tag, className, text) {
  const child = element(tag, className, text)
  parent.append(child)
  return child
}

function button(text, className, callback) {
  const result = element('button', className, text)
  result.type = 'button'
  result.addEventListener('click', callback)
  return result
}

function describeNode(id) {
  return view.graph.nodes.find((node) => node.id === id)
}

function status(parent, label) {
  return add(parent, 'span', 'node-status ' + (statusClass[label] ?? 'neutral'), label)
}

function renderGraph() {
  buttons = new Map()
  edges = document.createElementNS(svgNamespace, 'svg')
  edges.classList.add('connections')
  edges.setAttribute('aria-hidden', 'true')
  graph.replaceChildren(edges)
  for (const [kind, label] of columns) {
    const column = add(graph, 'div', 'graph-column')
    add(column, 'h3', '', label)
    const items = add(column, 'div', 'graph-items')
    for (const node of view.graph.nodes.filter((entry) => entry.kind === kind)) {
      const card = button(null, 'graph-node ' + kind, () => select(node.id))
      add(card, 'span', 'node-title', node.title)
      status(card, node.status)
      buttons.set(node.id, card)
      items.append(card)
    }
  }
  select(selection, false, false)
}

function renderEdges() {
  if (!edges) return
  const bounds = graph.getBoundingClientRect()
  edges.setAttribute('viewBox', `0 0 ${bounds.width} ${bounds.height}`)
  const paths = view.graph.edges.map(({ from, to, status: state }) => {
    const start = buttons.get(from).getBoundingClientRect()
    const end = buttons.get(to).getBoundingClientRect()
    const x1 = start.right - bounds.left
    const y1 = start.top + start.height / 2 - bounds.top
    const x2 = end.left - bounds.left
    const y2 = end.top + end.height / 2 - bounds.top
    const middle = (x1 + x2) / 2
    const path = document.createElementNS(svgNamespace, 'path')
    path.setAttribute('d', `M ${x1} ${y1} C ${middle} ${y1}, ${middle} ${y2}, ${x2} ${y2}`)
    path.setAttribute('class', 'connection ' + state + (from === selection || to === selection ? ' related' : ''))
    return path
  })
  edges.replaceChildren(...paths)
}

function select(id, focus = false, forChat = true) {
  const node = describeNode(id)
  if (!node) return
  selection = id
  if (forChat) chatFocus = id
  const neighbors = new Set(view.graph.edges.filter((edge) => edge.from === id || edge.to === id)
    .flatMap((edge) => [edge.from, edge.to]))
  for (const [key, card] of buttons) {
    card.setAttribute('aria-pressed', String(key === id))
    card.classList.toggle('related', neighbors.has(key))
  }
  $('approved').setAttribute('aria-pressed', String(id === 'adr'))
  renderInspector(node)
  requestAnimationFrame(renderEdges)
  if (focus) inspector.focus()
}

function renderInspector(node) {
  const heading = element('div', 'inspector-heading')
  const summary = add(heading, 'div')
  add(summary, 'h2', '', node.title)
  status(summary, node.status)
  heading.append(button('Ask about this ↗', 'ask-node', () => {
    chatFocus = node.id
    $('question').value = 'What does this show?'
    $('question').focus()
  }))
  inspector.replaceChildren(heading, element('p', 'node-description', node.description))

  const inputs = view.graph.edges.filter((edge) => edge.to === node.id)
  if (inputs.length) {
    const list = add(inspector, 'ul', 'input-list')
    for (const input of inputs) {
      const item = add(list, 'li')
      const link = button(describeNode(input.from).title, 'node-link', () => select(input.from))
      link.title = input.label
      item.append(link)
      add(item, 'span', 'input-state ' + input.status,
        input.status === 'blocked' ? 'Unmet' : input.status === 'hypothetical' ? 'Assumed' : 'Proven')
    }
  }
  if (node.logic) {
    const trace = add(inspector, 'details', 'rule-trace')
    add(trace, 'summary', '', 'View Prolog rule')
    add(add(trace, 'pre'), 'code', '', node.logic)
  }
  for (const source of node.sources) {
    const figure = add(inspector, 'figure', 'source-excerpt')
    const caption = add(figure, 'figcaption', '', source.title)
    if (source.date) add(caption, 'time', '', source.date).dateTime = source.date
    add(figure, 'blockquote', '', source.excerpt)
    add(figure, 'code', '', source.line ? `${source.filename}:${source.line}` : source.filename)
  }
}

function resetChat() {
  chatNumber++
  chatAbort?.abort()
  asking = false
  transcript = []
  chatFocus = undefined
  $('messages').replaceChildren()
  $('question').value = ''
  $('chat-progress').textContent = ''
  $('chat-error').hidden = true
  $('suggestions').hidden = false
  $('send-question').disabled = false
}

function render(next) {
  view = next
  $('record-status').hidden = true
  workbench.hidden = false
  $('approved').disabled = false
  $('scenario').disabled = false
  $('revision-time').textContent = dateTime.format(new Date(view.revision.at)) + ' UTC'
  $('graph-status').textContent = view.graph.hypothetical ? 'What-if · ' + view.migration.label : view.posture.label
  $('graph-status').classList.toggle('assumed', view.graph.hypothetical)
  $('chat-scope').textContent = view.revision.label + (view.graph.hypothetical ? ' · What-if' : '')
  renderGraph()
}

async function load(revision, replayDrill) {
  const request = ++loadNumber
  requestedRevision = revision
  resetChat()
  for (const control of switches) {
    control.setAttribute('aria-pressed', String(control.dataset.revision === revision))
  }
  $('approved').disabled = true
  $('scenario').disabled = true
  workbench.hidden = true
  const notice = $('record-status')
  notice.hidden = false
  notice.textContent = 'Loading the decision record…'
  try {
    const response = await fetch('/api/decision?' + new URLSearchParams({ revision, replayDrill }))
    if (!response.ok) throw new Error('Could not load the decision record.')
    const next = await response.json()
    if (request === loadNumber) render(next)
  } catch (error) {
    if (request === loadNumber) {
      $('scenario').disabled = false
      notice.textContent = error.message + ' '
      notice.append(button('Retry', 'retry', () => load(revision, replayDrill)))
    }
  }
}

function appendMessage(role, text, ids = []) {
  const item = add($('messages'), 'li', 'message ' + role)
  add(item, 'span', 'speaker', role === 'user' ? 'You' : 'Decision assistant')
  add(item, 'p', '', text)
  if (ids.length) {
    const citations = add(item, 'div', 'citations')
    for (const id of ids) {
      const node = describeNode(id)
      if (node) citations.append(button(node.title + ' ↗', 'citation', () => select(id, true)))
    }
  }
  $('messages').scrollTop = $('messages').scrollHeight
  return item
}

async function ask(value) {
  const text = value.trim()
  if (!text || asking || !view) return
  const request = ++chatNumber
  chatAbort = new AbortController()
  asking = true
  $('suggestions').hidden = true
  $('send-question').disabled = true
  $('chat-progress').textContent = 'Working through the record…'
  $('chat-error').hidden = true
  const selectedForChat = chatFocus
  const userMessage = appendMessage('user', text)
  try {
    const response = await fetch('/api/decision/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: chatAbort.signal,
      body: JSON.stringify({
        revision: view.revision.key,
        replayDrill: view.graph.hypothetical,
        focus: selectedForChat,
        turns: [...transcript.slice(-16), { role: 'user', content: text }],
      }),
    })
    if (!response.ok) throw new Error('The assistant could not answer. Try sending the question again.')
    const reply = await response.json()
    if (request !== chatNumber) return
    appendMessage('assistant', reply.text, reply.nodeIds)
    if (chatFocus === selectedForChat) chatFocus = undefined
    transcript.push({ role: 'user', content: text }, { role: 'assistant', content: reply.text.slice(0, 1000) })
    if ($('question').value.trim() === text) $('question').value = ''
  } catch (error) {
    if (request !== chatNumber) return
    userMessage.remove()
    if (!$('question').value) $('question').value = text
    $('chat-error').textContent = error.message
    $('chat-error').hidden = false
    $('suggestions').hidden = transcript.length > 0
  } finally {
    if (request === chatNumber) {
      asking = false
      $('send-question').disabled = false
      $('chat-progress').textContent = ''
    }
  }
}

if (typeof ResizeObserver !== 'undefined') new ResizeObserver(renderEdges).observe(graph)
for (const control of switches) {
  control.addEventListener('click', () => load(control.dataset.revision, $('scenario').checked))
}
$('scenario').addEventListener('change', () => load(requestedRevision, $('scenario').checked))
$('approved').addEventListener('click', () => select('adr', true))
$('chat-form').addEventListener('submit', (event) => {
  event.preventDefault()
  ask($('question').value)
})
for (const suggestion of $('suggestions').querySelectorAll('button')) {
  suggestion.addEventListener('click', () => ask(suggestion.textContent))
}
fetch('/api/decision/chat').then((response) => response.ok ? response.json() : null).then((config) => {
  if (config) $('chat-mode').textContent = config.mode === 'live' ? 'Live agent' : 'Guided chat'
}).catch(() => {})
load('review', false)
