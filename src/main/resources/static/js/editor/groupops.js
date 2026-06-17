import { layer, getSelection, setSelection, applyLayout, centerNodeOnBand } from './canvas.js';
import { nextId } from './state.js';

const Konva = window.Konva;
const isGroup = (n) => n.getAttr('elType') === 'GROUP';

// Wrap the current top-level selection (>= 2 nodes) into a new group.
export function groupSelected() {
  const sel = getSelection().filter(n => n.getParent() === layer);
  if (sel.length < 2) { alert('Select at least two items (shift-click) to group.'); return; }

  // Origin = top-left of the selection's bounding box.
  const originX = Math.min(...sel.map(n => n.x()));
  const originY = Math.min(...sel.map(n => n.y()));

  const group = new Konva.Group({ x: originX, y: originY, draggable: true });
  group.setAttr('elType', 'GROUP');
  group.setAttr('id', nextId());
  group.setAttr('stackDirection', 'LENGTH');
  group.setAttr('marginDots', 0);
  group.setAttr('crossAlign', 'CENTER'); // wristband text is usually centered
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
    freed.push(child);
  });
  group.destroy();
  setSelection(freed);
  applyLayout();
  layer.draw();
}

// Toggle the centerOnBand flag on the selected element/group. When on, lock horizontal
// drag (dragBoundFunc keeps x centered) and center immediately; when off, free it.
export function toggleCenterOnBand() {
  const sel = getSelection();
  if (sel.length !== 1) { alert('Select a single item or group to center.'); return; }
  let node = sel[0];
  while (node.getParent() && node.getParent() !== layer) node = node.getParent();
  const on = !node.getAttr('centerOnBand');
  node.setAttr('centerOnBand', on || undefined);
  if (on) {
    centerNodeOnBand(node);
    node.dragBoundFunc(function (pos) {
      // keep the locked (centered) x, allow y to move
      return { x: this.absolutePosition().x, y: pos.y };
    });
  } else {
    node.dragBoundFunc(null);
  }
  setSelection([node]); // refresh transformer + button state
  layer.draw();
}

// Whether the current single selection (outermost) is centered — for button state.
export function isSelectionCentered() {
  const sel = getSelection();
  if (sel.length !== 1) return false;
  let node = sel[0];
  while (node.getParent() && node.getParent() !== layer) node = node.getParent();
  return !!node.getAttr('centerOnBand');
}
