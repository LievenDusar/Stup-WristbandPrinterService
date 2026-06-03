// Thin wrappers over the /api/templates* endpoints. The admin cookie rides along
// automatically; a 401 means the session expired → bounce to login.
function guard(res) {
  if (res.status === 401) { window.location.href = '/login.html'; throw new Error('unauthorized'); }
  return res;
}

export async function listTemplates() {
  const res = guard(await fetch('/api/templates'));
  return res.ok ? res.json() : [];
}

export async function getTemplate(id) {
  const res = guard(await fetch('/api/templates/' + id));
  if (!res.ok) throw new Error('load failed');
  return res.json();
}

export async function createTemplate(body) {
  const res = guard(await fetch('/api/templates', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }));
  if (!res.ok) throw new Error('create failed (' + res.status + ')');
  return res.json();
}

export async function updateTemplate(id, body) {
  const res = guard(await fetch('/api/templates/' + id, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }));
  if (!res.ok) throw new Error('update failed (' + res.status + ')');
  return res.json();
}

// Live preview using the canvas's sample data; returns an object URL for an <img>.
export async function previewPng(id, color) {
  const url = '/api/templates/' + id + '/preview' + (color ? '?color=' + encodeURIComponent(color) : '');
  const res = guard(await fetch(url));
  if (!res.ok) throw new Error('preview failed');
  return URL.createObjectURL(await res.blob());
}

export async function previewPngWithData(id, color, data) {
  const url = '/api/templates/' + id + '/preview' + (color ? '?color=' + encodeURIComponent(color) : '');
  const res = guard(await fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  }));
  if (!res.ok) throw new Error('preview failed');
  return URL.createObjectURL(await res.blob());
}

export async function uploadAsset(file) {
  const fd = new FormData();
  fd.append('file', file);
  const res = guard(await fetch('/api/templates/assets', { method: 'POST', body: fd }));
  if (!res.ok) throw new Error('upload failed');
  return res.json(); // { id, name, width, height }
}
