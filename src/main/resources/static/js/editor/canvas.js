import { nextId } from './state.js';

const Konva = window.Konva;

const MAX_DISPLAY_HEIGHT = 720;
const SNAP_THRESHOLD = 10; // screen pixels — distance at which the center locks to a guide line
const QUARTER_STROKE = '#9bb0c9'; // subtle slate-blue for quarter guides (center stays pink)
// Mirrors ZplGeneratorService.CHAR_ADVANCE_RATIO — Zebra font 0 (^A0) proportional advance ÷ size.
// Used to size editor text to the printed font-0 footprint (length = chars × fontSize × ratio).
const CHAR_ADVANCE_RATIO = 0.46;
let stage, layer, tr, bg;
let vGuide, hGuide, vQ1, vQ2, hQ1, hQ2; // dashed guides (Konva.Line); hidden unless actively snapped
let vGuides = [], hGuides = [];          // candidate lists: { frac, line } per axis
let snapEnabled = false;        // "Snap to center" (50%)
let quarterSnapEnabled = false; // "Snap to quarters" (25% / 75%)
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
  'assetId', 'shape', 'thicknessDots', 'sampleText', 'centerOnBand'];

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

  // Dashed snap guides. Non-interactive and hidden; revealed only while a drag is snapped.
  // moveToTop() in snapAxis() keeps them above content (content nodes are added later).
  // isGuide marks them so contentNodes() never serializes or destroys them.
  const base = { strokeWidth: 1, dash: [6, 4], listening: false, visible: false, isGuide: true };
  const pink = { ...base, stroke: '#ff3399' };       // center (primary)
  const slate = { ...base, stroke: QUARTER_STROKE }; // quarters (subtle)
  vGuide = new Konva.Line({ ...pink });  hGuide = new Konva.Line({ ...pink });
  vQ1 = new Konva.Line({ ...slate });    vQ2 = new Konva.Line({ ...slate });
  hQ1 = new Konva.Line({ ...slate });    hQ2 = new Konva.Line({ ...slate });
  layer.add(vGuide, hGuide, vQ1, vQ2, hQ1, hQ2);
  // Vertical lines snap the X axis; horizontal lines snap the Y axis. 0.5 = center, 0.25/0.75 = quarters.
  vGuides = [{ frac: 0.5, line: vGuide }, { frac: 0.25, line: vQ1 }, { frac: 0.75, line: vQ2 }];
  hGuides = [{ frac: 0.5, line: hGuide }, { frac: 0.25, line: hQ1 }, { frac: 0.75, line: hQ2 }];

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

  // Center-snapping: drag events bubble to the layer, so one pair of listeners
  // covers every draggable node (leaves + groups), however it was created.
  // Guard: the Transformer's resize/rotate anchors are draggable too and bubble
  // dragmove here; their outermost() is the Transformer, whose bbox includes the
  // rotater handle. Only snap real content nodes (they carry elType) so a
  // rotate/resize never snaps the transformer's inflated box.
  layer.on('dragmove', (e) => {
    const node = outermost(e.target);
    if (node.getAttr('elType')) applySnap(node);
  });
  layer.on('dragend', hideGuides);

  resize(canvasDots);
}

export function resize(dots) {
  canvasDots = { ...dots };
  scale = Math.min(MAX_DISPLAY_HEIGHT / dots.lengthDots, 2);
  const w = dots.widthDots * scale, h = dots.lengthDots * scale;
  stage.width(w);
  stage.height(h);
  bg.width(w);
  bg.height(h);
  // Position every guide from its fractional location. Guard: resize() runs once at the
  // end of initCanvas, after the candidate arrays are populated above.
  if (vGuides.length) {
    vGuides.forEach(g => g.line.points([g.frac * w, 0, g.frac * w, h]));
    hGuides.forEach(g => g.line.points([0, g.frac * h, w, g.frac * h]));
  }
  applyLayout();
  layer.draw();
}

export function setBackgroundColor(c) { bg.fill(c || '#ffffff'); layer.draw(); }
export function getCanvasDots() { return { ...canvasDots }; }
export function getScale() { return scale; }
export function getSelection() { return selection.slice(); }
export { layer, tr };

// Enable/disable center (50%) snapping. Disabling clears any guide left on screen.
export function setSnapToCenter(enabled) {
  snapEnabled = enabled;
  if (!enabled) hideGuides();
}

// Enable/disable quarter (25% / 75%) snapping. Disabling clears any guide left on screen.
export function setSnapToQuarters(enabled) {
  quarterSnapEnabled = enabled;
  if (!enabled) hideGuides();
}

function hideGuides() {
  [...vGuides, ...hGuides].forEach(g => g.line && g.line.visible(false));
}

// Is the line at this fraction currently active? 0.5 = center toggle, else quarter toggle.
function enabledFrac(frac) {
  return frac === 0.5 ? snapEnabled : quarterSnapEnabled;
}

// Snap one axis: pick the nearest ENABLED candidate line within threshold, move the node's
// center onto it, show only that line (hide the rest on this axis). Each axis is independent.
function snapAxis(node, guides, size, center, axis) {
  let best = null, bestDist = SNAP_THRESHOLD;
  for (const g of guides) {
    if (!enabledFrac(g.frac)) continue;
    const dist = Math.abs(center - g.frac * size);
    if (dist < bestDist) { bestDist = dist; best = g; }
  }
  for (const g of guides) {
    const on = g === best;
    if (on) {
      const target = g.frac * size;
      if (axis === 'x') node.x(node.x() + (target - center));
      else              node.y(node.y() + (target - center));
      g.line.moveToTop();
    }
    g.line.visible(on);
  }
}

// Snap the dragged node's bbox center to the nearest active guide on each axis.
// Reveals the matching dashed guide while snapped; called on every dragmove.
function applySnap(node) {
  if (!snapEnabled && !quarterSnapEnabled) return;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  snapAxis(node, vGuides, stage.width(),  r.x + r.width  / 2, 'x');
  snapAxis(node, hGuides, stage.height(), r.y + r.height / 2, 'y');
  layer.batchDraw();
}

// ---- node helpers --------------------------------------------------------

function contentNodes() {
  return layer.getChildren(n =>
    n !== bg && !n.getAttr('isGuide') && n.className !== 'Transformer');
}
function isGroup(n) { return n.getAttr('elType') === 'GROUP'; }
function outermost(node) {
  let n = node;
  while (n.getParent() && n.getParent() !== layer) n = n.getParent();
  return n;
}

// The element's stored position is the top-left of its axis-aligned bounding box (in dots).
// This matches how ZPL prints every orientation (^FO ≈ bbox top-left), so editor = print
// even for rotated text. Konva rotates around the node's own origin, so we translate between
// the two via getClientRect.
function bboxTLDots(node) {
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  return { x: p2d(r.x), y: p2d(r.y) };
}
function placeAtBboxTL(node, xDots, yDots) {
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  node.x(node.x() + (d2p(xDots) - r.x));
  node.y(node.y() + (d2p(yDots) - r.y));
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

// Size a text node to the printer's font-0 footprint: length = chars × fontSize × CHAR_ADVANCE_RATIO,
// thickness = fontSize. Glyphs are scaled to fill that box so the canvas matches the print.
function applyTextMetrics(node) {
  node.scaleX(1); node.scaleY(1);
  node.width('auto'); node.height('auto');                 // measure the natural glyph run
  const fsDots = p2d(node.fontSize());
  const chars  = Math.max(1, (textOf(node) || '').length);
  const targetWpx = d2p(Math.round(chars * fsDots * CHAR_ADVANCE_RATIO)); // length along text
  const targetHpx = node.fontSize();                                      // thickness = fontSize
  const sx = targetWpx / Math.max(1, node.width());
  const sy = targetHpx / Math.max(1, node.height());       // ≈ 1 (lineHeight 1 ⇒ height = fontSize)
  node.scaleX(sx); node.scaleY(sy);
  node.setAttr('fitScaleY', sy);                           // lets resize separate gesture from fit
}

// Create a Konva node for a leaf spec. Geometry comes from the spec (in dots → px);
// non-geometry fields are stashed as attrs.
function makeLeaf(s) {
  const base = { x: d2p(s.x || 0), y: d2p(s.y || 0), rotation: s.rotation || 0, draggable: true };
  let node;
  if (s.type === 'TEXT' || s.type === 'STATIC_TEXT') {
    // Helvetica/Arial = on-screen stand-in for the printer's resident font 0 (^A0 / CG Triumvirate).
    node = new Konva.Text({ ...base, fontSize: d2p(s.fontSize || 24),
      fontFamily: 'Helvetica, Arial, sans-serif', fill: '#111' });
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
  if (node.className === 'Text') { node.text(textOf(node)); applyTextMetrics(node); }
  wireLeaf(node);
  return node;
}

function wireLeaf(node) {
  node.on('transformend dragend', () => {
    if (node.className === 'Text') {
      // scaleX also carries the font-0 fit (condense), so read the resize gesture from scaleY
      // (whose fit factor is ≈1), bake it into fontSize, then re-derive the box + fit.
      const gesture = node.scaleY() / (node.getAttr('fitScaleY') || 1);
      node.fontSize(Math.max(2, node.fontSize() * gesture));
      applyTextMetrics(node);
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
  placeAtBboxTL(node, s.x, s.y); // stored x/y = bbox top-left
  setSelection([node]);
  layer.draw();
  return node;
}

// ---- group layout (mirrors the renderer, in px) --------------------------

function sizePx(node) {
  if (!isGroup(node)) {
    const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
    return { w: r.width, h: r.height }; // rotated bounding box, so layout/centering is rotation-aware
  }
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

// Center a top-level node's bbox on the band width (px). Used for centerOnBand nodes.
export function centerNodeOnBand(node) {
  const widthPx = canvasDots.widthDots * scale;
  const r = node.getClientRect({ relativeTo: layer, skipStroke: true });
  node.x(node.x() + (widthPx / 2 - (r.x + r.width / 2)));
}

export function applyLayout() {
  contentNodes().forEach(n => { if (isGroup(n)) layoutGroup(n); });
  contentNodes().forEach(n => { if (n.getAttr('centerOnBand')) centerNodeOnBand(n); });
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
    const slotX = dir === 'LENGTH' ? off : cursor;
    const slotY = dir === 'LENGTH' ? cursor : off;
    // Position so the child's bounding-box top-left (relative to the group) lands at the slot —
    // matters for rotated children whose Konva origin != bbox top-left.
    const rel = c.getClientRect({ relativeTo: group, skipStroke: true });
    c.x(c.x() + (slotX - rel.x));
    c.y(c.y() + (slotY - rel.y));
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
  const top = node.getParent() === layer;
  switch (key) {
    case 'x': if (top) placeAtBboxTL(node, value, bboxTLDots(node).y); break;
    case 'y': if (top) placeAtBboxTL(node, bboxTLDots(node).x, value); break;
    case 'rotation': { const tl = bboxTLDots(node); node.rotation(value); if (top) placeAtBboxTL(node, tl.x, tl.y); break; }
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
  if (node.className === 'Text' && ['fontSize', 'value', 'sampleText', 'binding'].includes(key)) {
    applyTextMetrics(node);
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
      centerOnBand: node.getAttr('centerOnBand') || undefined,
      children: node.getChildren().map(nodeToElement),
    };
  }
  const tl = bboxTLDots(node);
  const el = {
    id: node.getAttr('id'), type: node.getAttr('elType'),
    x: tl.x, y: tl.y,
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
    if (spec.centerOnBand) node.setAttr('centerOnBand', true);
    parent.add(node);
    (spec.children || []).forEach(child => buildNode(child, node));
  } else {
    node = makeLeaf(spec);
    node.draggable(parent === layer);
    parent.add(node);
    if (parent === layer) placeAtBboxTL(node, spec.x || 0, spec.y || 0);
  }
  return node;
}

export function loadElements(elements) {
  contentNodes().forEach(n => n.destroy());
  setSelection([]);
  (elements || []).forEach(el => buildNode(el, layer));
  applyLayout();
  contentNodes().forEach(n => {
    if (n.getAttr('centerOnBand') && n.getParent() === layer) {
      n.dragBoundFunc(function (pos) { return { x: this.absolutePosition().x, y: pos.y }; });
    }
  });
  layer.draw();
}
