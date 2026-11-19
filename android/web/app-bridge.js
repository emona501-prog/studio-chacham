/* ממיר לשמע — the link between the page and the Android app (runs only inside the app) */
(function(){
  "use strict";
  var A=window.MemirApp;if(!A)return;
  window.__MEMIR_APP=true;
  var LANG=(function(){try{return localStorage.getItem("audioconv.lang")||"he"}catch(e){return "he"}})();
  var M={
    he:{saved:"נשמר בתיקיית ההורדות: ",fail:"השמירה לא הצליחה",upd:"גרסה חדשה של האפליקציה: ",go:"עדכן עכשיו",later:"אחר כך",
        dl:"מוריד את העדכון… ",inst:"פותח את ההתקנה…",perm:"אשר \"התקנה ממקור זה\" וחזור, ואז לחץ שוב \"עדכן\"",err:"ההורדה נכשלה. נסה שוב"},
    en:{saved:"Saved to Downloads: ",fail:"Saving failed",upd:"A new version of the app: ",go:"Update now",later:"Later",
        dl:"Downloading the update… ",inst:"Opening the installer…",perm:"Allow \"install from this source\", come back, and tap \"Update\" again",err:"The download failed. Try again"},
    fr:{saved:"Enregistré dans Téléchargements : ",fail:"L'enregistrement a échoué",upd:"Nouvelle version de l'application : ",go:"Mettre à jour",later:"Plus tard",
        dl:"Téléchargement de la mise à jour… ",inst:"Ouverture de l'installation…",perm:"Autorisez « installer depuis cette source », revenez et touchez à nouveau « Mettre à jour »",err:"Le téléchargement a échoué. Réessayez"}
  }[LANG]||null;
  if(!M)M={};

  /* a file made in the app goes to the phone in pieces, then into "Downloads" or to the share menu */
  function send(blob,name,mode){
    var id=A.saveBegin(name,blob.type||"");if(!id)return Promise.reject(new Error("begin"));
    var CH=1536*1024,o=0;
    function next(){
      if(o>=blob.size)return Promise.resolve(A.saveEnd(id,mode));
      var part=blob.slice(o,o+CH);o+=CH;
      return new Promise(function(ok,no){var r=new FileReader();r.onload=function(){var s=String(r.result),i=s.indexOf(",");ok(i>=0?s.slice(i+1):"")};r.onerror=function(){no(r.error)};r.readAsDataURL(part)})
        .then(function(b64){if(b64&&!A.saveChunk(id,b64))throw new Error("write");return next()});
    }
    return next();
  }
  window.__memirSave=send;
  var click0=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){
    var h=this.href||"";
    if(this.hasAttribute("download")&&/^(blob:|data:)/.test(h)){
      var name=this.getAttribute("download")||"file";
      fetch(h).then(function(r){return r.blob()}).then(function(b){return send(b,name,"save")})
        .then(function(n){A.toast(n?(M.saved||"")+n:(M.fail||"x"))},function(){A.toast(M.fail||"x")});
      return;
    }
    return click0.call(this);
  };
  try{
    Object.defineProperty(navigator,"canShare",{configurable:true,value:function(d){return !!(d&&d.files&&d.files.length)}});
    Object.defineProperty(navigator,"share",{configurable:true,value:function(d){
      var f=d&&d.files&&d.files[0];if(!f)return Promise.reject(new DOMException("nothing to share","NotAllowedError"));
      return send(f,f.name||"file","share").then(function(n){if(!n)throw new DOMException("share failed","AbortError")})}});
  }catch(e){}

  /* keep the screen on while working */
  var locks=0;
  try{Object.defineProperty(navigator,"wakeLock",{configurable:true,value:{request:function(){
    locks++;A.keepAwake(true);
    var L={released:false,type:"screen",onrelease:null,addEventListener:function(){},removeEventListener:function(){},
      release:function(){if(!L.released){L.released=true;locks=Math.max(0,locks-1);if(!locks)A.keepAwake(false)}return Promise.resolve()}};
    return Promise.resolve(L)}}})}catch(e){}

  /* files shared into the app wait in the same place the site used (the "memir-share" cache) */
  window.__appShared=function(){
    var L=[];try{L=JSON.parse(A.takeShared()||"[]")}catch(e){}
    if(!L.length)return Promise.resolve(0);
    return caches.open("memir-share").then(function(c){
      return L.reduce(function(p,f,i){return p.then(function(){
        return fetch(f.url).then(function(r){return r.blob()}).then(function(b){
          return c.put("/__s/"+Date.now()+"_"+i,new Response(b,{headers:{"content-type":f.type||b.type||"application/octet-stream","x-name":encodeURIComponent(f.name)}}))})})},Promise.resolve())
    }).then(function(){return L.length});
  };

  /* the back button: closes the open window first */
  window.__memirBack=function(){
    var md=document.getElementById("miniDlg");if(md&&!md.hidden){md.hidden=true;return true}
    var ms=document.getElementById("mixScreen");
    var sh=[].slice.call(document.querySelectorAll(".sheet:not([hidden])")).filter(function(s){return s.offsetParent!==null||getComputedStyle(s).display!=="none"});
    if(sh.length){var s=sh[sh.length-1],b=s.querySelector('[id$="Close"]');
      if(b)b.click();else s.dispatchEvent(new MouseEvent("click",{bubbles:true}));return true}
    if(ms&&!ms.hidden){var cm=document.getElementById("closeMix");if(cm){cm.click();return true}}
    return false;
  };

  /* updates: the newest version from the app's download page on GitHub */
  var REPO="emona501-prog/studio-chacham";
  function newer(a,b){a=String(a).split(".").map(Number);b=String(b).split(".").map(Number);
    for(var i=0;i<Math.max(a.length,b.length);i++){var x=a[i]||0,y=b[i]||0;if(x!==y)return x>y}return false}
  function banner(ver,url){
    if(document.getElementById("memirUpd"))return;
    var d=document.createElement("div");d.id="memirUpd";d.className="noi18n";d.dir=LANG==="he"?"rtl":"ltr";
    d.style.cssText="position:fixed;left:10px;right:10px;top:10px;z-index:99999;background:#5B3FD9;color:#fff;border-radius:14px;padding:12px 14px;box-shadow:0 8px 30px rgba(0,0,0,.3);font:600 15px/1.4 Assistant,Arial,sans-serif";
    var t=document.createElement("div");t.textContent=(M.upd||"")+ver;d.appendChild(t);
    var row=document.createElement("div");row.style.cssText="display:flex;gap:8px;margin-top:10px";
    function btn(txt,bg,fn){var b=document.createElement("button");b.type="button";b.textContent=txt;b.style.cssText="flex:1;border:0;border-radius:10px;padding:10px;font:700 15px Assistant,Arial,sans-serif;background:"+bg+";color:"+(bg==="#fff"?"#5B3FD9":"#fff");b.onclick=fn;row.appendChild(b);return b}
    var go=btn(M.go||"OK","#fff",function(){go.disabled=true;t.textContent=(M.dl||"")+"0%";A.updateDownload(url)});
    btn(M.later||"x","rgba(255,255,255,.18)",function(){d.remove()});
    d.appendChild(row);document.body.appendChild(d);
    window.__memirUpd=function(p){
      if(p==="done"){t.textContent=M.inst||"";go.disabled=false}
      else if(p==="perm"){t.textContent=M.perm||"";go.disabled=false}
      else if(p==="err"){t.textContent=M.err||"";go.disabled=false}
      else t.textContent=(M.dl||"")+(p>=0?p+"%":"");
    };
  }
  var X={he:{btn:"🔄 בדיקת עדכון לאפליקציה",chk:"בודק…",none:"האפליקציה מעודכנת · גרסה ",off:"אין חיבור. נסה שוב כשיש אינטרנט",ver:"גרסת האפליקציה: "},
         en:{btn:"🔄 Check for an app update",chk:"Checking…",none:"The app is up to date · version ",off:"No connection. Try again when online",ver:"App version: "},
         fr:{btn:"🔄 Vérifier la mise à jour",chk:"Vérification…",none:"L'application est à jour · version ",off:"Pas de connexion. Réessayez en ligne",ver:"Version de l'application : "}}[LANG]||{};
  /* manual=true: the person asked, so always look now and always answer */
  function check(manual,say){
    var last=0;try{last=+localStorage.getItem("memir.updCheck")||0}catch(e){}
    if(!manual&&Date.now()-last<30*60e3)return;
    fetch("https://api.github.com/repos/"+REPO+"/releases/latest",{cache:"no-store"}).then(function(r){if(!r.ok)throw new Error("http");return r.json()}).then(function(j){
      try{localStorage.setItem("memir.updCheck",String(Date.now()))}catch(e){}
      var v=String(j.tag_name||"").replace(/^v/,""),a=(j.assets||[]).filter(function(x){return /\.apk$/i.test(x.name)})[0];
      if(v&&a&&newer(v,A.version())){var old=document.getElementById("memirUpd");if(old&&manual)old.remove();banner(v,a.browser_download_url);say&&say((M.upd||"")+v)}
      else say&&say((X.none||"")+A.version());
    }).catch(function(){say&&say(X.off||"")});
  }
  window.__memirCheckUpdate=function(){check(true)};
  function addButton(){
    var ver=document.querySelector(".ver");if(!ver||document.getElementById("memirUpdBtn"))return;
    var w=document.createElement("div");w.className="noi18n";w.dir=LANG==="he"?"rtl":"ltr";w.style.cssText="text-align:center;margin:8px 0 2px";
    var b=document.createElement("button");b.type="button";b.id="memirUpdBtn";b.textContent=X.btn||"Update";
    b.style.cssText="border:1.5px solid #8b7bf0;background:transparent;color:inherit;border-radius:12px;padding:9px 16px;font:600 14px Assistant,Arial,sans-serif";
    var st=document.createElement("div");st.style.cssText="font-size:12.5px;opacity:.75;margin-top:5px";st.textContent=(X.ver||"")+A.version();
    b.onclick=function(){b.disabled=true;st.textContent=X.chk||"";check(true,function(m){st.textContent=m;b.disabled=false})};
    w.appendChild(b);w.appendChild(st);ver.parentNode.insertBefore(w,ver.nextSibling);
  }
  function start(){addButton();setTimeout(check,3000)}
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",start);else start();
  document.addEventListener("visibilitychange",function(){if(!document.hidden)check()});
})();
