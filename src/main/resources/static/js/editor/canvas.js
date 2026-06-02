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
