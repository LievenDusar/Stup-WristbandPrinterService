const jobs = {};
let statusFilter = '';
let typeFilter = '';
let eventFilter = '';
let printerFilter = '';
let printersById = {};   // id -> { id, displayName, online, hidden, isDefault, lastSeenAt }
let sortKey = 'submittedAt';
let sortDir = -1; // newest first
let eventSource = null;
let previewUrl = null;        // in-memory object URL of the current job's rendered preview
let previewVisible = false;
const DRAWER_BASE_WIDTH = 500; // keep in sync with .drawer width in app.css

const STATUSES = ['PENDING', 'PRINTING', 'DONE', 'FAILED', 'CANCELLED'];
const TYPES = ['CREW', 'PERMIT'];
const TYPE_LABELS = { CREW: 'Crew', PERMIT: 'Permit' };

// Data-driven table columns. `Actions` is always rendered last and is NOT in this list.
const COLUMNS = [
  { key: 'name',      label: 'Name',      sort: 'firstName',
    cell: j => { const n = ((j.firstName || '') + ' ' + (j.lastName || '')).trim() || j.permitLabel;
                 return n ? esc(n) : '<span class="muted">—</span>'; } },
  { key: 'type',      label: 'Type',      sort: 'wristbandType',
    cell: j => typeBadge(j.wristbandType) },
  { key: 'event',     label: 'Event',     sort: 'eventName',
    cell: j => esc(j.eventName) },
  { key: 'printer',   label: 'Printer',   sort: 'printerName',
    cell: j => printerLabel(j) },
  { key: 'copies',    label: 'Copies',    sort: 'copies',
    cell: j => (j.copies > 1 ? `<strong>${j.copies}</strong>` : `<span class="muted">${j.copies ?? 1}</span>`) },
  { key: 'status',    label: 'Status',    sort: 'status',
    cell: j => `<span class="badge ${j.status}">${j.status}</span>` },
  { key: 'submitted', label: 'Submitted', sort: 'submittedAt',
    cell: j => `<span title="${fmtDateTime(j.submittedAt)}">${relTime(j.submittedAt)}</span>` },
];

const MAX_COLS = 5;
const MIN_COLS = 1;
const DEFAULT_COLS = ['name', 'type', 'event', 'copies', 'status'];
const ALL_COL_KEYS = COLUMNS.map(c => c.key);
let visibleCols = loadVisibleCols();

function loadVisibleCols() {
  try {
    const raw = JSON.parse(localStorage.getItem('jobs.visibleColumns'));
    if (Array.isArray(raw)) {
      const valid = raw.filter(k => ALL_COL_KEYS.includes(k));
      if (valid.length >= MIN_COLS && valid.length <= MAX_COLS) return valid;
    }
  } catch (e) { /* fall through to default */ }
  return DEFAULT_COLS.slice();
}

function saveVisibleCols() {
  localStorage.setItem('jobs.visibleColumns', JSON.stringify(visibleCols));
}

// COLUMNS in declared order, filtered to the visible set (keeps a stable column order).
function visibleColumnDefs() {
  return COLUMNS.filter(c => visibleCols.includes(c.key));
}

function renderHeader() {
  const cols = visibleColumnDefs();
  document.getElementById('jobs-head').innerHTML =
    '<tr>' + cols.map(c => `<th onclick="sortBy('${c.sort}')">${c.label}</th>`).join('')
    + '<th aria-label="Actions"></th></tr>';
}

function toggleColumnsMenu(e) {
  e.stopPropagation();
  closeRowMenu();
  closeNavMenu();
  const m = document.getElementById('columns-menu');
  if (m.hidden) { renderColumnsMenu(); m.hidden = false; }
  else { m.hidden = true; }
}

function closeColumnsMenu() {
  const m = document.getElementById('columns-menu');
  if (m && !m.hidden) m.hidden = true;
}

function renderColumnsMenu() {
  const atMax = visibleCols.length >= MAX_COLS;
  const atMin = visibleCols.length <= MIN_COLS;
  document.getElementById('columns-menu').innerHTML = COLUMNS.map(c => {
    const on = visibleCols.includes(c.key);
    const disabled = (!on && atMax) || (on && atMin);
    return `<label class="menu-item col-toggle">
      <input type="checkbox" ${on ? 'checked' : ''} ${disabled ? 'disabled' : ''}
             onchange="toggleColumn('${c.key}')">${c.label}</label>`;
  }).join('');
}

function toggleColumn(key) {
  const on = visibleCols.includes(key);
  if (on) {
    if (visibleCols.length <= MIN_COLS) return;        // keep at least one data column
    visibleCols = visibleCols.filter(k => k !== key);
  } else {
    if (visibleCols.length >= MAX_COLS) return;        // cap at five data columns
    visibleCols.push(key);
  }
  saveVisibleCols();
  renderColumnsMenu();
  render();                                            // render() also calls renderHeader()
}

window.addEventListener('load', init);

async function init() {
  // Auth gate + initial load. A 401 means no valid admin cookie → go to login.
  try {
    const res = await fetch('/api/wristbands/jobs');
    if (res.status === 401) { redirectToLogin(); return; }
    (await res.json()).forEach(j => { jobs[j.jobId] = j; });
  } catch (e) { /* SSE will retry */ }
  try {
    const pr = await fetch('/api/wristbands/printers');
    if (pr.ok) {
      printersById = {};
      (await pr.json()).forEach(p => { printersById[p.id] = p; });
    }
  } catch (e) { /* the printer filter just won't render */ }
  render();
  connectSse();
}

function redirectToLogin() { window.location.href = '/login.html'; }

function connectSse() {
  if (eventSource) eventSource.close();
  eventSource = new EventSource('/api/wristbands/jobs/stream');
  eventSource.onopen = () => setSse('● Live', 'live');
  eventSource.onmessage = (e) => { const job = JSON.parse(e.data); jobs[job.jobId] = job; render(); };
  eventSource.addEventListener('printer', (e) => {
    const p = JSON.parse(e.data);
    printersById[p.id] = p;
    render();                       // repaints table cells + rebuilds the filter
    if (isManageOpen()) renderManageModal();
  });
  eventSource.onerror = async () => {
    setSse('○ Reconnecting…', 'reconnecting');
    // Distinguish a dropped connection from a lost session.
    try { if ((await fetch('/api/wristbands/jobs')).status === 401) redirectToLogin(); } catch (e) {}
  };
}

function setSse(text, cls) {
  const el = document.getElementById('sse-status');
  el.textContent = text;
  el.className = cls;
}

function sortBy(key) {
  if (sortKey === key) { sortDir *= -1; } else { sortKey = key; sortDir = 1; }
  render();
}

// ── Filters ─────────────────────────────────────────────────────────────────

function setStatusFilter(v)  { statusFilter  = v; render(); }
function setTypeFilter(v)    { typeFilter    = v; render(); }
function setEventFilter(v)   { eventFilter   = v; render(); }
function setPrinterFilter(v) { printerFilter = v; render(); }

// Reset every active filter (dropdowns + search) back to "show all".
function resetFilters() {
  statusFilter = ''; typeFilter = ''; eventFilter = ''; printerFilter = '';
  document.getElementById('search').value = '';
  render();
}

// Empty the search box (the clear button hides itself again via CSS once the field is empty).
function clearSearch() {
  const el = document.getElementById('search');
  el.value = '';
  el.focus();
  render();
}

function render() {
  renderHeader();
  renderFilters();
  const search = document.getElementById('search').value.trim().toLowerCase();
  const tbody = document.getElementById('jobs-body');

  // Surface the "Clear filters" button only when something is actually filtering.
  document.getElementById('filter-reset').hidden =
    !(statusFilter || typeFilter || eventFilter || printerFilter || search);

  let list = Object.values(jobs)
    .filter(j => !statusFilter  || j.status === statusFilter)
    .filter(j => !typeFilter    || j.wristbandType === typeFilter)
    .filter(j => !eventFilter   || (j.eventName || '') === eventFilter)
    .filter(j => !printerFilter || j.printerId === printerFilter)
    .filter(j => !search
      || j.jobId.toLowerCase().includes(search)
      || (j.eventName || '').toLowerCase().includes(search)
      || (j.permitLabel || '').toLowerCase().includes(search)
      || ((j.firstName || '') + ' ' + (j.lastName || '')).toLowerCase().includes(search));

  list.sort((a, b) => {
    const av = a[sortKey] || '', bv = b[sortKey] || '';
    return av < bv ? -sortDir : av > bv ? sortDir : 0;
  });

  if (list.length === 0) {
    tbody.innerHTML = `<tr><td colspan="${visibleCols.length + 1}" class="empty">No jobs.</td></tr>`;
    return;
  }

  tbody.innerHTML = list.map(rowHtml).join('');
}

// Visible data cells (in column order) + the always-present ⋮ actions cell.
// The whole row opens the detail slide-in; the actions cell stops propagation.
function rowHtml(job) {
  const cells = visibleColumnDefs().map(c => `<td>${c.cell(job)}</td>`).join('');
  return `<tr onclick="showDetail('${job.jobId}')">${cells}
    <td class="actions-cell" onclick="event.stopPropagation()">
      <button class="kebab" title="Actions" aria-label="Row actions" onclick="openRowMenu(event, '${job.jobId}')">⋮</button>
    </td>
  </tr>`;
}

// Printer name resolved live from printersById (so a rename repaints all rows);
// falls back to the value captured on the job. A known-offline printer gets a muted dot.
function printerLabel(j) {
  const p = j.printerId ? printersById[j.printerId] : null;
  const name = (p && p.displayName) || j.printerName;
  if (!name) return '<span class="muted">—</span>';
  const dot = p && !p.online ? '<span class="printer-dot off" title="offline"></span>' : '';
  return dot + esc(name);
}

// Small coloured pill marking a job's wristband type (CREW / PERMIT).
function typeBadge(type) {
  if (!type) return '<span class="muted">—</span>';
  return `<span class="badge ${type}">${esc(TYPE_LABELS[type] || type)}</span>`;
}

// ── Filter dropdowns ──────────────────────────────────────────────────────────

function renderFilters() {
  const all = Object.values(jobs);

  const sc = {}; STATUSES.forEach(s => sc[s] = 0);
  all.forEach(j => { sc[j.status] = (sc[j.status] || 0) + 1; });
  syncSelect('filter-status', [
    { value: '', label: `All statuses (${all.length})` },
    ...STATUSES.map(s => ({ value: s, label: `${statusLabel(s)} (${sc[s] || 0})` }))
  ], statusFilter);

  const tc = {}; TYPES.forEach(t => tc[t] = 0);
  all.forEach(j => { if (j.wristbandType) tc[j.wristbandType] = (tc[j.wristbandType] || 0) + 1; });
  syncSelect('filter-type', [
    { value: '', label: `All types (${all.length})` },
    ...TYPES.map(t => ({ value: t, label: `${TYPE_LABELS[t]} (${tc[t] || 0})` }))
  ], typeFilter);

  const ec = {};
  all.forEach(j => { const e = j.eventName || ''; if (e) ec[e] = (ec[e] || 0) + 1; });
  const events = Object.keys(ec).sort((a, b) => a.localeCompare(b));
  syncSelect('filter-event', [
    { value: '', label: 'All events' },
    ...events.map(e => ({ value: e, label: `${e} (${ec[e]})` }))
  ], eventFilter);

  const printerSel = document.getElementById('filter-printer');
  const visiblePrinters = Object.values(printersById).filter(p => !p.hidden);
  if (visiblePrinters.length > 1) {                    // no point routing-filtering with one printer
    printerSel.hidden = false;
    const pc = {};
    all.forEach(j => { if (j.printerId) pc[j.printerId] = (pc[j.printerId] || 0) + 1; });
    syncSelect('filter-printer', [
      { value: '', label: 'All printers' },
      ...visiblePrinters.map(p => ({ value: p.id, label: `${p.displayName} (${pc[p.id] || 0})` }))
    ], printerFilter);
  } else {
    printerSel.hidden = true;
  }
}

function statusLabel(s) { return s.charAt(0) + s.slice(1).toLowerCase(); }

// Rebuild a <select>'s options only when they actually changed, and never while the
// user has it open (focused) — otherwise a live SSE update would collapse the dropdown.
function syncSelect(id, options, value) {
  const el = document.getElementById(id);
  const sig = JSON.stringify(options);
  if (el.dataset.sig !== sig && el !== document.activeElement) {
    el.innerHTML = '';
    options.forEach(o => {
      const opt = document.createElement('option');
      opt.value = o.value;
      opt.textContent = o.label;
      el.appendChild(opt);
    });
    el.dataset.sig = sig;
  }
  el.value = value;
}

// ── Row action menu (⋮ popover) ───────────────────────────────────────────────

function openRowMenu(e, jobId) {
  e.stopPropagation();              // don't open the drawer, don't let the doc-listener close us
  closeNavMenu();
  closeColumnsMenu();
  const job = jobs[jobId];
  const menu = document.getElementById('row-menu');
  if (!job) { menu.hidden = true; return; }

  const items = [
    `<button class="menu-item" onclick="showDetail('${jobId}')">Details</button>`,
    `<button class="menu-item" onclick="copyId('${jobId}')">Copy job ID</button>`
  ];
  if (job.status === 'DONE' || job.status === 'FAILED') {
    items.push(`<button class="menu-item" onclick="reprint('${jobId}')">Reprint</button>`);
  }
  if (job.status === 'PENDING') {
    items.push(`<button class="menu-item danger" onclick="cancelJob('${jobId}')">Cancel</button>`);
  }
  menu.innerHTML = items.join('');

  // Anchor below the button, right-aligned; flip above if it would overflow the viewport.
  menu.hidden = false;
  menu.style.visibility = 'hidden';
  const r = e.currentTarget.getBoundingClientRect();
  const mh = menu.offsetHeight, mw = menu.offsetWidth;
  let top = r.bottom + 4;
  if (top + mh > window.innerHeight - 8) top = Math.max(8, r.top - mh - 4);
  let left = r.right - mw;
  if (left < 8) left = 8;
  menu.style.top = top + 'px';
  menu.style.left = left + 'px';
  menu.style.visibility = 'visible';
}

function closeRowMenu() {
  const m = document.getElementById('row-menu');
  if (m && !m.hidden) m.hidden = true;
}

// ── Top navigation menu ───────────────────────────────────────────────────────

function toggleNavMenu(e) {
  e.stopPropagation();
  closeRowMenu();
  closeColumnsMenu();
  const m = document.getElementById('nav-menu');
  m.hidden = !m.hidden;
}

function closeNavMenu() {
  const m = document.getElementById('nav-menu');
  if (m && !m.hidden) m.hidden = true;
}

async function copyId(id) {
  try { await navigator.clipboard.writeText(id); toast('Job ID copied', 'ok'); }
  catch (e) { toast('Copy failed', 'err'); }
}

// ── Manage-printers modal ──────────────────────────────────────────────────────
function isManageOpen() {
  const o = document.getElementById('manage-overlay');
  return o && o.classList.contains('open');
}

function openManageModal() {
  closeNavMenu();
  renderManageModal();
  document.getElementById('manage-overlay').classList.add('open');
}

function closeManageModal() {
  document.getElementById('manage-overlay').classList.remove('open');
}

function renderManageModal() {
  const rows = Object.values(printersById)
    .sort((a, b) => a.id.localeCompare(b.id))
    .map(p => {
      const status = p.online
        ? '<span class="badge DONE">online</span>'
        : '<span class="badge PENDING">offline</span>';
      const hidden = p.hidden ? ' <span class="muted">(hidden)</span>' : '';
      const star = p.isDefault
        ? '<span class="default-star on" title="default printer">★</span>'
        : `<button class="btn btn-sm" onclick="setDefaultPrinter('${p.id}')"
             ${p.hidden ? 'disabled title="hidden printers cannot be default"' : ''}>Set default</button>`;
      const hideBtn = p.online
        ? '<button class="btn btn-sm" disabled title="can only hide an offline printer">Hide</button>'
        : (p.hidden ? '' : `<button class="btn btn-sm" onclick="hidePrinter('${p.id}')">Hide</button>`);
      return `<tr>
        <td><input class="input manage-name" id="pname-${p.id}" value="${esc(p.displayName)}">
            <button class="btn btn-sm" onclick="renamePrinter('${p.id}')">Save</button></td>
        <td>${status}${hidden}<div class="muted manage-seen">${p.lastSeenAt ? relTime(p.lastSeenAt) : ''}</div></td>
        <td>${star}</td>
        <td class="manage-actions">
          <button class="btn btn-sm" onclick="testPrinter('${p.id}')">Test</button>
          ${hideBtn}
        </td></tr>`;
    }).join('');
  document.getElementById('manage-rows').innerHTML = rows
    || '<tr><td class="muted">No printers registered. Start a worker container.</td></tr>';
}

// Render key/value detail rows, skipping any with an empty value.
function detailRows(pairs) {
  return pairs
    .filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `<div class="detail-row"><span class="k">${k}</span><span class="v">${esc(String(v))}</span></div>`)
    .join('');
}

// Wrap rows in a titled section; renders nothing when the section has no rows.
function detailSection(heading, rowsHtml) {
  return rowsHtml
    ? `<div class="detail-section"><div class="detail-section-title">${heading}</div>${rowsHtml}</div>`
    : '';
}

async function showDetail(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id));
  if (!res) return;
  if (!res.ok) { toast('Could not load job', 'err'); return; }
  const d = await res.json();

  // The identity (name / permit label + event) is promoted into the header; the
  // remaining fields are grouped into titled sections, skipping any that don't apply.
  const name = ((d.firstName || '') + ' ' + (d.lastName || '')).trim();
  const title = name || d.permitLabel || '—';

  const wristbandRows = detailRows([
    ['Club', d.clubName],
    ['Barcode', d.barcodeValue]
  ]);
  const printingRows = detailRows([
    ['Printer', d.printerName],
    ['Copies', d.copies],
    ['Submitted', fmtDateTime(d.submittedAt)],
    ['Completed', d.completedAt ? fmtDateTime(d.completedAt) : '—']
  ]);

  const actions = [];
  if (d.status === 'PENDING') {
    actions.push(`<button class="btn btn-sm" onclick="cancelJob('${d.jobId}'); closeDrawer()">Cancel</button>`);
  }
  if (d.status === 'DONE' || d.status === 'FAILED') {
    actions.push(`<button class="btn btn-sm btn-primary" onclick="reprint('${d.jobId}')">Reprint</button>`);
  }

  document.getElementById('drawer-content').innerHTML = `
    <button class="drawer-close-x" onclick="closeDrawer()" aria-label="Close">×</button>
    <div class="drawer-body">
      <div class="drawer-preview"><div id="preview-box"></div></div>
      <div class="drawer-details">
        <div class="drawer-head">
          <div class="drawer-eyebrow">
            ${typeBadge(d.wristbandType)}
            <span class="badge ${d.status}">${statusLabel(d.status)}</span>
          </div>
          <h2 class="drawer-title">${esc(title)}</h2>
          ${d.eventName ? `<div class="drawer-subtitle">${esc(d.eventName)}</div>` : ''}
        </div>

        <div class="drawer-sections">
          ${detailSection('Wristband', wristbandRows)}
          ${detailSection('Printing', printingRows)}
        </div>

        <div class="drawer-footer">
          <div class="detail-id">
            <span class="detail-id-label">Job ID</span>
            <span class="mono detail-id-value" title="${d.jobId}">${d.jobId}</span>
            <button class="copy-btn" title="Copy job ID" onclick="copyId('${d.jobId}')">⧉</button>
          </div>
          ${d.status === 'FAILED' && d.error ? `<div class="drawer-error">${esc(d.error)}</div>` : ''}
          <div class="drawer-actions">
            <button class="btn btn-sm" id="preview-btn" onclick="togglePreview('${d.jobId}')">Show preview</button>
            ${actions.join('')}
          </div>
        </div>
      </div>
    </div>`;

  clearPreview();                 // drop any cached preview from a previously opened job
  const drawer = document.getElementById('drawer');
  drawer.style.width = '';        // reset to the default width
  drawer.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
  document.getElementById('drawer-overlay').classList.add('open');
}

function closeDrawer() {
  clearPreview();                 // free the cached preview when the flyin closes
  const drawer = document.getElementById('drawer');
  drawer.classList.remove('open');
  drawer.style.width = '';
  drawer.setAttribute('aria-hidden', 'true');
  document.getElementById('drawer-overlay').classList.remove('open');
}

// Toggle the rendered preview. First show fetches it once and caches the image in
// memory (object URL); hiding keeps the cache so re-showing needs no backend call.
async function togglePreview(id) {
  const btn = document.getElementById('preview-btn');
  const box = document.getElementById('preview-box');

  if (previewVisible) {                 // hide — keep the cached image, shrink the drawer
    box.innerHTML = '';
    document.getElementById('drawer').style.width = '';
    btn.textContent = 'Show preview';
    previewVisible = false;
    return;
  }

  btn.textContent = 'Hide preview';
  previewVisible = true;

  if (previewUrl) { renderPreviewImage(previewUrl); return; }  // cached — no backend call

  box.innerHTML = '<div class="spinner" role="status" aria-label="Rendering preview"></div>';
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/preview'));
  if (!res) { previewVisible = false; return; }               // 401 → redirected to login
  if (!res.ok) {
    box.innerHTML = '<div class="error-text" style="padding:12px 0">Preview unavailable</div>';
    return;
  }
  previewUrl = URL.createObjectURL(await res.blob());
  if (previewVisible) renderPreviewImage(previewUrl);         // user may have hidden meanwhile
}

function renderPreviewImage(url) {
  const box = document.getElementById('preview-box');
  const img = new Image();
  img.className = 'wristband-preview';
  img.alt = 'Wristband preview';
  img.onload = () => {
    box.innerHTML = '';
    box.appendChild(img);
    // Grow the drawer by exactly the rendered wristband's width (animated via CSS).
    const w = Math.ceil(img.getBoundingClientRect().width);
    document.getElementById('drawer').style.width = 'min(96vw, ' + (DRAWER_BASE_WIDTH + w) + 'px)';
  };
  img.src = url;
}

function clearPreview() {
  if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
  previewVisible = false;
}

// Reprint a finished job. Asks for a copy count (defaulting to the original job's copies)
// and, when more than one printer exists, a target printer.
async function reprint(id) {
  const job = jobs[id];
  const sel = await reprintDialog(job && job.copies ? job.copies : 1);
  if (!sel) return;                                  // cancelled
  const params = new URLSearchParams();
  if (sel.printerId) params.set('printerId', sel.printerId);
  if (sel.copies && sel.copies !== 1) params.set('copies', String(sel.copies));
  const qs = params.toString();
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/reprint' + (qs ? '?' + qs : ''),
                                  { method: 'POST' }));
  if (!res) return;
  toast(res.ok ? 'Reprint queued' : 'Reprint failed', res.ok ? 'ok' : 'err');
}

// Reprint dialog: a copies number-input plus an optional printer <select>, reusing the
// confirm overlay. Resolves to { copies, printerId } or null (cancel).
function reprintDialog(defaultCopies) {
  return new Promise(resolve => {
    const overlay = document.getElementById('confirm-overlay');
    const card = overlay.querySelector('.confirm-card');
    const prevHtml = card.innerHTML;
    const reprintPrinters = Object.values(printersById).filter(p => !p.hidden);
    const printerField = (reprintPrinters.length > 1)
      ? `<label class="reprint-field">Printer
           <select class="select" id="reprint-printer">
             ${reprintPrinters.map(p => `<option value="${esc(p.id)}">${esc(p.displayName)}</option>`).join('')}
           </select></label>`
      : '';
    card.innerHTML = `
      <div style="font-weight:600;margin-bottom:4px">Reprint</div>
      <label class="reprint-field">Copies
        <input class="input" id="reprint-copies" type="number" min="1" value="${defaultCopies}"></label>
      ${printerField}
      <div class="confirm-actions">
        <button class="btn" id="reprint-cancel">Cancel</button>
        <button class="btn btn-primary" id="reprint-go">Reprint</button>
      </div>`;
    overlay.classList.add('open');
    const done = (val) => { overlay.classList.remove('open'); card.innerHTML = prevHtml; resolve(val); };
    card.querySelector('#reprint-cancel').onclick = () => done(null);
    card.querySelector('#reprint-go').onclick = () => {
      const copies = Math.max(1, parseInt(card.querySelector('#reprint-copies').value, 10) || 1);
      const sel = card.querySelector('#reprint-printer');
      done({ copies, printerId: sel ? sel.value : null });
    };
  });
}

async function cancelJob(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/cancel', { method: 'POST' }));
  if (!res) return;
  if (res.ok) toast('Job cancelled', 'ok');
  else if (res.status === 409) toast('Job already started', 'err');
  else toast('Cancel failed', 'err');
}

function confirmDialog(message, okLabel = 'Confirm') {
  return new Promise(resolve => {
    const overlay = document.getElementById('confirm-overlay');
    const okBtn = document.getElementById('confirm-ok');
    const cancelBtn = document.getElementById('confirm-cancel');
    document.getElementById('confirm-message').textContent = message;
    okBtn.textContent = okLabel;
    overlay.classList.add('open');
    const done = (result) => {
      overlay.classList.remove('open');
      okBtn.onclick = null; cancelBtn.onclick = null; overlay.onclick = null;
      document.removeEventListener('keydown', onKey);
      resolve(result);
    };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    okBtn.onclick = () => done(true);
    cancelBtn.onclick = () => done(false);
    overlay.onclick = (e) => { if (e.target === overlay) done(false); };
    document.addEventListener('keydown', onKey);
  });
}

async function clearCompleted() {
  const ok = await confirmDialog(
    'Hide all completed, failed and cancelled jobs from the queue? This is a soft delete — '
    + 'they stay in the database and can only be restored by an admin.', 'Clear');
  if (!ok) return;
  const res = await guarded(fetch('/api/wristbands/jobs/completed', { method: 'DELETE' }));
  if (!res) return;
  if (res.ok) {
    Object.keys(jobs).forEach(id => {
      const s = jobs[id].status;
      if (s === 'DONE' || s === 'FAILED' || s === 'CANCELLED') delete jobs[id];
    });
    render();
    toast('Completed jobs cleared', 'ok');
  } else { toast('Clear failed', 'err'); }
}

async function logout() {
  try { await fetch('/api/wristbands/logout', { method: 'POST' }); } catch (e) {}
  redirectToLogin();
}

// Wrap a fetch promise; on 401 redirect to login and return null.
async function guarded(promise) {
  try {
    const res = await promise;
    if (res.status === 401) { redirectToLogin(); return null; }
    return res;
  } catch (e) { toast('Network error', 'err'); return null; }
}

function relTime(iso) {
  if (!iso) return '—';
  const diff = (Date.now() - new Date(iso).getTime()) / 1000;
  if (diff < 0) return fmtDateTime(iso);
  if (diff < 60) return Math.floor(diff) + 's ago';
  if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
  if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
  return fmtDateTime(iso);
}

// Render an ISO-8601 instant as a readable local date+time (falls back to the raw value).
function fmtDateTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
}

function esc(str) {
  return (str ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function toast(msg, kind) {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind === 'ok' ? 'ok' : 'err');
  el.textContent = msg;
  document.getElementById('toasts').appendChild(el);
  setTimeout(() => el.remove(), 3000);
}

// Any click that isn't captured by a menu trigger (those call stopPropagation) closes the menus.
document.addEventListener('click', () => { closeRowMenu(); closeNavMenu(); closeColumnsMenu(); });
// A fixed-positioned popover would detach from its anchor on scroll/resize — just close it.
window.addEventListener('scroll', closeRowMenu, true);
window.addEventListener('resize', () => { closeRowMenu(); closeNavMenu(); closeColumnsMenu(); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeDrawer(); closeRowMenu(); closeNavMenu(); closeColumnsMenu(); }
});
