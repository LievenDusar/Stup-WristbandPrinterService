import { listTemplates, getTemplate, createTemplate, updateTemplate, previewPngWithData } from './api.js';
import { serializeElements, loadElements, getCanvasDots, resize, setBackgroundColor } from './canvas.js';
import { toUpsertRequest } from './state.js';

let currentId = null;

const $ = (id) => document.getElementById(id);

function meta() {
  return { name: $('tpl-name').value.trim(), projectType: $('tpl-project').value.trim(), color: $('tpl-color').value };
}
function canvasFromInputs() {
  return { widthDots: +$('tpl-width').value, lengthDots: +$('tpl-length').value, dpi: +$('tpl-dpi').value };
}

const CSS_COLORS = { white: '#ffffff', red: '#e53935', blue: '#1E88E5', green: '#43A047', yellow: '#FDD835', orange: '#FB8C00', pink: '#EC407A' };

export async function initToolbar() {
  await refreshTemplateList();

  $('tpl-color').addEventListener('change', () => setBackgroundColor(CSS_COLORS[$('tpl-color').value]));
  ['tpl-width', 'tpl-length', 'tpl-dpi'].forEach(id =>
    $(id).addEventListener('change', () => resize(canvasFromInputs())));

  $('btn-new').addEventListener('click', () => { currentId = null; $('tpl-name').value = ''; loadElements([]); });

  $('btn-save').addEventListener('click', async () => {
    if (!meta().name) { alert('Please enter a template name.'); return; }
    const body = toUpsertRequest(meta(), canvasFromInputs(), serializeElements());
    try {
      const saved = currentId ? await updateTemplate(currentId, body) : await createTemplate(body);
      currentId = saved.id;
      await refreshTemplateList();
      $('tpl-open').value = currentId;
      alert('Saved: ' + saved.slug);
    } catch (e) { alert('Save failed: ' + e.message); }
  });

  $('btn-preview').addEventListener('click', async () => {
    if (!currentId) { alert('Save the template first, then preview.'); return; }
    try {
      const data = sampleDataFromElements(serializeElements());
      const url = await previewPngWithData(currentId, $('tpl-color').value, data);
      const img = $('preview-img');
      img.src = url; img.style.display = 'block';
      window.open(url, '_blank');
    } catch (e) { alert('Preview failed: ' + e.message); }
  });

  $('btn-export').addEventListener('click', async () => {
    if (!currentId) { alert('Save the template first, then export.'); return; }
    const tpl = await getTemplate(currentId);
    const blob = new Blob([tpl.generatedZpl || ''], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = (tpl.slug || 'template') + '.zpl'; a.click();
  });

  $('tpl-open').addEventListener('change', async () => {
    const id = $('tpl-open').value;
    if (!id) return;
    const tpl = await getTemplate(id);
    currentId = tpl.id;
    $('tpl-name').value = tpl.name;
    $('tpl-project').value = tpl.projectType || '';
    $('tpl-color').value = tpl.defaultPreviewColor || 'white';
    $('tpl-width').value = tpl.definition.canvas.widthDots;
    $('tpl-length').value = tpl.definition.canvas.lengthDots;
    $('tpl-dpi').value = tpl.definition.canvas.dpi;
    resize(tpl.definition.canvas);
    setBackgroundColor(CSS_COLORS[$('tpl-color').value]);
    loadElements(tpl.definition.elements);
  });
}

async function refreshTemplateList() {
  const list = await listTemplates();
  const sel = $('tpl-open');
  sel.innerHTML = '<option value="">Open template…</option>'
    + list.map(t => `<option value="${t.id}">${t.name}${t.projectType ? ' (' + t.projectType + ')' : ''}</option>`).join('');
}

// Assemble a WristbandData body from each block's sampleText (falling back to sensible defaults).
function sampleDataFromElements(elements) {
  const data = { eventName: 'Pukkelpop 2026', firstName: 'Annechien', lastName: 'Van De Wall',
    clubName: 'Chiro Sint-Christina Brustem', barcodeValue: '12345654245524789' };
  const visit = (els) => els.forEach(el => {
    if (el.type === 'GROUP') { visit(el.children || []); return; }
    const s = el.sampleText;
    if (!s) return;
    switch (el.binding) {
      case 'EVENT_NAME': data.eventName = s; break;
      case 'FIRST_NAME': data.firstName = s; break;
      case 'LAST_NAME': data.lastName = s; break;
      case 'CLUB_NAME': data.clubName = s; break;
      case 'BARCODE_VALUE': data.barcodeValue = s; break;
      case 'FULL_NAME': { const [f, ...r] = s.split(' '); data.firstName = f; data.lastName = r.join(' '); break; }
      default: break;
    }
  });
  visit(elements);
  return data;
}
