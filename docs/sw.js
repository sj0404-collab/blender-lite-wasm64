// The payload lives in this repo's release, not in the site: GitHub Pages cannot
// set response headers, and the wasm threads need COOP/COEP. So the service worker
// re-serves every same-origin response with those headers added - the same trick
// the original host achieves with server config - and fetches the payload from the
// mirror release over CORS, caching it, so after the first visit the app is offline.
const BASE = 'https://github.com/sj0404-collab/blender-lite-wasm64/releases/download/blender-pack-v1/';
const PAYLOAD = ['blender.wasm.zst', 'assets.tar.zst', 'wgsl-cache.json', 'manifest.json'];

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

function isolated(response) {
  const h = new Headers(response.headers);
  h.set('Cross-Origin-Opener-Policy', 'same-origin');
  h.set('Cross-Origin-Embedder-Policy', 'require-corp');
  h.set('Cross-Origin-Resource-Policy', 'same-origin');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: h });
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const name = url.pathname.split('/').pop();

  if (url.origin === self.location.origin && (PAYLOAD.includes(name) || name.endsWith('.zst'))) {
    e.respondWith((async () => {
      const cache = await caches.open('blender-pack-v1');
      const hit = await cache.match(BASE + name);
      if (hit) return isolated(hit);
      try {
        const res = await fetch(BASE + name, { mode: 'cors' });
        if (res.ok) {
          cache.put(BASE + name, res.clone());
          return isolated(res);
        }
        return res;
      } catch (err) {
        return new Response('mirror unreachable: ' + err, { status: 502 });
      }
    })());
    return;
  }

  if (url.origin === self.location.origin) {
    e.respondWith((async () => isolated(await fetch(req)))());
  }
});
