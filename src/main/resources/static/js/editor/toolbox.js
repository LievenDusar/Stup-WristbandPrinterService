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
