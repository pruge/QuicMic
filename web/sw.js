/*
 * QuicMic service worker — installability only.
 *
 * This worker exists solely so the browser considers QuicMic installable as a
 * home-screen app. It implements NO caching strategy on purpose:
 *
 *  - The page is a live control surface for a LAN server; serving stale HTML/JS
 *    would silently break pairing against an updated server.
 *  - The server already sends `ETag` + `Cache-Control: no-cache` on assets and
 *    `no-store` on API responses (see src/server/assets.rs / mod.rs), which is
 *    the caching policy we want.
 *
 * The fetch handler is therefore a deliberate pass-through: not calling
 * `respondWith()` forwards every request to the network exactly as if no
 * worker were present, while still satisfying the install criterion that a
 * service worker with a fetch handler is registered.
 */

self.addEventListener('install', () => {
    // No precache: activate immediately.
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', () => {
    // Pass-through by design — see the header comment above.
});
