const jobs = {};
let statusFilter = '';
let sortKey = 'submittedAt';
let sortDir = -1; // newest first
let eventSource = null;

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
    <td title="${job.submittedAt || ''}">${relTime(job.submittedAt)}</td>
    <td title="${job.completedAt || ''}">${job.completedAt ? relTime(job.completedAt) : '—'}</td>
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

async function showDetail(id) {
  const res = await guarded(fetch('/api/wristbands/jobs/' + id));
  if (!res) return;
  if (!res.ok) { toast('Could not load job', 'err'); return; }
  const d = await res.json();

  const rows = [
    ['Job ID', d.jobId], ['Status', d.status], ['Event', d.eventName],
    ['First name', d.firstName], ['Last name', d.lastName],
    ['Association', d.associationName], ['Barcode', d.barcodeValue],
    ['Submitted', d.submittedAt], ['Completed', d.completedAt || '—'],
    ['Error', d.error || '—']
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
      <div class="drawer-preview preview-section">
        <button class="btn btn-sm" onclick="showPreview('${d.jobId}')">Show preview</button>
        <div id="preview-box"></div>
      </div>
      <div class="drawer-details">
        <h2>Job detail</h2>
        ${rows}
        <div class="drawer-actions">${actions.join('')}</div>
        <button class="btn drawer-close" onclick="closeDrawer()">Close</button>
      </div>
    </div>`;

  document.getElementById('drawer').classList.add('open');
  document.getElementById('drawer').setAttribute('aria-hidden', 'false');
  document.getElementById('drawer-overlay').classList.add('open');
}

function closeDrawer() {
  document.getElementById('drawer').classList.remove('open');
  document.getElementById('drawer').setAttribute('aria-hidden', 'true');
  document.getElementById('drawer-overlay').classList.remove('open');
}

function showPreview(id) {
  const box = document.getElementById('preview-box');
  box.innerHTML = '<div class="muted" style="padding:12px 0">Rendering…</div>';
  const img = new Image();
  img.className = 'wristband-preview';
  img.alt = 'Wristband preview';
  img.onload = () => { box.innerHTML = ''; box.appendChild(img); };
  img.onerror = () => {
    box.innerHTML = '<div class="error-text" style="padding:12px 0">Preview unavailable</div>';
  };
  img.src = '/api/wristbands/jobs/' + id + '/preview';
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
  if (diff < 60) return Math.floor(diff) + 's ago';
  if (diff < 3600) return Math.floor(diff / 60) + 'm ago';
  if (diff < 86400) return Math.floor(diff / 3600) + 'h ago';
  return new Date(iso).toLocaleString();
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
