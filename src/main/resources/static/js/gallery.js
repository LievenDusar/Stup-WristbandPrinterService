// src/main/resources/static/js/gallery.js
(function () {
  'use strict';

  const API_KEY_HEADER = 'X-API-Key';

  // API key is stored in sessionStorage by jobs.html login flow;
  // fall back to localStorage so gallery can be used standalone.
  function getApiKey() {
    return sessionStorage.getItem('apiKey') || localStorage.getItem('apiKey') || '';
  }

  async function fetchGallery() {
    const key = getApiKey();
    const res = await fetch('/api/wristbands/gallery', {
      headers: { [API_KEY_HEADER]: key }
    });
    if (!res.ok) throw new Error('Failed to load gallery (' + res.status + ')');
    return res.json();
  }

  async function fetchPreview(previewUrl, samplePayload) {
    const key = getApiKey();
    const res = await fetch(previewUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [API_KEY_HEADER]: key
      },
      body: samplePayload
    });
    if (!res.ok) throw new Error('Preview failed (' + res.status + ')');
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  }

  function buildTile(entry) {
    const tile = document.createElement('div');
    tile.className = 'gallery-tile';
    tile.innerHTML = `
      <img src="" alt="${entry.displayName}" data-loaded="false">
      <div class="tile-name">${entry.displayName}</div>
      <div class="tile-desc">${entry.description}</div>
    `;
    const img = tile.querySelector('img');

    // Lazy-load the preview image
    fetchPreview(entry.previewUrl, entry.samplePayload)
      .then(url => { img.src = url; img.dataset.loaded = 'true'; })
      .catch(() => { img.alt = 'Preview unavailable'; });

    tile.addEventListener('click', () => openModal(img.src, entry.displayName));
    return tile;
  }

  function openModal(imgSrc, title) {
    const modal = document.getElementById('modal');
    const modalImg = document.getElementById('modalImg');
    modalImg.src = imgSrc;
    modalImg.alt = title;
    modal.classList.add('open');
  }

  document.getElementById('modalClose').addEventListener('click', () => {
    document.getElementById('modal').classList.remove('open');
  });
  document.getElementById('modal').addEventListener('click', (e) => {
    if (e.target === e.currentTarget) {
      e.currentTarget.classList.remove('open');
    }
  });

  async function init() {
    const grid = document.getElementById('galleryGrid');
    try {
      const entries = await fetchGallery();
      grid.innerHTML = '';
      if (entries.length === 0) {
        grid.innerHTML = '<p>No wristband types registered.</p>';
        return;
      }
      entries.forEach(e => grid.appendChild(buildTile(e)));
    } catch (err) {
      grid.innerHTML = '<p class="error">Could not load gallery: ' + err.message + '</p>';
    }
  }

  init();
}());
