// Service worker: ưu tiên mạng (luôn lấy bản mới nhất), mất mạng thì dùng bản đã lưu
const CACHE = 'so-tai-chinh-v2';
const SHELL = ['./', 'index.html', 'manifest.json', 'icon-192.png', 'icon-512.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) {
    return Promise.all(ks.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin) return;   // chỉ xử lý file của chính web
  e.respondWith(
    fetch(e.request, { cache: 'no-cache' }).then(function (r) {   // luôn hỏi máy chủ xem có bản mới không
      const copy = r.clone();
      caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
      return r;
    }).catch(function () {
      return caches.match(e.request).then(function (m) { return m || caches.match('index.html'); });
    })
  );
});
