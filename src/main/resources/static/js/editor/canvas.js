import { nextId } from './state.js';

const Konva = window.Konva;

const MAX_DISPLAY_HEIGHT = 720;
let stage, layer, tr, bg;
let scale = 1;
let canvasDots = { widthDots: 330, lengthDots: 3300, dpi: 300 };
let onSelect = () => {};
let selection = [];

const d2p = (d) => d * scale;
const p2d = (p) => Math.round(p / scale);

// Non-geometry model fields stored as Konva attrs. Geometry (x/y/size/fontSize/rotation)
// lives ONLY on the Konva node (in pixels) and is derived to dots at serialize time —
// storing dots in Konva's native attrs would corrupt position/size on every edit.
const NON_GEO = ['binding', 'value', 'font', 'symbology', 'showHumanReadable',
  'assetId', 'shape', 'thicknessDots', 'sampleText'];

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
    if (e.target.getParent() && e.target.getParent().getAttr('elType') === 'GROUP') {
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
function isGroup(n) { return n.getAttr('elType') === 'GROUP'; }
function outermost(node) {
  let n = node;
  while (n.getParent() && n.getParent() !== layer) n = n.getParent();
  return n;
}

function labelFor(binding) {
  return { FULL_NAME: 'First Last', EVENT_NAME: 'Event', ASSOCIATION_NAME: 'Association',
    FIRST_NAME: 'First', LAST_NAME: 'Last', BARCODE_VALUE: '12345' }[binding] || binding || 'Text';
}
function textOf(node) {
  return node.getAttr('elType') === 'STATIC_TEXT'
    ? (node.getAttr('value') || 'Text')
    : (node.getAttr('sampleText') || labelFor(node.getAttr('binding')));
}

// Create a Konva node for a leaf spec. Geometry comes from the spec (in dots → px);
// non-geometry fields are stashed as attrs.
function makeLeaf(s) {
  const base = { x: d2p(s.x || 0), y: d2p(s.y || 0), rotation: s.rotation || 0, draggable: true };
  let node;
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    // Text auto-sizes to its content (no fixed width → no wrapping into an invisible sliver).
    node = new Konva.Text({ ...base, fontSize: d2p(s.fontSize || 24), fontFamily: 'Poppins', fill: '#111' });
  } else if (s.type === 'BARCODE') {
    node = new Konva.Rect({ ...base, width: d2p(s.widthDots || 1), height: d2p(s.heightDots || 1), fill: '#d0d0d0', stroke: '#333', strokeWidth: 1 });
  } else if (s.type === 'IMAGE') {
    node = new Konva.Rect({ ...base, width: d2p(s.widthDots || 1), height: d2p(s.heightDots || 1), fill: '#e8eefc', stroke: '#88a', dash: [6, 4] });
    if (s.assetId) loadImageInto(node, s.assetId);
  } else {
    node = new Konva.Rect({ ...base, width: d2p(s.widthDots || 1), height: d2p(s.heightDots || 1), fill: '#111' });
  }
  node.setAttr('id', s.id);
  node.setAttr('elType', s.type);
  NON_GEO.forEach(k => { if (s[k] !== undefined && s[k] !== null) node.setAttr(k, s[k]); });
  if (node.className === 'Text') node.text(textOf(node));
  wireLeaf(node);
  return node;
}

function wireLeaf(node) {
  node.on('transformend dragend', () => {
    if (node.className === 'Text') {
      // Resizing text scales the font; bake the scale into fontSize, then clear it.
      const nf = Math.max(2, node.fontSize() * node.scaleX());
      node.scaleX(1); node.scaleY(1);
      node.fontSize(nf);
    } else {
      const nw = Math.max(1, node.width() * node.scaleX());
      const nh = Math.max(1, node.height() * node.scaleY());
      node.scaleX(1); node.scaleY(1);
      node.width(nw); node.height(nh);
      if (typeof node.fillPatternImage === 'function' && node.fillPatternImage()) {
        const img = node.fillPatternImage();
        node.fillPatternScale({ x: nw / img.width, y: nh / img.height });
      }
    }
    node.rotation(Math.round(node.rotation() / 90) * 90 % 360);
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

// ---- group layout (mirrors the renderer, in px) --------------------------

function sizePx(node) {
  if (!isGroup(node)) return { w: node.width(), h: node.height() };
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

// Apply an edited property from the panel. Geometry keys write the Konva node directly
// (in px); non-geometry keys are stored as attrs.
export function applyProp(node, key, value) {
  switch (key) {
    case 'x': if (node.getParent() === layer) node.x(d2p(value)); break;
    case 'y': if (node.getParent() === layer) node.y(d2p(value)); break;
    case 'rotation': node.rotation(value); break;
    case 'fontSize': if (node.className === 'Text') node.fontSize(d2p(value)); break;
    case 'widthDots': if (node.className !== 'Text') node.width(d2p(value)); break;
    case 'heightDots': if (node.className !== 'Text') node.height(d2p(value)); break;
    case 'value':
    case 'sampleText':
    case 'binding':
      node.setAttr(key, value);
      if (node.className === 'Text') node.text(textOf(node));
      break;
    default:
      node.setAttr(key, value); // symbology, showHumanReadable, thicknessDots, group settings
  }
  if (['stackDirection', 'marginDots', 'crossAlign', 'widthDots', 'heightDots',
       'fontSize', 'value', 'sampleText', 'binding', 'rotation'].includes(key)) applyLayout();
  layer.draw();
}

// ---- serialization (recursive; geometry derived from the live node) ------

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
  const el = {
    id: node.getAttr('id'), type: node.getAttr('elType'),
    x: p2d(node.x()), y: p2d(node.y()),
    widthDots: Math.max(1, p2d(node.width())), heightDots: Math.max(1, p2d(node.height())),
    rotation: Math.round(node.rotation() / 90) * 90 % 360,
  };
  if (node.className === 'Text') el.fontSize = Math.max(1, p2d(node.fontSize()));
  NON_GEO.forEach(k => { const v = node.getAttr(k); if (v !== undefined && v !== null) el[k] = v; });
  return el;
}

export function serializeElements() {
  return contentNodes().map(nodeToElement);
}

function buildNode(spec, parent) {
  let node;
  if (spec.type === 'GROUP') {
    node = new Konva.Group({ x: d2p(spec.x || 0), y: d2p(spec.y || 0), draggable: parent === layer });
    node.setAttr('elType', 'GROUP');
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
