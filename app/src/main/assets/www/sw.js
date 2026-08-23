// கணக்கு தாள் — Service Worker
// App shell-ஐ cache பண்ணி, offline-லும் app திறக்க உதவும்.
// Supabase (cloud) calls மற்றும் CDN scripts எப்போதும் network-க்கு போகும்.

const CACHE_NAME = 'kanakku-cache-v1';
const APP_SHELL = [
  './',
  './index.html',
  './manifest.json',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL))
      .catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter((n) => n !== CACHE_NAME).map((n) => caches.delete(n)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Same-origin GET requests மட்டும் intercept பண்ணுவோம்.
  // Supabase API / CDN (cross-origin) requests எப்போதும் நேரடியா network-க்கு போகும்.
  if (url.origin !== self.location.origin || event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request)
      .then((res) => {
        const resClone = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, resClone));
        return res;
      })
      .catch(() =>
        caches.match(event.request).then((cached) => cached || caches.match('./index.html'))
      )
  );
});
