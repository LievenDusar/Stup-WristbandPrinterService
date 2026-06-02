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
