// Load Konva (global) before the canvas module uses window.Konva.
import '/js/vendor/konva-9.3.20.min.js';
import { initCanvas, deleteSelected, setSnapToCenter, setSnapToQuarters } from './canvas.js';
import { initToolbox } from './toolbox.js';
import { showProperties } from './properties.js';
import { initToolbar } from './toolbar.js';
import { listTemplates } from './api.js';
import { groupSelected, ungroupSelected, toggleCenterOnBand, isSelectionCentered } from './groupops.js';

async function main() {
  // Auth gate: any 401 inside listTemplates redirects to /login.html.
  await listTemplates();

  const btnCenter = document.getElementById('btn-center');
  function refreshCenterBtn() { btnCenter.classList.toggle('active', isSelectionCentered()); }

  initCanvas('stage-container', (node) => { showProperties(node); refreshCenterBtn(); });
  initToolbox();
  await initToolbar();

  document.getElementById('btn-delete').addEventListener('click', deleteSelected);
  document.getElementById('btn-group').addEventListener('click', groupSelected);
  document.getElementById('btn-ungroup').addEventListener('click', ungroupSelected);
  btnCenter.addEventListener('click', () => { toggleCenterOnBand(); refreshCenterBtn(); });
  document.getElementById('snap-center').addEventListener('change',
    (e) => setSnapToCenter(e.target.checked));
  document.getElementById('snap-quarters').addEventListener('change',
    (e) => setSnapToQuarters(e.target.checked));

  document.addEventListener('keydown', (e) => {
    const tag = document.activeElement.tagName;
    if ((e.key === 'Delete' || e.key === 'Backspace') && tag !== 'INPUT' && tag !== 'SELECT') {
      deleteSelected();
    }
  });
}

main();
