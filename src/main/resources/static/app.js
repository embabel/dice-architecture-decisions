'use strict'

const content = document.querySelector('#decision-content')
const switches = [...document.querySelectorAll('[data-revision]')]
const timeDisplay = document.querySelector('#revision-time')
const dateTime = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'UTC',
})
const states = {
  holding: 'Held at adoption',
  superseded: 'Superseded',
  unproven: 'Unproven',
}
const marker = document.querySelector('#link-mark')

let requestNumber = 0

function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text) element.textContent = text
  return element
}

function block(parent, tag, className, text) {
  const child = node(tag, className, text)
  parent.append(child)
  return child
}

function evidenceId(source) {
  return `evidence-${source.filename.replace(/[^a-z0-9]/gi, '-')}-${source.line}`
}

function cite(parent, source) {
  const link = block(parent, 'a', 'proof-source', source.title)
  link.href = `#${evidenceId(source)}`
  link.append(marker.content.cloneNode(true))
}

function sourceExcerpt(parent, label, source) {
  const excerpt = block(parent, 'figure', 'source-excerpt')
  excerpt.id = evidenceId(source)
  excerpt.tabIndex = -1
  const caption = block(excerpt, 'figcaption', 'source-title', label)
  block(caption, 'span', 'source-date', source.date)
  block(excerpt, 'blockquote', '', source.excerpt)
  block(excerpt, 'code', 'source-path', `${source.filename}:${source.line}`)
}

function renderAssumptions(view) {
  const listing = node('section', 'assumptions')
  listing.setAttribute('aria-label', 'Decision premises')
  const heading = block(listing, 'div', 'section-heading')
  block(heading, 'h2', '', 'Decision premises')
  block(heading, 'p', '', 'Follow each claim back to the evidence on file.')
  for (const premise of view.assumptions) {
    const row = block(listing, 'article', `assumption ${premise.status}`)
    const header = block(row, 'div', 'assumption-header')
    block(header, 'h3', '', premise.title)
    block(header, 'span', 'state', states[premise.status])
    block(row, 'p', 'assumption-check', premise.check)
    if (premise.original) {
      const trace = block(row, 'div', 'assumption-sources')
      sourceExcerpt(trace, 'Original evidence', premise.original)
      if (premise.update) sourceExcerpt(trace, 'Later evidence', premise.update)
    } else {
      block(row, 'p', 'missing-source', 'No completed replay drill in this evidence set.')
    }
  }
  return listing
}

function renderAnalysis(view) {
  const fixed = node('aside', 'analysis')
  fixed.setAttribute('aria-label', 'Derived review and approved decision')
  const statement = block(fixed, 'section', 'proof-panel')
  const posture = block(statement, 'header', 'proof-heading')
  block(posture, 'h2', '', view.posture.label)
  block(posture, 'span', 'proof-type', view.posture.rule ? 'Rule conclusion' : 'Evidence incomplete')
  const explanation = {
    'revisit_polling/1': 'The two original constraints changed. Reopen the ADR and evaluate the queue against today’s evidence.',
    'retain_polling/1': 'Both constraints were in force at adoption. The evidence supported keeping the outbox poller.',
  }
  block(statement, 'p', 'proof-description', explanation[view.posture.rule] ?? 'The available evidence does not establish a posture.')
  if (view.posture.rule) {
    const derivation = block(statement, 'details', 'derivation')
    block(derivation, 'summary', '', 'Inspect the rule trace')
    block(block(derivation, 'pre', ''), 'code', '', view.posture.logic)
    const used = block(derivation, 'div', 'proof-sources')
    view.posture.evidence.forEach(source => cite(used, source))
  }
  const guard = block(statement, 'section', 'migration-status')
  block(guard, 'h3', '', view.migration.label)
  block(guard, 'p', '', 'The migration rule requires a successful replay drill.')
  const missing = block(guard, 'details', 'derivation')
  block(missing, 'summary', '', 'Inspect migration criteria')
  block(block(missing, 'pre', ''), 'code', '', view.migration.logic)

  const approved = block(fixed, 'section', 'decision-record')
  block(approved, 'h2', '', 'Approved decision')
  block(approved, 'p', 'record-intro', 'The published ADR remains in force.')
  sourceExcerpt(approved, view.decision.title, view.decision)
  return fixed
}

function render(view) {
  timeDisplay.textContent = `${dateTime.format(new Date(view.revision.at))} UTC`
  const layout = node('div', 'workbench')
  layout.append(renderAssumptions(view), renderAnalysis(view))
  content.replaceChildren(layout)
}

async function load(revision) {
  const request = ++requestNumber
  for (const button of switches) {
    button.setAttribute('aria-pressed', String(button.dataset.revision === revision))
  }
  content.setAttribute('aria-busy', 'true')
  content.replaceChildren(node('p', 'progress', 'Loading the decision record…'))
  timeDisplay.textContent = ''
  try {
    const response = await fetch(`/api/decision?revision=${encodeURIComponent(revision)}`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const view = await response.json()
    if (request === requestNumber) render(view)
  } catch {
    if (request === requestNumber) {
      const panel = node('div', 'progress issue')
      block(panel, 'p', '', 'The decision record could not load. Check that the demo is running and try again.')
      block(panel, 'button', '', 'Retry').addEventListener('click', () => load(revision))
      panel.setAttribute('role', 'alert')
      content.replaceChildren(panel)
    }
  } finally {
    if (request === requestNumber) content.removeAttribute('aria-busy')
  }
}

for (const button of switches) {
  button.addEventListener('click', () => load(button.dataset.revision))
}
load('review')
