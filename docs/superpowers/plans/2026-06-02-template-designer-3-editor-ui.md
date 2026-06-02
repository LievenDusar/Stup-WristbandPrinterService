# Wristband Template Designer — Plan 3: Editor UI (Konva.js)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A browser-based drag-and-drop editor (`/template-editor.html`) where staff design wristband layouts on a fixed canvas, drop component blocks from a toolbox, edit their properties, preview the result (colour-tinted PNG), and save them as templates via the Plan 1/2 API.

**Architecture:** A no-build, vanilla **ES-module** front-end served as static resources behind the existing admin-cookie auth. Konva.js (vendored, pinned) provides the canvas, drag, and a Transformer for resize/rotate. The page is split into focused modules: `api` (fetch wrappers), `state` (canvas ⇄ `TemplateDefinition` JSON), `canvas` (Konva stage + node factory), `toolbox`, `properties`, `toolbar`, and `main` (bootstrap + auth gate). Coordinates are stored in **printer dots**; the canvas renders at a fixed dots→pixel scale.

**Tech Stack:** HTML/CSS, native ES modules (`<script type="module">` — no bundler), Konva.js (vendored under `/js/vendor/`), the existing `/api/templates*` + `/api/wristbands/login` endpoints. One Java test for the security change (`SecurityConfigTest`); the UI itself is verified by running the app (no JS test runner in this project).

**Scope (Plan 3 of 3):** the editor UI only. All backend already exists (Plans 1–2). No new backend except adding `/template-editor.html` to the `SecurityConfig` permit-list.

**Conventions (verified):**
- Static admin pages are public *shells*; data calls require the admin cookie. `SecurityConfig` permits `/jobs.html`, `/login.html`, `/css/**`, `/js/**` — we add `/template-editor.html` (the `/js/**` rule already covers new scripts).
- Auth gate pattern (from `jobs.js`): call an authenticated endpoint; on `401`, `window.location.href = '/login.html'`. The browser sends the admin cookie automatically.
- Reuse `css/app.css` classes (`glass`, `btn`, `btn-primary`, `input`, `field`).
- `TemplateDefinition` JSON shape and enum names (UPPER_SNAKE) match Plan 1: `ElementType` {TEXT, STATIC_TEXT, BARCODE, IMAGE, SHAPE}, `DataBinding` {EVENT_NAME, FIRST_NAME, LAST_NAME, FULL_NAME, ASSOCIATION_NAME, BARCODE_VALUE}, `ShapeType` {BOX, LINE}. `TemplateElement` fields: `id,type,x,y,widthDots,heightDots,rotation,binding,value,fontSize,font,symbology,showHumanReadable,assetId,shape,thicknessDots`.
- `UpsertTemplateRequest`: `{ name, projectType, defaultPreviewColor, definition }`.

---

## File Structure

**Create:**
- `src/main/resources/static/template-editor.html` — page shell (toolbar, toolbox, canvas host, properties panel)
- `src/main/resources/static/css/editor.css` — editor-specific layout (3-pane)
- `src/main/resources/static/js/vendor/konva-9.3.20.min.js` — pinned Konva (downloaded)
- `src/main/resources/static/js/editor/main.js` — bootstrap + auth gate + wiring
- `src/main/resources/static/js/editor/api.js` — fetch wrappers for `/api/templates*`
- `src/main/resources/static/js/editor/state.js` — canvas ⇄ `TemplateDefinition` serialization
- `src/main/resources/static/js/editor/canvas.js` — Konva stage, scale, node factory, Transformer
- `src/main/resources/static/js/editor/toolbox.js` — toolbox buttons → add node
- `src/main/resources/static/js/editor/properties.js` — selected-node property form
- `src/main/resources/static/js/editor/toolbar.js` — name/projectType/colour/canvas, save, open, preview, export

**Modify:**
- `src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java` — permit `/template-editor.html`
- `src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java` — assert the page is not blocked by auth
- `src/main/resources/static/jobs.html` — add a header link to the editor (discoverability)
- `docs/template-designer.md` — flip Plan 3 status to done

---

## Task 1: Permit the editor page (security) + Java test

**Files:**
- Modify: `src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java`
- Modify: `src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java`

- [ ] **Step 1: Add the failing test**

In `SecurityConfigTest`, add (and `import static org.junit.jupiter.api.Assertions.assertNotEquals;`):

```java
    @Test
    void templateEditorPage_isPublic() throws Exception {
        // permitAll → request passes the security filter (no 401). Whether the static
        // resource is then served (200) or unmapped in the slice (404), it must not be 401.
        mockMvc.perform(get("/template-editor.html"))
            .andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=SecurityConfigTest`
Expected: FAIL — `/template-editor.html` currently hits `anyRequest().authenticated()` → 401.

- [ ] **Step 3: Add the page to the permit list**

In `SecurityConfig.filterChain`, add `"/template-editor.html",` to the `requestMatchers(...)` permitAll list (next to `"/jobs.html"`):

```java
                .requestMatchers(
                    "/jobs.html",
                    "/template-editor.html",
                    "/login.html",
                    "/css/**",
                    "/js/**",
                    "/api/wristbands/login",
                    "/api/wristbands/logout",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health"
                ).permitAll()
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=SecurityConfigTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stup/wristbandprinter/config/SecurityConfig.java \
        src/test/java/com/stup/wristbandprinter/config/SecurityConfigTest.java
git commit -m "feat: permit the template editor page"
```

---

## Task 2: Vendor Konva + page shell + editor CSS

**Files:**
- Create: `src/main/resources/static/js/vendor/konva-9.3.20.min.js`
- Create: `src/main/resources/static/css/editor.css`
- Create: `src/main/resources/static/template-editor.html`

- [ ] **Step 1: Vendor Konva (pinned)**

Download the pinned build (avoids a runtime CDN dependency):

```bash
mkdir -p src/main/resources/static/js/vendor
curl -fsSL https://unpkg.com/konva@9.3.20/konva.min.js \
  -o src/main/resources/static/js/vendor/konva-9.3.20.min.js
test -s src/main/resources/static/js/vendor/konva-9.3.20.min.js && echo "konva vendored"
```

Expected: `konva vendored` and a file > 200 KB.

- [ ] **Step 2: Create the editor CSS**

`src/main/resources/static/css/editor.css`:

```css
.editor-shell { display: flex; flex-direction: column; height: 100vh; }
.editor-topbar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 10px 16px; border-bottom: 1px solid rgba(255,255,255,0.12);
}
.editor-topbar .grow { flex: 1; }
.editor-topbar .input { width: auto; min-width: 140px; }
.editor-main { display: flex; flex: 1; min-height: 0; }
.editor-toolbox {
  width: 180px; padding: 12px; border-right: 1px solid rgba(255,255,255,0.12);
  overflow-y: auto;
}
.editor-toolbox h3 { font-size: 0.78rem; text-transform: uppercase; opacity: 0.6; margin: 0 0 8px; }
.tool-btn {
  display: block; width: 100%; text-align: left; margin-bottom: 8px;
  cursor: pointer;
}
.editor-canvas-host {
  flex: 1; display: flex; align-items: flex-start; justify-content: center;
  overflow: auto; padding: 24px; background: rgba(0,0,0,0.15);
}
#stage-container { box-shadow: 0 8px 30px rgba(0,0,0,0.35); }
.editor-props {
  width: 260px; padding: 12px; border-left: 1px solid rgba(255,255,255,0.12);
  overflow-y: auto;
}
.editor-props .field { margin-bottom: 10px; }
.editor-props label { display: block; font-size: 0.75rem; opacity: 0.7; margin-bottom: 3px; }
.editor-props .input, .editor-props select { width: 100%; }
.muted { opacity: 0.6; font-size: 0.82rem; }
.row { display: flex; gap: 8px; }
.row > * { flex: 1; }
```

- [ ] **Step 3: Create the page shell**

`src/main/resources/static/template-editor.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>STUP — Wristband Template Editor</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
  <link href="/css/app.css" rel="stylesheet">
  <link href="/css/editor.css" rel="stylesheet">
</head>
<body>
  <div class="editor-shell">
    <div class="editor-topbar glass">
      <strong>Template:</strong>
      <input class="input" id="tpl-name" placeholder="Template name">
      <input class="input" id="tpl-project" placeholder="Project type (optional)">
      <label class="muted">Colour
        <select id="tpl-color">
          <option value="white">white</option>
          <option value="red">red</option>
          <option value="blue">blue</option>
          <option value="green">green</option>
          <option value="yellow">yellow</option>
          <option value="orange">orange</option>
          <option value="pink">pink</option>
        </select>
      </label>
      <label class="muted">W
        <input class="input" id="tpl-width" type="number" value="203" style="width:80px">
      </label>
      <label class="muted">L
        <input class="input" id="tpl-length" type="number" value="2233" style="width:90px">
      </label>
      <label class="muted">DPI
        <input class="input" id="tpl-dpi" type="number" value="300" style="width:80px">
      </label>
      <span class="grow"></span>
      <select class="input" id="tpl-open"><option value="">Open template…</option></select>
      <button class="btn" id="btn-new">New</button>
      <button class="btn" id="btn-preview">Preview</button>
      <button class="btn" id="btn-export">Export ZPL</button>
      <button class="btn btn-primary" id="btn-save">Save</button>
    </div>

    <div class="editor-main">
      <div class="editor-toolbox glass">
        <h3>Add block</h3>
        <button class="btn tool-btn" data-add="FULL_NAME">Name</button>
        <button class="btn tool-btn" data-add="EVENT_NAME">Event</button>
        <button class="btn tool-btn" data-add="ASSOCIATION_NAME">Association</button>
        <button class="btn tool-btn" data-add="BARCODE">Barcode</button>
        <button class="btn tool-btn" data-add="STATIC_TEXT">Static text</button>
        <button class="btn tool-btn" data-add="IMAGE">Logo</button>
        <button class="btn tool-btn" data-add="BOX">Box</button>
        <button class="btn tool-btn" data-add="LINE">Line</button>
        <h3 style="margin-top:16px">Logo</h3>
        <input type="file" id="logo-file" accept="image/png,image/jpeg">
      </div>

      <div class="editor-canvas-host">
        <div id="stage-container"></div>
      </div>

      <div class="editor-props glass">
        <h3 style="margin-top:0">Properties</h3>
        <div id="props-empty" class="muted">Select an element to edit it.</div>
        <div id="props-form" style="display:none"></div>
        <button class="btn" id="btn-delete" style="display:none;margin-top:8px">Delete element</button>
      </div>
    </div>
  </div>

  <img id="preview-img" alt="" style="display:none">
  <script type="module" src="/js/editor/main.js"></script>
</body>
</html>
```

- [ ] **Step 4: Verify by running the app**

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
```
Open `http://localhost:8080/template-editor.html` (log in at `/login.html` first if redirected). Expected: the three-pane shell renders (topbar, toolbox, empty canvas host, properties panel). The canvas is wired in Task 4; it's empty here. Stop the app with Ctrl+C.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/template-editor.html \
        src/main/resources/static/css/editor.css \
        src/main/resources/static/js/vendor/konva-9.3.20.min.js
git commit -m "feat: add template editor page shell and vendored Konva"
```

---

## Task 3: API module + state (serialization) module

**Files:**
- Create: `src/main/resources/static/js/editor/api.js`
- Create: `src/main/resources/static/js/editor/state.js`

> Pure modules; verified indirectly when the canvas wires up (Task 4+). No standalone test runner.

- [ ] **Step 1: Create the API module**

`src/main/resources/static/js/editor/api.js`:

```js
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

export async function uploadAsset(file) {
  const fd = new FormData();
  fd.append('file', file);
  const res = guard(await fetch('/api/templates/assets', { method: 'POST', body: fd }));
  if (!res.ok) throw new Error('upload failed');
  return res.json(); // { id, name, width, height }
}
```

- [ ] **Step 2: Create the state module**

`src/main/resources/static/js/editor/state.js`:

```js
// Serialization between the in-memory editor model and the backend TemplateDefinition.
// The editor keeps elements as plain objects in dot-space; canvas.js mirrors them as Konva nodes.

export function newDefinition() {
  return { canvas: { widthDots: 203, lengthDots: 2233, dpi: 300 }, elements: [] };
}

// Build the UpsertTemplateRequest body from the toolbar fields + current elements.
export function toUpsertRequest(meta, canvas, elements) {
  return {
    name: meta.name,
    projectType: meta.projectType || null,
    defaultPreviewColor: meta.color || 'white',
    definition: { canvas, elements },
  };
}

let counter = 0;
export function nextId() { return 'el-' + (Date.now().toString(36)) + '-' + (counter++); }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/editor/api.js src/main/resources/static/js/editor/state.js
git commit -m "feat: editor API and state modules"
```

---

## Task 4: Canvas module — Konva stage, scale, node factory, Transformer

**Files:**
- Create: `src/main/resources/static/js/editor/canvas.js`

- [ ] **Step 1: Create the canvas module**

`src/main/resources/static/js/editor/canvas.js`:

```js
import { nextId } from './state.js';

// Konva is loaded as a global by the vendored script (see main.js import order).
const Konva = window.Konva;

const MAX_DISPLAY_HEIGHT = 720;     // px; the long wristband axis is scaled to fit this
let stage, layer, tr, bg;
let scale = 1;                       // pixels per dot
let canvasDots = { widthDots: 203, lengthDots: 2233, dpi: 300 };
let onSelect = () => {};

export function initCanvas(containerId, selectHandler) {
  onSelect = selectHandler;
  stage = new Konva.Stage({ container: containerId, width: 10, height: 10 });
  layer = new Konva.Layer();
  stage.add(layer);

  bg = new Konva.Rect({ x: 0, y: 0, fill: '#ffffff', listening: true });
  layer.add(bg);

  tr = new Konva.Transformer({
    rotationSnaps: [0, 90, 180, 270],
    enabledAnchors: ['top-left', 'top-right', 'bottom-left', 'bottom-right'],
  });
  layer.add(tr);

  // Click empty background → deselect.
  stage.on('click tap', (e) => {
    if (e.target === bg || e.target === stage) { select(null); }
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
  layer.draw();
}

export function setBackgroundColor(cssColor) {
  bg.fill(cssColor || '#ffffff');
  layer.draw();
}

export function getCanvasDots() { return { ...canvasDots }; }

// ---- node creation -------------------------------------------------------

const DEFAULTS = {
  TEXT: { widthDots: 30, heightDots: 400, fontSize: 28, font: '0' },
  STATIC_TEXT: { widthDots: 30, heightDots: 300, fontSize: 24, font: '0' },
  BARCODE: { widthDots: 120, heightDots: 400, symbology: 'CODE128', showHumanReadable: false },
  IMAGE: { widthDots: 150, heightDots: 80 },
  SHAPE: { widthDots: 150, heightDots: 6, thicknessDots: 4 },
};

// Create a node from an element spec (dot-space). Returns the Konva node.
export function addElement(spec) {
  const s = { id: spec.id || nextId(), rotation: 0, x: 20, y: 20, ...spec };
  let node;
  const common = {
    x: s.x * scale, y: s.y * scale, rotation: s.rotation, draggable: true,
    width: s.widthDots * scale, height: s.heightDots * scale,
  };

  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    node = new Konva.Text({
      ...common,
      text: s.type === 'STATIC_TEXT' ? (s.value || 'Text') : labelFor(s.binding),
      fontSize: (s.fontSize || 24) * scale, fontFamily: 'Poppins', fill: '#111',
    });
  } else if (s.type === 'BARCODE') {
    node = new Konva.Rect({ ...common, fill: '#d0d0d0', stroke: '#333', strokeWidth: 1 });
  } else if (s.type === 'IMAGE') {
    node = new Konva.Rect({ ...common, fill: '#e8eefc', stroke: '#88a', dash: [6, 4] });
    if (s.assetId) loadImageInto(node, s.assetId);
  } else { // SHAPE
    node = new Konva.Rect({ ...common, fill: '#111' });
  }

  // Stash the model fields on the node for serialization + the properties panel.
  Object.entries(s).forEach(([k, v]) => node.setAttr(k, v));
  node.on('click tap', () => select(node));
  node.on('transformend dragend', () => syncFromNode(node));
  layer.add(node);
  select(node);
  layer.draw();
  return node;
}

function labelFor(binding) {
  return { FULL_NAME: 'First Last', EVENT_NAME: 'Event', ASSOCIATION_NAME: 'Association',
    FIRST_NAME: 'First', LAST_NAME: 'Last', BARCODE_VALUE: '12345' }[binding] || binding || 'Text';
}

function loadImageInto(rect, assetId) {
  const img = new window.Image();
  img.onload = () => {
    const image = new Konva.Image({
      x: rect.x(), y: rect.y(), width: rect.width(), height: rect.height(),
      rotation: rect.rotation(), draggable: true, image: img,
    });
    Object.keys(rect.getAttrs()).forEach(k => {
      if (!['x', 'y', 'width', 'height', 'fill', 'stroke', 'dash'].includes(k)) image.setAttr(k, rect.getAttr(k));
    });
    image.on('click tap', () => select(image));
    image.on('transformend dragend', () => syncFromNode(image));
    rect.destroy();
    layer.add(image);
    select(image);
    layer.draw();
  };
  img.src = '/api/templates/assets/' + assetId;
}

// Keep stored dot-space attrs in sync after a drag/resize/rotate.
function syncFromNode(node) {
  node.setAttr('x', Math.round(node.x() / scale));
  node.setAttr('y', Math.round(node.y() / scale));
  node.setAttr('rotation', Math.round(node.rotation() / 90) * 90 % 360);
  const w = Math.max(1, Math.round((node.width() * node.scaleX()) / scale));
  const h = Math.max(1, Math.round((node.height() * node.scaleY()) / scale));
  node.setAttr('widthDots', w);
  node.setAttr('heightDots', h);
  if (node.className === 'Text') {
    node.setAttr('fontSize', Math.max(6, Math.round(node.fontSize() * node.scaleX() / scale)));
  }
  node.scaleX(1); node.scaleY(1);
  onSelect(node); // refresh the props panel with synced values
}

export function select(node) {
  if (!node) { tr.nodes([]); onSelect(null); layer.draw(); return; }
  tr.nodes([node]);
  onSelect(node);
  layer.draw();
}

export function deleteSelected() {
  const nodes = tr.nodes();
  if (!nodes.length) return;
  nodes.forEach(n => n.destroy());
  tr.nodes([]);
  onSelect(null);
  layer.draw();
}

// Apply a single edited property (from the panel) back onto the selected node.
export function applyProp(node, key, value) {
  node.setAttr(key, value);
  if (key === 'value' && node.className === 'Text') node.text(value || 'Text');
  if (key === 'binding' && node.className === 'Text') node.text(labelFor(value));
  if (key === 'fontSize' && node.className === 'Text') node.fontSize(value * scale);
  if (key === 'x') node.x(value * scale);
  if (key === 'y') node.y(value * scale);
  if (key === 'widthDots') node.width(value * scale);
  if (key === 'heightDots') node.height(value * scale);
  if (key === 'rotation') node.rotation(value);
  layer.draw();
}

// ---- (de)serialization ---------------------------------------------------

const MODEL_KEYS = ['id', 'type', 'x', 'y', 'widthDots', 'heightDots', 'rotation',
  'binding', 'value', 'fontSize', 'font', 'symbology', 'showHumanReadable',
  'assetId', 'shape', 'thicknessDots'];

export function serializeElements() {
  return layer.getChildren(n => n !== bg && n.className !== 'Transformer').map(node => {
    const el = {};
    MODEL_KEYS.forEach(k => { const v = node.getAttr(k); if (v !== undefined) el[k] = v; });
    return el;
  });
}

export function loadElements(elements) {
  layer.getChildren(n => n !== bg && n.className !== 'Transformer').forEach(n => n.destroy());
  tr.nodes([]);
  elements.forEach(addElement);
  select(null);
  layer.draw();
}
```

- [ ] **Step 2: Commit** (verified once `main.js` wires it in Task 7)

```bash
git add src/main/resources/static/js/editor/canvas.js
git commit -m "feat: editor canvas module (Konva stage, nodes, transformer)"
```

---

## Task 5: Toolbox module

**Files:**
- Create: `src/main/resources/static/js/editor/toolbox.js`

- [ ] **Step 1: Create the toolbox module**

`src/main/resources/static/js/editor/toolbox.js`:

```js
import { addElement } from './canvas.js';
import { uploadAsset } from './api.js';

// Maps a toolbox button to an element spec.
function specFor(add) {
  switch (add) {
    case 'BARCODE': return { type: 'BARCODE', binding: 'BARCODE_VALUE', symbology: 'CODE128', showHumanReadable: false, widthDots: 120, heightDots: 400 };
    case 'STATIC_TEXT': return { type: 'STATIC_TEXT', value: 'STAFF', fontSize: 24, font: '0', widthDots: 30, heightDots: 300 };
    case 'IMAGE': return { type: 'IMAGE', widthDots: 150, heightDots: 80 };
    case 'BOX': return { type: 'SHAPE', shape: 'BOX', thicknessDots: 4, widthDots: 150, heightDots: 100 };
    case 'LINE': return { type: 'SHAPE', shape: 'LINE', thicknessDots: 4, widthDots: 150, heightDots: 6 };
    default: return { type: 'TEXT', binding: add, fontSize: 28, font: '0', widthDots: 30, heightDots: 400 };
  }
}

export function initToolbox() {
  document.querySelectorAll('.tool-btn[data-add]').forEach(btn => {
    btn.addEventListener('click', () => addElement(specFor(btn.dataset.add)));
  });

  const fileInput = document.getElementById('logo-file');
  fileInput.addEventListener('change', async () => {
    const file = fileInput.files[0];
    if (!file) return;
    try {
      const asset = await uploadAsset(file);
      addElement({ type: 'IMAGE', assetId: asset.id, widthDots: Math.min(180, asset.width), heightDots: Math.min(120, asset.height) });
    } catch (e) { alert('Logo upload failed: ' + e.message); }
    fileInput.value = '';
  });
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/editor/toolbox.js
git commit -m "feat: editor toolbox module"
```

---

## Task 6: Properties panel module

**Files:**
- Create: `src/main/resources/static/js/editor/properties.js`

- [ ] **Step 1: Create the properties module**

`src/main/resources/static/js/editor/properties.js`:

```js
import { applyProp } from './canvas.js';

const BINDINGS = ['EVENT_NAME', 'FIRST_NAME', 'LAST_NAME', 'FULL_NAME', 'ASSOCIATION_NAME', 'BARCODE_VALUE'];

// Render the property form for the selected node (or the empty state when null).
export function showProperties(node) {
  const empty = document.getElementById('props-empty');
  const form = document.getElementById('props-form');
  const del = document.getElementById('btn-delete');

  if (!node) { empty.style.display = ''; form.style.display = 'none'; del.style.display = 'none'; return; }
  empty.style.display = 'none'; form.style.display = ''; del.style.display = '';

  const type = node.getAttr('type');
  const rows = [];
  rows.push(numberRow('x', node.getAttr('x')));
  rows.push(numberRow('y', node.getAttr('y')));
  rows.push(numberRow('widthDots', node.getAttr('widthDots')));
  rows.push(numberRow('heightDots', node.getAttr('heightDots')));
  rows.push(selectRow('rotation', node.getAttr('rotation'), ['0', '90', '180', '270']));

  if (type === 'TEXT') {
    rows.push(selectRow('binding', node.getAttr('binding'), BINDINGS));
    rows.push(numberRow('fontSize', node.getAttr('fontSize')));
  } else if (type === 'STATIC_TEXT') {
    rows.push(textRow('value', node.getAttr('value')));
    rows.push(numberRow('fontSize', node.getAttr('fontSize')));
  } else if (type === 'BARCODE') {
    rows.push(selectRow('symbology', node.getAttr('symbology'), ['CODE128', 'CODE39', 'QR']));
    rows.push(checkboxRow('showHumanReadable', node.getAttr('showHumanReadable')));
  } else if (type === 'SHAPE') {
    rows.push(numberRow('thicknessDots', node.getAttr('thicknessDots')));
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
git commit -m "feat: editor properties panel module"
```

---

## Task 7: Toolbar module + main bootstrap (auth gate, wiring)

**Files:**
- Create: `src/main/resources/static/js/editor/toolbar.js`
- Create: `src/main/resources/static/js/editor/main.js`

- [ ] **Step 1: Create the toolbar module**

`src/main/resources/static/js/editor/toolbar.js`:

```js
import { listTemplates, getTemplate, createTemplate, updateTemplate, previewPng } from './api.js';
import { serializeElements, loadElements, getCanvasDots, resize, setBackgroundColor } from './canvas.js';
import { toUpsertRequest, newDefinition } from './state.js';

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
      const url = await previewPng(currentId, $('tpl-color').value);
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
```

- [ ] **Step 2: Create the bootstrap**

`src/main/resources/static/js/editor/main.js`:

```js
// Load Konva (global) before the canvas module uses window.Konva.
import '/js/vendor/konva-9.3.20.min.js';
import { initCanvas } from './canvas.js';
import { initToolbox } from './toolbox.js';
import { showProperties } from './properties.js';
import { initToolbar } from './toolbar.js';
import { deleteSelected } from './canvas.js';
import { listTemplates } from './api.js';

async function main() {
  // Auth gate: any 401 inside listTemplates redirects to /login.html.
  await listTemplates();

  initCanvas('stage-container', showProperties);
  initToolbox();
  await initToolbar();

  document.getElementById('btn-delete').addEventListener('click', deleteSelected);
  document.addEventListener('keydown', (e) => {
    if ((e.key === 'Delete' || e.key === 'Backspace') && document.activeElement.tagName !== 'INPUT'
        && document.activeElement.tagName !== 'SELECT') {
      deleteSelected();
    }
  });
}

main();
```

> Note: `import '/js/vendor/konva-9.3.20.min.js';` runs Konva for its side-effect of defining `window.Konva` (the UMD build attaches to `window`). If a future Konva build is ESM-only, switch to `import Konva from ...` and pass it in; the vendored 9.3.20 UMD build sets the global.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/editor/toolbar.js src/main/resources/static/js/editor/main.js
git commit -m "feat: editor toolbar and bootstrap wiring"
```

---

## Task 8: Manual end-to-end verification, jobs.html link, docs

**Files:**
- Modify: `src/main/resources/static/jobs.html`
- Modify: `docs/template-designer.md`

- [ ] **Step 1: Add a link to the editor from the jobs page**

In `jobs.html`, add a link in the header area (next to the title/logout). Locate the header and add:

```html
<a class="btn" href="/template-editor.html">Template editor</a>
```

(Place it among the existing header controls; match the surrounding markup.)

- [ ] **Step 2: Run the app and verify the full flow**

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
```

With a local Postgres running (see README), open `http://localhost:8080/template-editor.html`, log in (`admin` / `local-admin`), then verify each:

1. **Toolbox add** — click each block (Name, Event, Association, Barcode, Static text, Box, Line); a node appears on the white wristband canvas and is selected.
2. **Move/resize/rotate** — drag a node; use the Transformer handles to resize and rotate (rotation snaps to 0/90/180/270).
3. **Properties** — select a node; edit x/y/size/rotation/fontSize/binding; the canvas updates.
4. **Logo** — choose a PNG via the Logo file input; it uploads and appears as an image node.
5. **Colour** — change the colour dropdown; the canvas background tints.
6. **Save** — enter a name, Save; an alert shows the slug; the template appears in "Open template…".
7. **Preview** — Preview opens a colour-tinted PNG in a new tab (rendered by Labelary). Requires outbound access to Labelary.
8. **Export ZPL** — downloads `<slug>.zpl` containing the `${BINDING}` snapshot.
9. **Open** — pick the saved template from "Open template…"; the canvas rebuilds losslessly.
10. **Reload safety** — a 401 (e.g. after logout) bounces to `/login.html`.

Fix any issues found, re-running as needed. Stop the app with Ctrl+C.

- [ ] **Step 3: Flip the Plan 3 status in the docs**

In `docs/template-designer.md`:
- Update the status banner to note all three plans implemented.
- In the Roadmap table, change Plan 3 from `⏳ Planned` to `✅ Done`.
- Optionally add a short "Using the editor" subsection pointing at `/template-editor.html`.

- [ ] **Step 4: Run the full backend suite (guard against regressions)**

Run: `./mvnw test`
Expected: PASS (132 tests + the new `SecurityConfigTest` case = 133).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/jobs.html docs/template-designer.md
git commit -m "feat: link editor from jobs page and document the designer UI"
```

---

## Done — Plan 3 deliverable

A working drag-and-drop wristband template editor at `/template-editor.html`: a scaled wristband
canvas, a toolbox of draggable blocks (duplicates allowed), a Konva Transformer for
move/resize/rotate (snapped to ZPL's four orientations), a properties panel, logo upload, colour
preview, save/open against `/api/templates`, Labelary PNG preview, and ZPL export. This completes
the three-plan Wristband Template Designer.

**Self-review notes (verified while writing):**
- Spec coverage: §8 editor UI (toolbox, canvas, properties, top bar) ✓; colour preview ✓; save/preview/export ✓; logo upload ✓.
- Enum/JSON contract matches Plans 1–2 exactly (UPPER_SNAKE `type`/`binding`/`shape`; `UpsertTemplateRequest` shape; `/api/templates*` paths and verbs).
- No bundler: native ES modules + a vendored UMD Konva that sets `window.Konva`; `/js/**` is already permitted, only `/template-editor.html` needed adding (Task 1, with a Java test).
- Honest verification: the project has no JS test runner, so the UI is verified by the Task 8 run-the-app checklist; the one automated test covers the security change. This is called out, not hidden.
- Known simplifications (logged, not silent): (1) barcodes render as a placeholder rectangle on the *canvas* — the real symbol only appears in the Labelary PNG preview and on the printer. (2) The Plan 2 `TemplateZplRenderer.appendBarcode` currently emits `^BC` (Code 128) **regardless** of the `symbology` value and stores the chosen symbology in the definition but does not yet act on it. The UI lets the user pick CODE128/CODE39/QR, but only CODE128 actually renders today; honouring CODE39/QR (`^B3`/`^BQ`) is a small renderer follow-up, out of scope for this plan. Flag this to the reviewer rather than implying full symbology support.
```
