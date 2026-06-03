import { applyProp, layer, getScale } from './canvas.js';

const BINDINGS = ['EVENT_NAME', 'FIRST_NAME', 'LAST_NAME', 'FULL_NAME', 'ASSOCIATION_NAME', 'BARCODE_VALUE'];

// Geometry is read from the live Konva node (px → dots); only non-geometry fields are attrs.
export function showProperties(node) {
  const empty = document.getElementById('props-empty');
  const form = document.getElementById('props-form');
  const del = document.getElementById('btn-delete');

  if (!node) { empty.style.display = ''; form.style.display = 'none'; del.style.display = 'none'; return; }
  empty.style.display = 'none'; form.style.display = ''; del.style.display = '';

  const p2d = (p) => Math.round(p / getScale());
  const type = node.getAttr('elType');
  const grouped = node.getParent() && node.getParent() !== layer;
  const rot = Math.round(node.rotation() / 90) * 90 % 360;
  const rows = [];

  if (type === 'GROUP') {
    rows.push(selectRow('stackDirection', node.getAttr('stackDirection') || 'LENGTH', ['LENGTH', 'WIDTH']));
    rows.push(numberRow('marginDots', node.getAttr('marginDots') || 0));
    rows.push(selectRow('crossAlign', node.getAttr('crossAlign') || 'START', ['START', 'CENTER', 'END']));
  } else {
    if (!grouped) {
      const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
      rows.push(numberRow('x', Math.round(r.x / getScale())));
      rows.push(numberRow('y', Math.round(r.y / getScale())));
    }
    rows.push(selectRow('rotation', rot, ['0', '90', '180', '270']));
    if (type === 'TEXT') {
      rows.push(numberRow('fontSize', p2d(node.fontSize())));
      rows.push(selectRow('binding', node.getAttr('binding'), BINDINGS));
      rows.push(textRow('sampleText', node.getAttr('sampleText')));
    } else if (type === 'STATIC_TEXT') {
      rows.push(numberRow('fontSize', p2d(node.fontSize())));
      rows.push(textRow('value', node.getAttr('value')));
    } else if (type === 'BARCODE') {
      rows.push(numberRow('widthDots', p2d(node.width())));
      rows.push(numberRow('heightDots', p2d(node.height())));
      rows.push(selectRow('symbology', node.getAttr('symbology'), ['CODE128', 'CODE39', 'QR']));
      rows.push(checkboxRow('showHumanReadable', node.getAttr('showHumanReadable')));
    } else if (type === 'IMAGE') {
      rows.push(numberRow('widthDots', p2d(node.width())));
      rows.push(numberRow('heightDots', p2d(node.height())));
    } else if (type === 'SHAPE') {
      rows.push(numberRow('widthDots', p2d(node.width())));
      rows.push(numberRow('heightDots', p2d(node.height())));
      rows.push(numberRow('thicknessDots', node.getAttr('thicknessDots')));
    }
  }

  form.innerHTML = rows.join('');
  form.querySelectorAll('[data-prop]').forEach(input => {
    input.addEventListener('change', () => {
      const key = input.dataset.prop;
      let val = input.value;
      if (input.type === 'number' || key === 'rotation') val = parseInt(val, 10);
      if (input.type === 'checkbox') val = input.checked;
      applyProp(node, key, val);
    });
  });
}

function numberRow(key, val) {
  return `<div class="field"><label>${key}</label><input class="input" type="number" data-prop="${key}" value="${val ?? 0}"></div>`;
}
function textRow(key, val) {
  return `<div class="field"><label>${key}</label><input class="input" type="text" data-prop="${key}" value="${(val ?? '').replace(/"/g, '&quot;')}"></div>`;
}
function selectRow(key, val, options) {
  const opts = options.map(o => `<option value="${o}" ${String(o) === String(val) ? 'selected' : ''}>${o}</option>`).join('');
  return `<div class="field"><label>${key}</label><select class="input" data-prop="${key}">${opts}</select></div>`;
}
function checkboxRow(key, val) {
  return `<div class="field"><label>${key}</label><input type="checkbox" data-prop="${key}" ${val ? 'checked' : ''}></div>`;
}
