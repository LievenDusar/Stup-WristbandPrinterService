const jobs = {};
let statusFilter = '';
let sortKey = 'submittedAt';
let sortDir = -1; // newest first
let eventSource = null;
let previewUrl = null;        // in-memory object URL of the current job's rendered preview
let previewVisible = false;
const DRAWER_BASE_WIDTH = 500; // keep in sync with .drawer width in app.css

const STATUSES = ['PENDING', 'PRINTING', 'DONE', 'FAILED', 'CANCELLED'];

window.addEventListener('load', init);

async function init() {
  // Auth gate + initial load. A 401 means no valid admin cookie → go to login.
  try {
    const res = await fetch('/api/wristbands/jobs');
    if (res.status === 401) { redirectToLogin(); return; }
    (await res.json()).forEach(j => { jobs[j.jobId] = j; });
  } catch (e) { /* SSE will retry */ }
  render();
  connectSse();
}

function redirectToLogin() { window.location.href = '/login.html'; }

function connectSse() {
  if (eventSource) eventSource.close();
  eventSource = new EventSource('/api/wristbands/jobs/stream');
  eventSource.onopen = () => setSse('● Live', 'live');
  eventSource.onmessage = (e) => { const job = JSON.parse(e.data); jobs[job.jobId] = job; render(); };
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

function setFilter(status) { statusFilter = (statusFilter === status) ? '' : status; render(); }

function render() {
  renderChips();
  const search = document.getElementById('search').value.trim().toLowerCase();
  const tbody = document.getElementById('jobs-body');

  let list = Object.values(jobs)
    .filter(j => !statusFilter || j.status === statusFilter)
    .filter(j => !search
      || j.jobId.toLowerCase().includes(search)
      || (j.eventName || '').toLowerCase().includes(search)
      || ((j.firstName || '') + ' ' + (j.lastName || '')).toLowerCase().includes(search));

  list.sort((a, b) => {
    const av = a[sortKey] || '', bv = b[sortKey] || '';
    return av < bv ? -sortDir : av > bv ? sortDir : 0;
  });

  if (list.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty">No jobs.</td></tr>';
    return;
  }

  tbody.innerHTML = list.map(rowHtml).join('');
}

function rowHtml(job) {
  const actions = [`<button class="btn btn-sm" onclick="showDetail('${job.jobId}')">Details</button>`];
  if (job.status === 'PENDING') {
    actions.push(`<button class="btn btn-sm" onclick="cancelJob('${job.jobId}')">Cancel</button>`);
  }
  if (job.status === 'DONE' || job.status === 'FAILED') {
    actions.push(`<button class="btn btn-sm btn-primary" onclick="reprint('${job.jobId}')">Reprint</button>`);
  }
  return `<tr>
    <td><div class="id-cell">
      <span class="mono" title="${job.jobId}">${job.jobId.substring(0, 8)}…</span>
      <button class="copy-btn" title="Copy full ID" onclick="copyId('${job.jobId}')">⧉</button>
    </div></td>
    <td>${esc(((job.firstName || '') + ' ' + (job.lastName || '')).trim())}</td>
    <td>${esc(job.eventName)}</td>
    <td><span class="badge ${job.status}">${job.status}</span></td>
    <td title="${fmtDateTime(job.submittedAt)}">${relTime(job.submittedAt)}</td>
    <td title="${job.completedAt ? fmtDateTime(job.completedAt) : ''}">${job.completedAt ? relTime(job.completedAt) : '—'}</td>
    <td><div style="display:flex;gap:6px">${actions.join('')}</div></td>
  </tr>`;
}

function renderChips() {
  const counts = {};
  STATUSES.forEach(s => counts[s] = 0);
  Object.values(jobs).forEach(j => { counts[j.status] = (counts[j.status] || 0) + 1; });
  const chips = [`<span class="chip ${statusFilter === '' ? 'active' : ''}" onclick="setFilter('')">All <span class="count">${Object.values(jobs).length}</span></span>`];
  STATUSES.forEach(s => {
    chips.push(`<span class="chip ${statusFilter === s ? 'active' : ''}" onclick="setFilter('${s}')">${s} <span class="count">${counts[s]}</span></span>`);
  });
  document.getElementById('chips').innerHTML = chips.join('');
}

async function copyId(id) {
  try { await navigator.clipboard.writeText(id); toast('Job ID copied', 'ok'); }
  catch (e) { toast('Copy failed', 'err'); }
}

// Build the alert-style status box shown at the top of the drawer.
function statusBox(d) {
  const cls = (d.status || '').toLowerCase();
  const titles = { PENDING: 'Pending', PRINTING: 'Printing', DONE: 'Done', FAILED: 'Failed', CANCELLED: 'Cancelled' };
  const msgs = {
    PENDING: 'Waiting in the queue to be printed.',
    PRINTING: 'Sending the wristband to the printer…',
    DONE: 'The wristband was printed successfully.',
    FAILED: d.error ? d.error : 'Printing failed.',
    CANCELLED: 'This job was cancelled before printing.'
  };
  const title = titles[d.status] || (d.status || 'Unknown');
  const msg = msgs[d.status] || '';
  return `<div class="status-box ${cls}">
    <div class="status-box-title">${esc(title)}</div>
    ${msg ? `<div class="status-box-msg">${esc(String(msg))}</div>` : ''}
  </div>`;
}

async function showDetail(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id));
  if (!res) return;
  if (!res.ok) { toast('Could not load job', 'err'); return; }
  const d = await res.json();

  const rows = [
    ['Job ID', d.jobId], ['Event', d.eventName],
    ['First name', d.firstName], ['Last name', d.lastName],
    ['Association', d.associationName], ['Barcode', d.barcodeValue],
    ['Submitted', fmtDateTime(d.submittedAt)], ['Completed', fmtDateTime(d.completedAt)]
  ].map(([k, v]) => `<div class="detail-row"><span class="k">${k}</span><span class="v">${esc(String(v))}</span></div>`).join('');

  const actions = [];
  if (d.status === 'PENDING') {
    actions.push(`<button class="btn btn-sm" onclick="cancelJob('${d.jobId}'); closeDrawer()">Cancel</button>`);
  }
  if (d.status === 'DONE' || d.status === 'FAILED') {
    actions.push(`<button class="btn btn-sm btn-primary" onclick="reprint('${d.jobId}')">Reprint</button>`);
  }

  document.getElementById('drawer-content').innerHTML = `
    <div class="drawer-body">
      <div class="drawer-preview"><div id="preview-box"></div></div>
      <div class="drawer-details">
        
        <h2>Job detail</h2>
        ${statusBox(d)}
        ${rows}
        <div class="preview-trigger">
          <button class="btn btn-sm" id="preview-btn" onclick="togglePreview('${d.jobId}')">Show preview</button>
        </div>
        <div class="drawer-actions">${actions.join('')}</div>
        <button class="btn drawer-close" onclick="closeDrawer()">Close</button>
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

async function reprint(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/reprint', { method: 'POST' }));
  if (!res) return;
  toast(res.ok ? 'Reprint queued' : 'Reprint failed', res.ok ? 'ok' : 'err');
}

async function cancelJob(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id + '/cancel', { method: 'POST' }));
  if (!res) return;
  if (res.ok) toast('Job cancelled', 'ok');
  else if (res.status === 409) toast('Job already started', 'err');
  else toast('Cancel failed', 'err');
}

async function clearCompleted() {
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

document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });
