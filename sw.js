// סטודיו חכם – עבודה גם בלי אינטרנט, והפרדה על כמה ליבות.
// GitHub Pages לא שולח את שתי הכותרות שמאפשרות לדף לעבוד על כמה ליבות (COOP/COEP), אז ה-worker הזה מוסיף אותן.
const CACHE='studio-v3';
const SHELL=['./','index.html','manifest.json','icon-192.png','icon-512.png','icon-maskable-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL)).catch(()=>{}));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==CACHE&&x!=='memir-share').map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
function coi(r){
  if(!r||r.status===0||r.type==='opaque'||r.type==='opaqueredirect')return r;
  const h=new Headers(r.headers);
  h.set('Cross-Origin-Embedder-Policy','credentialless');
  h.set('Cross-Origin-Opener-Policy','same-origin');
  return new Response(r.body,{status:r.status,statusText:r.statusText,headers:h});
}
self.addEventListener('fetch',e=>{
  const req=e.request;if(req.method!=='GET')return;const url=new URL(req.url);
  if(url.origin===location.origin&&url.pathname.includes('/models/'))return; // המודל נשמר באפליקציה עצמה
  const cdn=/cdn\.jsdelivr\.net|fonts\.(googleapis|gstatic)\.com/.test(url.host);
  if(url.origin!==location.origin&&!cdn)return;
  if(cdn){e.respondWith(caches.open(CACHE).then(c=>c.match(req).then(h=>h||fetch(req).then(r=>{if(r.ok)c.put(req,r.clone());return r}))));return}
  e.respondWith(fetch(req).then(r=>{if(r.ok){const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp))}return coi(r)})
    .catch(()=>caches.match(req).then(r=>coi(r||null)||caches.match('index.html').then(coi))));
});
