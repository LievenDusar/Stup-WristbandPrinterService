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
  const base = { x: d2p(s.x || 0), y: d2p(s.y || 0), rotation: s.rotation || 0, draggable: true };
  const box = { width: d2p(s.widthDots || 1), height: d2p(s.heightDots || 1) };
  let node;
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    // Text auto-sizes to its content — no fixed width, so it never wraps into an
    // invisible sliver. Its box (widthDots/heightDots) is derived from the rendered bounds.
    node = new Konva.Text({ ...base, text: displayText(s), fontSize: d2p(s.fontSize || 24),
      fontFamily: 'Poppins', fill: '#111' });
  } else if (s.type === 'BARCODE') {
    node = new Konva.Rect({ ...base, ...box, fill: '#d0d0d0', stroke: '#333', strokeWidth: 1 });
  } else if (s.type === 'IMAGE') {
    node = new Konva.Rect({ ...base, ...box, fill: '#e8eefc', stroke: '#88a', dash: [6, 4] });
    if (s.assetId) loadImageInto(node, s.assetId);
  } else {
    node = new Konva.Rect({ ...base, ...box, fill: '#111' });
  }
  Object.entries(s).forEach(([k, v]) => { if (k !== 'children') node.setAttr(k, v); });
  if (node.className === 'Text') syncTextBox(node);
  wireLeaf(node);
  return node;
}

// Store a text node's layout box (widthDots/heightDots) from its rendered bounds.
function syncTextBox(node) {
  node.setAttr('widthDots', Math.max(1, p2d(node.width())));
  node.setAttr('heightDots', Math.max(1, p2d(node.height())));
}

function wireLeaf(node) {
  node.on('transformend dragend', () => {
    if (node.className === 'Text') {
      // Resizing text scales the font; apply it, then clear the transform scale.
      const nf = Math.max(2, node.fontSize() * node.scaleX());
      node.scaleX(1); node.scaleY(1);
      node.fontSize(nf);
      node.setAttr('fontSize', Math.max(6, p2d(nf)));
      syncTextBox(node);
    } else {
      // Apply the scaled size to the node (don't just reset scale, or it snaps back / jumps).
      const nw = Math.max(1, node.width() * node.scaleX());
      const nh = Math.max(1, node.height() * node.scaleY());
      node.scaleX(1); node.scaleY(1);
      node.width(nw); node.height(nh);
      node.setAttr('widthDots', Math.max(1, p2d(nw)));
      node.setAttr('heightDots', Math.max(1, p2d(nh)));
      if (typeof node.fillPatternImage === 'function' && node.fillPatternImage()) {
        const img = node.fillPatternImage();
        node.fillPatternScale({ x: nw / img.width, y: nh / img.height });
      }
    }
    node.setAttr('rotation', Math.round(node.rotation() / 90) * 90 % 360);
    if (node.getParent() === layer) { node.setAttr('x', p2d(node.x())); node.setAttr('y', p2d(node.y())); }
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
    syncTextBox(node); // text box follows content/font
  } else {
    if (key === 'widthDots') node.width(d2p(value));
    if (key === 'heightDots') node.height(d2p(value));
  }
  if (key === 'rotation') node.rotation(value);
  if (key === 'x' && node.getParent() === layer) node.x(d2p(value));
  if (key === 'y' && node.getParent() === layer) node.y(d2p(value));
  if (['stackDirection', 'marginDots', 'crossAlign', 'widthDots', 'heightDots',
       'fontSize', 'value', 'sampleText', 'binding'].includes(key)) applyLayout();
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
