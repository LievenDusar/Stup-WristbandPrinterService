// Load Konva (global) before the canvas module uses window.Konva.
import '/js/vendor/konva-9.3.20.min.js';
import { initCanvas, deleteSelected } from './canvas.js';
import { initToolbox } from './toolbox.js';
import { showProperties } from './properties.js';
import { initToolbar } from './toolbar.js';
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
