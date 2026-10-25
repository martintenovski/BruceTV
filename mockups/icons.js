const P = {
  home: '<path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10"/>',
  apps: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  input: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8M12 17v4"/>',
  game: '<path d="M6 8h12a4 4 0 014 4l-1 5a2.5 2.5 0 01-4.4 1L15 16H9l-1.6 2a2.5 2.5 0 01-4.4-1l-1-5a4 4 0 014-4zM8 10.5v3M6.5 12h3M16 11h.01M18 13h.01"/>',
  settings: '<path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2"/><circle cx="8" cy="17" r="2"/>',
  recent: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="M4 18l5-5 4 4 3-3 4 4"/>',
  power: '<path d="M12 3v9M6.3 6.8a8 8 0 1011.4 0"/>',
  wifi: '<path d="M2 9a15 15 0 0120 0M5 13a10 10 0 0114 0M8.5 16.5a5 5 0 017 0M12 20h.01"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 119.5 4a7 7 0 0010.5 10.5z"/>',
  play: '<path d="M7 4l13 8-13 8z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.4 1.4M17.6 17.6L19 19M5 19l1.4-1.4M17.6 6.4L19 5"/>',
  bolt: '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
  refresh: '<path d="M20 11a8 8 0 10-2.3 5.7M20 4v7h-7"/>',
};
document.querySelectorAll("i[data-i]").forEach(e => {
  e.outerHTML = `<svg class="i" viewBox="0 0 24 24">${P[e.dataset.i]}</svg>`;
});
