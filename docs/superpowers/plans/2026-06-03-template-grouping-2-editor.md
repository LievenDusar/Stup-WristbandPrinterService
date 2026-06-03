# Template Grouping — Plan 2 (Editor): Grouping, Alignment, Margins, Placeholders

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add to the Konva editor: multi-select, group/ungroup (nestable), live group layout (stack direction + margin + cross-align), center-on-band, per-block sample text (data blocks) shown on canvas + driving the preview, and obvious free-text entry — round-tripping to the Plan-1 `GROUP` model.

**Architecture:** Editor nodes mirror the model: leaves are Konva shapes; a group is a `Konva.Group` carrying `{type:'GROUP', stackDirection, marginDots, crossAlign}` whose members are laid out by a recursive `applyLayout()` that mirrors the renderer's math in pixel space (`px = dots × scale`). Grouped members aren't dragged individually (layout owns their position); the group drags as a unit. Serialization recurses: Konva groups ⇄ `GROUP` elements with `children`.

**Tech Stack:** Vanilla ES modules + vendored Konva (as in the existing editor). No JS test runner — verified via the run-the-app checklist (Task 6).

**Prerequisite:** Plan 1 (backend groups) merged to `main`; the existing editor (`/template-editor.html`) present.

**Selection model (explicit):**
- Single click selects the **outermost** node (a top-level leaf, or the top-level group containing the clicked member) — groups move as a unit.
- **Double-click** a grouped member selects that inner leaf for property/size editing (its position stays layout-controlled).
- **Shift-click** adds/removes outermost nodes from the selection; **Group** wraps the selection, **Ungroup** dissolves one level.
- Transformer: leaves resize+rotate; groups move only (no resize/rotate — layout + margin control size; group rotation is out of scope).

---

## File Structure

**Modify:**
- `src/main/resources/static/js/editor/canvas.js` — recursive layout, group nodes, selection, serialize/deserialize (full replacement)
- `src/main/resources/static/js/editor/properties.js` — group form, sample-text, grouped-member form (full replacement)
- `src/main/resources/static/js/editor/toolbar.js` — preview uses sample-text data (targeted edits)
- `src/main/resources/static/js/editor/groupops.js` — **create**: group/ungroup/center actions
- `src/main/resources/static/js/editor/main.js` — wire group/ungroup/center buttons (targeted edits)
- `src/main/resources/static/template-editor.html` — add Group / Ungroup / Center buttons
- `docs/template-designer.md` — note the editor capabilities

---

## Task 1: Canvas — recursive layout, group nodes, selection, serialization

**Files:**
- Modify (full replacement): `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Replace `canvas.js`**

`src/main/resources/static/js/editor/canvas.js`:

```js
import { nextId } from './state.js';

const Konva = window.Konva;

const MAX_DISPLAY_HEIGHT = 720;
let stage, layer, tr, bg;
let scale = 1;
let canvasDots = { widthDots: 203, lengthDots: 2233, dpi: 300 };
let onSelect = () => {};
let selection = [];

const d2p = (d) => d * scale;
const p2d = (p) => Math.round(p / scale);

export function initCanvas(containerId, selectHandler) {
  onSelect = selectHandler;
  stage = new Konva.Stage({ container: containerId, width: 10, height: 10 });
  layer = new Konva.Layer();
  stage.add(layer);

  bg = new Konva.Rect({ x: 0, y: 0, fill: '#ffffff', listening: true });
  layer.add(bg);

  tr = new Konva.Transformer({ rotationSnaps: [0, 90, 180, 270],
    enabledAnchors: ['top-left', 'top-right', 'bottom-left', 'bottom-right'] });
  layer.add(tr);

  stage.on('click tap', (e) => {
    if (e.target === bg || e.target === stage) { setSelection([]); return; }
    const node = outermost(e.target);
    if (e.evt && e.evt.shiftKey) {
      const i = selection.indexOf(node);
      if (i >= 0) selection.splice(i, 1); else selection.push(node);
      setSelection(selection.slice());
    } else {
      setSelection([node]);
    }
  });

  stage.on('dblclick dbltap', (e) => {
    if (e.target === bg || e.target === stage) return;
    // Drill into a group: select the actual clicked leaf for editing.
    if (e.target.getParent() && e.target.getParent().getAttr('type') === 'GROUP') {
      setSelection([e.target]);
    }
  });

  resize(canvasDots);
}

export function resize(dots) {
  canvasDots = { ...dots };
  scale = Math.min(MAX_DISPLAY_HEIGHT / dots.lengthDots, 2);
  stage.width(dots.widthDots * scale);
  stage.height(dots.lengthDots * scale);
  bg.width(dots.widthDots * scale);
  bg.height(dots.lengthDots * scale);
  applyLayout();
  layer.draw();
}

export function setBackgroundColor(c) { bg.fill(c || '#ffffff'); layer.draw(); }
export function getCanvasDots() { return { ...canvasDots }; }
export function getScale() { return scale; }
export function getSelection() { return selection.slice(); }
export { layer, tr };

// ---- node helpers --------------------------------------------------------

function contentNodes() {
  return layer.getChildren(n => n !== bg && n.className !== 'Transformer');
}
function isGroup(n) { return n.getAttr('type') === 'GROUP'; }
function outermost(node) {
  let n = node;
  while (n.getParent() && n.getParent() !== layer) n = n.getParent();
  return n;
}

function labelFor(binding) {
  return { FULL_NAME: 'First Last', EVENT_NAME: 'Event', ASSOCIATION_NAME: 'Association',
    FIRST_NAME: 'First', LAST_NAME: 'Last', BARCODE_VALUE: '12345' }[binding] || binding || 'Text';
}
function displayText(spec) {
  if (spec.type === 'STATIC_TEXT') return spec.value || 'Text';
  return spec.sampleText || labelFor(spec.binding); // TEXT
}

// Create a Konva node for a leaf spec (positions in px applied by caller/layout).
function makeLeaf(s) {
  const common = { x: d2p(s.x || 0), y: d2p(s.y || 0), rotation: s.rotation || 0,
    width: d2p(s.widthDots), height: d2p(s.heightDots), draggable: true };
  let node;
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    node = new Konva.Text({ ...common, text: displayText(s), fontSize: d2p(s.fontSize || 24),
      fontFamily: 'Poppins', fill: '#111' });
  } else if (s.type === 'BARCODE') {
    node = new Konva.Rect({ ...common, fill: '#d0d0d0', stroke: '#333', strokeWidth: 1 });
  } else if (s.type === 'IMAGE') {
    node = new Konva.Rect({ ...common, fill: '#e8eefc', stroke: '#88a', dash: [6, 4] });
    if (s.assetId) loadImageInto(node, s.assetId);
  } else {
    node = new Konva.Rect({ ...common, fill: '#111' });
  }
  Object.entries(s).forEach(([k, v]) => { if (k !== 'children') node.setAttr(k, v); });
  wireLeaf(node);
  return node;
}

function wireLeaf(node) {
  node.on('transformend dragend', () => {
    node.setAttr('widthDots', Math.max(1, p2d(node.width() * node.scaleX())));
    node.setAttr('heightDots', Math.max(1, p2d(node.height() * node.scaleY())));
    node.setAttr('rotation', Math.round(node.rotation() / 90) * 90 % 360);
    if (node.getParent() === layer) { node.setAttr('x', p2d(node.x())); node.setAttr('y', p2d(node.y())); }
    if (node.className === 'Text') node.setAttr('fontSize', Math.max(6, p2d(node.fontSize() * node.scaleX())));
    node.scaleX(1); node.scaleY(1);
    if (node.getParent() !== layer) applyLayout();
    onSelect(node);
    layer.draw();
  });
}

function loadImageInto(rect, assetId) {
  const img = new window.Image();
  img.onload = () => {
    rect.fillPatternImage(img);
    rect.fillPatternScale({ x: rect.width() / img.width, y: rect.height() / img.height });
    rect.stroke(null); rect.dash([]);
    layer.draw();
  };
  img.src = '/api/templates/assets/' + assetId;
}

// Public: add a brand-new top-level leaf from the toolbox.
export function addElement(spec) {
  const s = { id: spec.id || nextId(), rotation: 0, x: 20, y: 20, ...spec };
  const node = makeLeaf(s);
  layer.add(node);
  setSelection([node]);
  layer.draw();
  return node;
}

// ---- group layout (mirrors the renderer in px) ---------------------------

function sizePx(node) {
  if (!isGroup(node)) return { w: d2p(node.getAttr('widthDots')), h: d2p(node.getAttr('heightDots')) };
  const dir = node.getAttr('stackDirection') || 'LENGTH';
  const margin = d2p(node.getAttr('marginDots') || 0);
  const kids = node.getChildren();
  let along = 0, cross = 0;
  kids.forEach((c, i) => {
    const s = sizePx(c);
    const a = dir === 'LENGTH' ? s.h : s.w;
    const cr = dir === 'LENGTH' ? s.w : s.h;
    along += a; if (i < kids.length - 1) along += margin;
    cross = Math.max(cross, cr);
  });
  return dir === 'LENGTH' ? { w: cross, h: along } : { w: along, h: cross };
}

export function applyLayout() {
  contentNodes().forEach(n => { if (isGroup(n)) layoutGroup(n); });
}

function layoutGroup(group) {
  const dir = group.getAttr('stackDirection') || 'LENGTH';
  const margin = d2p(group.getAttr('marginDots') || 0);
  const align = group.getAttr('crossAlign') || 'START';
  const kids = group.getChildren();
  kids.forEach(c => { if (isGroup(c)) layoutGroup(c); });

  let crossSize = 0;
  kids.forEach(c => { const s = sizePx(c); crossSize = Math.max(crossSize, dir === 'LENGTH' ? s.w : s.h); });

  let cursor = 0;
  kids.forEach(c => {
    const s = sizePx(c);
    const axis = dir === 'LENGTH' ? s.h : s.w;
    const cross = dir === 'LENGTH' ? s.w : s.h;
    const off = align === 'START' ? 0 : align === 'CENTER' ? (crossSize - cross) / 2 : (crossSize - cross);
    if (dir === 'LENGTH') { c.x(off); c.y(cursor); } else { c.x(cursor); c.y(off); }
    cursor += axis + margin;
  });
}

// ---- selection + transformer --------------------------------------------

export function setSelection(nodes) {
  selection = nodes.filter(Boolean);
  tr.nodes(selection);
  const anyGroup = selection.some(isGroup);
  tr.resizeEnabled(!anyGroup);
  tr.rotateEnabled(!anyGroup);
  onSelect(selection.length === 1 ? selection[0] : null);
  layer.draw();
}

export function deleteSelected() {
  selection.forEach(n => n.destroy());
  setSelection([]);
  applyLayout();
  layer.draw();
}

// Apply an edited property from the panel to a node.
export function applyProp(node, key, value) {
  node.setAttr(key, value);
  if (node.className === 'Text') {
    if (key === 'value' || key === 'sampleText' || key === 'binding') {
      node.text(node.getAttr('type') === 'STATIC_TEXT'
        ? (node.getAttr('value') || 'Text')
        : (node.getAttr('sampleText') || labelFor(node.getAttr('binding'))));
    }
    if (key === 'fontSize') node.fontSize(d2p(value));
  }
  if (key === 'widthDots') node.width(d2p(value));
  if (key === 'heightDots') node.height(d2p(value));
  if (key === 'rotation') node.rotation(value);
  if (key === 'x' && node.getParent() === layer) node.x(d2p(value));
  if (key === 'y' && node.getParent() === layer) node.y(d2p(value));
  if (['stackDirection', 'marginDots', 'crossAlign', 'widthDots', 'heightDots'].includes(key)) applyLayout();
  layer.draw();
}

// ---- serialization (recursive) ------------------------------------------

const LEAF_KEYS = ['id', 'type', 'x', 'y', 'widthDots', 'heightDots', 'rotation',
  'binding', 'value', 'fontSize', 'font', 'symbology', 'showHumanReadable',
  'assetId', 'shape', 'thicknessDots', 'sampleText'];

function nodeToElement(node) {
  if (isGroup(node)) {
    return {
      id: node.getAttr('id'), type: 'GROUP',
      x: p2d(node.x()), y: p2d(node.y()),
      stackDirection: node.getAttr('stackDirection') || 'LENGTH',
      marginDots: node.getAttr('marginDots') || 0,
      crossAlign: node.getAttr('crossAlign') || 'START',
      children: node.getChildren().map(nodeToElement),
    };
  }
  const el = {};
  LEAF_KEYS.forEach(k => { const v = node.getAttr(k); if (v !== undefined && v !== null) el[k] = v; });
  return el;
}

export function serializeElements() {
  return contentNodes().map(nodeToElement);
}

function buildNode(spec, parent) {
  let node;
  if (spec.type === 'GROUP') {
    node = new Konva.Group({ x: d2p(spec.x || 0), y: d2p(spec.y || 0), draggable: parent === layer });
    node.setAttr('type', 'GROUP');
    node.setAttr('id', spec.id || nextId());
    node.setAttr('stackDirection', spec.stackDirection || 'LENGTH');
    node.setAttr('marginDots', spec.marginDots || 0);
    node.setAttr('crossAlign', spec.crossAlign || 'START');
    parent.add(node);
    (spec.children || []).forEach(child => buildNode(child, node));
  } else {
    node = makeLeaf(spec);
    node.draggable(parent === layer);
    parent.add(node);
  }
  return node;
}

export function loadElements(elements) {
  contentNodes().forEach(n => n.destroy());
  setSelection([]);
  (elements || []).forEach(el => buildNode(el, layer));
  applyLayout();
  layer.draw();
}
```

- [ ] **Step 2: Verify it loads (run the app)**

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
```
Open `/template-editor.html` (log in), add a few blocks (toolbox still works), confirm select/move/resize behave as before and the browser console is error-free. (Grouping buttons come in Task 3.) Stop with Ctrl+C.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat: recursive group layout, selection and serialization in the canvas"
```

---

## Task 2: Group operations module (group / ungroup / center)

**Files:**
- Create: `src/main/resources/static/js/editor/groupops.js`

- [ ] **Step 1: Create `groupops.js`**

`src/main/resources/static/js/editor/groupops.js`:

```js
import { layer, getSelection, setSelection, applyLayout, getScale, getCanvasDots } from './canvas.js';
import { nextId } from './state.js';

const Konva = window.Konva;
const isGroup = (n) => n.getAttr('type') === 'GROUP';

// Wrap the current top-level selection (>= 2 nodes) into a new group.
export function groupSelected() {
  const sel = getSelection().filter(n => n.getParent() === layer);
  if (sel.length < 2) { alert('Select at least two items (shift-click) to group.'); return; }

  // Origin = top-left of the selection's bounding box.
  const originX = Math.min(...sel.map(n => n.x()));
  const originY = Math.min(...sel.map(n => n.y()));

  const group = new Konva.Group({ x: originX, y: originY, draggable: true });
  group.setAttr('type', 'GROUP');
  group.setAttr('id', nextId());
  group.setAttr('stackDirection', 'LENGTH');
  group.setAttr('marginDots', 0);
  group.setAttr('crossAlign', 'START');
  layer.add(group);

  // Preserve visual order (top→bottom for the default LENGTH stack).
  sel.sort((a, b) => a.y() - b.y());
  sel.forEach(n => { n.moveTo(group); n.draggable(false); });

  applyLayout();          // auto-stack the new group
  setSelection([group]);
  layer.draw();
}

// Dissolve one level: reparent a selected group's children back to the layer (absolute coords).
export function ungroupSelected() {
  const sel = getSelection();
  if (sel.length !== 1 || !isGroup(sel[0])) { alert('Select a single group to ungroup.'); return; }
  const group = sel[0];
  const gx = group.x(), gy = group.y();
  const freed = [];
  group.getChildren().slice().forEach(child => {
    const ax = gx + child.x(), ay = gy + child.y();
    child.moveTo(layer);
    child.position({ x: ax, y: ay });
    child.draggable(true);
    if (!isGroup(child)) { child.setAttr('x', Math.round(ax / getScale())); child.setAttr('y', Math.round(ay / getScale())); }
    freed.push(child);
  });
  group.destroy();
  setSelection(freed);
  applyLayout();
  layer.draw();
}

// Center the selected node (or its outermost group) across the band width.
export function centerSelectedOnBand() {
  const sel = getSelection();
  if (sel.length !== 1) { alert('Select a single item or group to center.'); return; }
  let node = sel[0];
  while (node.getParent() && node.getParent() !== layer) node = node.getParent();
  const scale = getScale();
  const widthPx = getCanvasDots().widthDots * scale;
  const box = node.getClientRect({ relativeTo: layer });
  node.x(node.x() + (widthPx - box.width) / 2 - (box.x - node.x()));
  if (node.getAttr('type') !== 'GROUP') node.setAttr('x', Math.round(node.x() / scale));
  layer.draw();
}
```

- [ ] **Step 2: Commit** (wired up in Task 4)

```bash
git add src/main/resources/static/js/editor/groupops.js
git commit -m "feat: group/ungroup/center-on-band operations"
```

---

## Task 3: Properties panel — group form, sample text, grouped-member form

**Files:**
- Modify (full replacement): `src/main/resources/static/js/editor/properties.js`

- [ ] **Step 1: Replace `properties.js`**

`src/main/resources/static/js/editor/properties.js`:

```js
import { applyProp, layer } from './canvas.js';

const BINDINGS = ['EVENT_NAME', 'FIRST_NAME', 'LAST_NAME', 'FULL_NAME', 'ASSOCIATION_NAME', 'BARCODE_VALUE'];

export function showProperties(node) {
  const empty = document.getElementById('props-empty');
  const form = document.getElementById('props-form');
  const del = document.getElementById('btn-delete');

  if (!node) { empty.style.display = ''; form.style.display = 'none'; del.style.display = 'none'; return; }
  empty.style.display = 'none'; form.style.display = ''; del.style.display = '';

  const type = node.getAttr('type');
  const grouped = node.getParent() && node.getParent() !== layer;
  const rows = [];

  if (type === 'GROUP') {
    rows.push(selectRow('stackDirection', node.getAttr('stackDirection') || 'LENGTH', ['LENGTH', 'WIDTH']));
    rows.push(numberRow('marginDots', node.getAttr('marginDots') || 0));
    rows.push(selectRow('crossAlign', node.getAttr('crossAlign') || 'START', ['START', 'CENTER', 'END']));
  } else {
    if (!grouped) { rows.push(numberRow('x', node.getAttr('x'))); rows.push(numberRow('y', node.getAttr('y'))); }
    rows.push(numberRow('widthDots', node.getAttr('widthDots')));
    rows.push(numberRow('heightDots', node.getAttr('heightDots')));
    rows.push(selectRow('rotation', node.getAttr('rotation') || 0, ['0', '90', '180', '270']));
    if (type === 'TEXT') {
      rows.push(selectRow('binding', node.getAttr('binding'), BINDINGS));
      rows.push(numberRow('fontSize', node.getAttr('fontSize')));
      rows.push(textRow('sampleText', node.getAttr('sampleText')));
    } else if (type === 'STATIC_TEXT') {
      rows.push(textRow('value', node.getAttr('value')));
      rows.push(numberRow('fontSize', node.getAttr('fontSize')));
    } else if (type === 'BARCODE') {
      rows.push(selectRow('symbology', node.getAttr('symbology'), ['CODE128', 'CODE39', 'QR']));
      rows.push(checkboxRow('showHumanReadable', node.getAttr('showHumanReadable')));
    } else if (type === 'SHAPE') {
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/editor/properties.js
git commit -m "feat: group + sample-text property forms"
```

---

## Task 4: Buttons in the page + main wiring

**Files:**
- Modify: `src/main/resources/static/template-editor.html`
- Modify: `src/main/resources/static/js/editor/main.js`

- [ ] **Step 1: Add toolbox buttons**

In `template-editor.html`, inside the `<div class="editor-toolbox glass">`, add a new block after the existing "Add block" buttons (before the `<h3 style="margin-top:16px">Logo</h3>`):

```html
        <h3 style="margin-top:16px">Arrange</h3>
        <button class="btn tool-btn" id="btn-group">Group</button>
        <button class="btn tool-btn" id="btn-ungroup">Ungroup</button>
        <button class="btn tool-btn" id="btn-center">Center on band</button>
```

- [ ] **Step 2: Wire them in `main.js`**

Replace `src/main/resources/static/js/editor/main.js` with:

```js
import '/js/vendor/konva-9.3.20.min.js';
import { initCanvas, deleteSelected } from './canvas.js';
import { initToolbox } from './toolbox.js';
import { showProperties } from './properties.js';
import { initToolbar } from './toolbar.js';
import { listTemplates } from './api.js';
import { groupSelected, ungroupSelected, centerSelectedOnBand } from './groupops.js';

async function main() {
  await listTemplates(); // auth gate → 401 redirects to /login.html

  initCanvas('stage-container', showProperties);
  initToolbox();
  await initToolbar();

  document.getElementById('btn-delete').addEventListener('click', deleteSelected);
  document.getElementById('btn-group').addEventListener('click', groupSelected);
  document.getElementById('btn-ungroup').addEventListener('click', ungroupSelected);
  document.getElementById('btn-center').addEventListener('click', centerSelectedOnBand);

  document.addEventListener('keydown', (e) => {
    const tag = document.activeElement.tagName;
    if ((e.key === 'Delete' || e.key === 'Backspace') && tag !== 'INPUT' && tag !== 'SELECT') deleteSelected();
  });
}

main();
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/template-editor.html src/main/resources/static/js/editor/main.js
git commit -m "feat: wire group/ungroup/center buttons into the editor"
```

---

## Task 5: Preview uses sample text

**Files:**
- Modify: `src/main/resources/static/js/editor/toolbar.js`

- [ ] **Step 1: Build preview data from sample text**

In `toolbar.js`, change the preview handler to POST sample-text-derived data. Replace the `api.js` import line and the `btn-preview` handler.

Change the import:

```js
import { listTemplates, getTemplate, createTemplate, updateTemplate, previewPng, previewPngWithData } from './api.js';
import { serializeElements, loadElements, getCanvasDots, resize, setBackgroundColor } from './canvas.js';
```

Replace the `btn-preview` click handler body with:

```js
  $('btn-preview').addEventListener('click', async () => {
    if (!currentId) { alert('Save the template first, then preview.'); return; }
    try {
      const data = sampleDataFromElements(serializeElements());
      const url = await previewPngWithData(currentId, $('tpl-color').value, data);
      const img = $('preview-img'); img.src = url; img.style.display = 'block';
      window.open(url, '_blank');
    } catch (e) { alert('Preview failed: ' + e.message); }
  });
```

Add this helper at the bottom of `toolbar.js`:

```js
// Assemble a WristbandData body from each block's sampleText (falling back to sensible defaults).
function sampleDataFromElements(elements) {
  const data = { eventName: 'Pukkelpop 2026', firstName: 'Annechien', lastName: 'Van De Wall',
    associationName: 'Chiro Sint-Christina Brustem', barcodeValue: '12345654245524789' };
  const visit = (els) => els.forEach(el => {
    if (el.type === 'GROUP') { visit(el.children || []); return; }
    const s = el.sampleText;
    if (!s) return;
    switch (el.binding) {
      case 'EVENT_NAME': data.eventName = s; break;
      case 'FIRST_NAME': data.firstName = s; break;
      case 'LAST_NAME': data.lastName = s; break;
      case 'ASSOCIATION_NAME': data.associationName = s; break;
      case 'BARCODE_VALUE': data.barcodeValue = s; break;
      case 'FULL_NAME': { const [f, ...r] = s.split(' '); data.firstName = f; data.lastName = r.join(' '); break; }
      default: break;
    }
  });
  visit(elements);
  return data;
}
```

- [ ] **Step 2: Add `previewPngWithData` to `api.js`**

Append to `src/main/resources/static/js/editor/api.js`:

```js
export async function previewPngWithData(id, color, data) {
  const url = '/api/templates/' + id + '/preview' + (color ? '?color=' + encodeURIComponent(color) : '');
  const res = guard(await fetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  }));
  if (!res.ok) throw new Error('preview failed');
  return URL.createObjectURL(await res.blob());
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/editor/toolbar.js src/main/resources/static/js/editor/api.js
git commit -m "feat: live preview reflects per-block sample text"
```

---

## Task 6: End-to-end verification + docs

**Files:**
- Modify: `docs/template-designer.md`

- [ ] **Step 1: Syntax-check the modules**

Run: `cd src/main/resources/static/js/editor && for f in api.js state.js canvas.js toolbox.js properties.js toolbar.js groupops.js main.js; do node --check "$f" && echo "OK $f"; done; cd -`
Expected: `OK` for all eight.

- [ ] **Step 2: Run the app and verify**

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
```
With local Postgres up, open `/template-editor.html`, log in, and verify:

1. **Sample text** — add a Name (FULL_NAME) block; set its `sampleText` to "Annechien Van De Wall"; the canvas shows it.
2. **Free text** — add a Static text block; type into `value`; the canvas updates.
3. **Multi-select + Group** — shift-click two blocks → **Group**; they snap into a vertical stack.
4. **Group properties** — select the group; change `marginDots` (gap grows), `stackDirection` to WIDTH (re-lays across), `crossAlign` to CENTER (members re-center).
5. **Nested group** — group two items, then shift-select that group plus a third item → **Group** again; the inner group nests and lays out.
6. **Center on band** — select a group/item → **Center on band**; it centers across the width.
7. **Ungroup** — select a group → **Ungroup**; members return to free positioning.
8. **Save / Open** — Save, then **Open template…** → the groups, margins, alignment, and sample text reload intact (round-trip).
9. **Preview** — Preview shows a PNG where the sample text appears.

Fix issues found and re-run. Stop with Ctrl+C.

- [ ] **Step 3: Update docs**

In `docs/template-designer.md`, under "Using the editor", add a short paragraph:

```markdown
**Grouping & alignment:** shift-click to multi-select, then **Group** to stack items (set
direction, margin, and cross-alignment in the properties panel); groups can be nested. **Center on
band** centers an item/group across the width. Data blocks accept **sample text** that shows on the
canvas and drives the preview; static blocks take their text in the properties panel.
```

- [ ] **Step 4: Run the backend suite (no regressions)**

Run: `./mvnw test`
Expected: PASS (Plan-1 model/renderer tests included; this plan changes only static front-end assets + docs).

- [ ] **Step 5: Commit**

```bash
git add docs/template-designer.md
git commit -m "docs: document editor grouping, alignment and sample text"
```

---

## Done — Plan 2 deliverable

The editor supports multi-select, nestable groups with live stacking (direction + margin +
cross-align), center-on-band, per-block sample text (shown on canvas + driving the preview), and
free-text entry — round-tripping to the Plan-1 `GROUP` model.

**Self-review notes:**
- Spec coverage: center-on-band + group cross-align ✓; nested groups ✓; choosable stack direction + margin ✓; sample text saved + canvas + preview ✓; single-line free text ✓.
- Honest verification: editor behaviour is validated by the Task 6 run-the-app checklist (no JS test runner) and `node --check`; the model/renderer are covered by Plan 1's Java tests. Interactive verification is explicitly an unchecked, run-locally step.
- Risk (flagged): the grouped-member resize → re-layout and the `getClientRect`-based center math are the trickiest interactions; Task 6 exercises them. Group rotation is intentionally unsupported (spec).
- Consistency: editor group attrs (`stackDirection` LENGTH/WIDTH, `crossAlign` START/CENTER/END, `marginDots`) and serialization match Plan 1's model field names exactly.
```
