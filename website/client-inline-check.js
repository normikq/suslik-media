
const $ = id => document.getElementById(id);
async function api(path, options={}) {
  const response = await fetch(path, { credentials:"same-origin", ...options, headers:{"Content-Type":"application/json", ...(options.headers||{})} });
  const data = await response.json().catch(()=>({error:"Некорректный ответ сервера"}));
  if (!response.ok) throw new Error(data.error || `Не удалось выполнить запрос (${response.status})`);
  return data;
}
function showApp(authenticated) { $("loginPanel").classList.toggle("hidden", authenticated); $("appPanel").classList.toggle("hidden", !authenticated); }
async function checkSession() { try { const data=await api("/api/session"); showApp(data.authenticated); if(data.authenticated) loadLibrary(); } catch { showApp(false); } }
$("loginForm").addEventListener("submit", async e => {
  e.preventDefault(); $("loginStatus").textContent="Выполняется вход…"; $("loginStatus").className="status";
  try { await api("/api/login",{method:"POST",body:JSON.stringify({password:$("password").value})}); $("password").value=""; showApp(true); loadLibrary(); }
  catch(err) { $("loginStatus").textContent=err.message; $("loginStatus").className="status error"; }
});
$("logout").addEventListener("click", async()=>{try{await api("/api/logout",{method:"POST"});}catch{} showApp(false);});
async function wait(ms){return new Promise(resolve=>setTimeout(resolve,ms));}
const HISTORY_KEY = "moviePortal.searchHistory.v1";
const LIBRARY_SNAPSHOT_KEY = "moviePortal.librarySnapshot.v1";
const posterCache = new Map();
function readHistory(){try{const value=JSON.parse(localStorage.getItem(HISTORY_KEY)||"[]");return Array.isArray(value)?value:[];}catch{return [];}}
function saveHistory(value){try{localStorage.setItem(HISTORY_KEY,JSON.stringify(value.slice(0,30)));}catch{}}
function rememberПоиск(value){const history=readHistory().filter(item=>item.value!==value);history.unshift({value,at:Date.now(),title:value.startsWith("magnet:?")?"Загрузка по magnet-ссылке":"Проверка ссылки",status:"Отправлено"});saveHistory(history);renderHistory();}
function updateHistory(value, patch){const history=readHistory();const item=history.find(entry=>entry.value===value);if(item)Object.assign(item,patch,{updatedAt:Date.now()});saveHistory(history);renderHistory();}
function renderHistory(){const target=$("searchHistory");if(!target)return;target.replaceChildren();const history=readHistory();if(!history.length){const empty=document.createElement("p");empty.className="file-meta";empty.textContent="История поиска пуста.";target.append(empty);return;}for(const item of history){const row=document.createElement("div");row.className="history-item";const info=document.createElement("div");info.className="history-value";const titleRow=document.createElement("div");titleRow.className="history-title-row";const title=document.createElement("strong");title.textContent=item.title|| (item.value.startsWith("magnet:?")?"Загрузка по magnet-ссылке":"Проверка ссылки");title.style.display="block";titleRow.append(title);if(item.magnetId && !item.ready && !item.cancelled){const progress=document.createElement("div");progress.className="history-progress";const track=document.createElement("div");track.className="history-progress-track";track.setAttribute("role","progressbar");track.setAttribute("aria-valuemin","0");track.setAttribute("aria-valuemax","100");const fill=document.createElement("div");fill.className="history-progress-fill";const pct=item.percent==null?0:Math.max(0,Math.min(100,Number(item.percent)));fill.style.width=pct+"%";track.append(fill);const percent=document.createElement("span");percent.className="history-percent";percent.textContent=item.percent==null?"…":pct+"%";progress.append(track,percent);titleRow.append(progress);}const detail=document.createElement("div");detail.className="file-meta";detail.textContent=[item.status||"Отправлено",item.ready?"100%": ""].filter(Boolean).join(" · ");info.append(titleRow,detail);const actions=document.createElement("div");actions.className="history-actions";const activeСкачать=Boolean(item.magnetId && !item.ready && !item.cancelled && !item.failed);if(activeСкачать){const cancel=document.createElement("button");cancel.type="button";cancel.className="secondary";cancel.textContent="Отменить загрузку";cancel.addEventListener("click",async()=>{if(!confirm("Отменить загрузку и удалить magnet-ссылку из AllDebrid? Это действие нельзя отменить."))return;cancel.disabled=true;cancel.textContent="Отмена…";try{await api(`/api/magnets/${item.magnetId}/cancel`,{method:"POST"});updateHistory(item.value,{status:"Отменено",cancelled:true});}catch(err){alert(err.message);cancel.disabled=false;cancel.textContent="Отменить загрузку";}});actions.append(cancel);}else{const reuse=document.createElement("button");reuse.type="button";reuse.className="secondary";reuse.textContent="Использовать снова";reuse.addEventListener("click",()=>{if(item.sourceType==="torrent-file"){setInputMode("torrent");$("linkStatus").textContent="Выберите файл .torrent повторно: браузер не позволяет восстановить вложение автоматически.";$("linkStatus").className="status";$("torrentDropzone").focus();}else{setInputMode("magnet");$("url").value=item.value;$("url").focus();}});actions.append(reuse);if(item.ready || item.cancelled || item.failed){const remove=document.createElement("button");remove.type="button";remove.className="secondary";remove.textContent="×";remove.title="Удалить из истории";remove.setAttribute("aria-label","Удалить из истории");remove.addEventListener("click",event=>{event.preventDefault();event.stopPropagation();saveHistory(readHistory().filter(entry=>entry.value!==item.value));renderHistory();});actions.append(remove);}}row.append(info,actions);target.append(row);}}
$("clearHistory").addEventListener("click",()=>{saveHistory(readHistory().filter(item=>!(item.ready || item.cancelled || item.failed)));renderHistory();});
let inputMode = "magnet";
function setInputMode(mode){
  inputMode=mode; const magnet=mode==="magnet";
  $("magnetModeBtn").classList.toggle("selected",magnet); $("magnetModeBtn").setAttribute("aria-pressed",String(magnet));
  $("torrentModeBtn").classList.toggle("selected",!magnet); $("torrentModeBtn").setAttribute("aria-pressed",String(!magnet));
  $("magnetInputArea").classList.toggle("hidden",!magnet); $("torrentInputArea").classList.toggle("hidden",magnet);
  if(magnet){$("torrentФайл").value="";renderChosenTorrent();} else {$("url").value="";}
}
function renderChosenTorrent(){const file=$("torrentФайл").files?.[0]||null;$("torrentDropzone").classList.toggle("hidden",Boolean(file));$("torrentChosen").classList.toggle("hidden",!file);$("submitTorrentBtn").classList.toggle("hidden",!file);if(file){$("torrentChosenName").textContent=file.name;$("torrentChosenSize").textContent=(file.size/1024).toFixed(0)+" KB";}}
$("magnetModeBtn").addEventListener("click",()=>setInputMode("magnet"));
$("torrentModeBtn").addEventListener("click",()=>setInputMode("torrent"));
$("chooseTorrentBtn").addEventListener("click",()=>$("torrentФайл").click());
$("torrentDropzone").addEventListener("click",e=>{if(!e.target.closest("button"))$("torrentФайл").click();});
$("torrentDropzone").addEventListener("keydown",e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();$("torrentФайл").click();}});
$("torrentФайл").addEventListener("change",()=>{const file=$("torrentФайл").files?.[0];if(file&&file.size>1024*1024){$("torrentФайл").value="";renderChosenTorrent();$("linkStatus").textContent="Размер файла .torrent не должен превышать 1 МБ.";$("linkStatus").className="status error";return;}if(file&&!file.name.toLowerCase().endsWith(".torrent")){$("torrentФайл").value="";renderChosenTorrent();$("linkStatus").textContent="Выберите файл с расширением .torrent.";$("linkStatus").className="status error";return;}renderChosenTorrent();});
$("removeTorrentBtn").addEventListener("click",()=>{$("torrentФайл").value="";renderChosenTorrent();});
for(const ev of ["dragenter","dragover"])$("torrentDropzone").addEventListener(ev,e=>{e.preventDefault();$("torrentDropzone").classList.add("dragover");});
for(const ev of ["dragleave","drop"])$("torrentDropzone").addEventListener(ev,e=>{e.preventDefault();$("torrentDropzone").classList.remove("dragover");});
$("torrentDropzone").addEventListener("drop",e=>{const file=e.dataTransfer?.files?.[0];if(!file)return;e.preventDefault();if(file.size>1024*1024){$("linkStatus").textContent="Размер файла .torrent не должен превышать 1 МБ.";$("linkStatus").className="status error";return;}if(!file.name.toLowerCase().endsWith(".torrent")){$("linkStatus").textContent="Выберите файл с расширением .torrent.";$("linkStatus").className="status error";return;}const transfer=new DataTransfer();transfer.items.add(file);$("torrentФайл").files=transfer.files;renderChosenTorrent();});
$("linkForm").addEventListener("submit", async e=>{
  e.preventDefault();
  // The active mode is authoritative, so one submit can never send both types.
  const torrentФайл=inputMode==="torrent"?($("torrentФайл").files?.[0]||null):null;
  const value=inputMode==="magnet"?$("url").value.trim():"", btn=inputMode==="torrent"?$("submitTorrentBtn"):$("submitBtn");
  if(inputMode==="torrent"&&!torrentФайл){$("linkStatus").textContent="Сначала выберите файл .torrent.";$("linkStatus").className="status error";return;}
  if(torrentФайл&&torrentФайл.size>1024*1024){$("linkStatus").textContent="Размер файла .torrent не должен превышать 1 МБ.";$("linkStatus").className="status error";return;}
  if(inputMode==="magnet"&&!value){$("linkStatus").textContent="Введите magnet-ссылку или поддерживаемый URL.";$("linkStatus").className="status error";return;}
  const historyValue=torrentФайл?`torrentfile:${torrentФайл.name}:${torrentФайл.size}:${torrentФайл.lastModified}`:value;
  btn.disabled=true; btn.textContent="Выполняется…";
  $("linkStatus").textContent="Обработка ссылки…"; $("linkStatus").className="status";
  $("resultPanel").classList.add("hidden"); $("magnetPanel").classList.add("hidden");
  try {
    if(torrentФайл || value.startsWith("magnet:?")){
      let submitted;
      if (torrentФайл) {
        const bytes = await torrentФайл.arrayBuffer();
        submitted = await api("/api/torrents", { method:"POST", headers:{"Content-Type":"application/x-bittorrent","X-Torrent-Filename":encodeURIComponent(torrentФайл.name)}, body:bytes });
      } else {
        submitted = await api("/api/magnets",{method:"POST",body:JSON.stringify({magnet:value})});
      }
      // Only persist a search after the server accepted a real magnet/torrent.
      rememberПоиск(historyValue);
      const identity = submitted.sha256 || null;
      const existing = readHistory().find(entry => entry.value !== historyValue && ((identity && entry.sha256 === identity) || String(entry.magnetId || "") === String(submitted.id)));
      if (existing) {
        saveHistory(readHistory().filter(entry => entry.value !== historyValue));
        updateHistory(existing.value,{magnetId:submitted.id,sha256:identity||existing.sha256,title:submitted.name||(torrentФайл?torrentФайл.name.replace(/\.torrent$/i,""):"Загрузка по magnet-ссылке"),status:"Проверка состояния",ready:false,percent:0,sourceType:torrentФайл?"torrent-file":"magnet",sourceName:torrentФайл?.name||null});
      } else {
        updateHistory(historyValue,{magnetId:submitted.id,sha256:identity,title:submitted.name||(torrentФайл?torrentФайл.name.replace(/\.torrent$/i,""):"Загрузка по magnet-ссылке"),status:"Проверка состояния",ready:false,percent:0,sourceType:torrentФайл?"torrent-file":"magnet",sourceName:torrentФайл?.name||null});
      }
      const activeHistoryValue = existing ? existing.value : historyValue;
      $("magnetPanel").classList.remove("hidden");
      $("magnetStatus").textContent=`Added: ${submitted.name}. Проверка состояния…`;
      let status=null;
      function updateProgress(current){ /* Progress is displayed in История поиска only. */ }
      updateProgress({size:0,downloaded:0});
      for(let i=0;i<30;i++){
        await wait(3000);
        status=await api(`/api/magnets/${submitted.id}`);
        $("magnetStatus").textContent=`${status.filename || submitted.name}: ${status.status || "Обработка"}${status.size ? " · "+(status.size/1024/1024).toFixed(1)+" MB" : ""}`;
        updateProgress(status);
        const total=Number(status.size)||0, done=Number(status.downloaded)||0;
        updateHistory(activeHistoryValue,{magnetId:submitted.id,title:status.filename||submitted.name||"Загрузка по magnet-ссылке",status:status.status||"Обработка",percent:total>0?Math.floor(Math.min(100,done/total*100)):null,ready:Boolean(status.ready||status.statusCode===4)});
        if(status.statusCode===4 || status.ready) break;
        if(status.statusCode>=5) {
          updateHistory(activeHistoryValue,{status:status.status||"Ошибка",failed:true,ready:false,percent:null});
          throw new Error(`AllDebrid magnet processing failed: ${status.status}`);
        }
      }
      if(!status || !(status.statusCode===4 || status.ready)){
        updateHistory(activeHistoryValue,{status:"Всё ещё обрабатывается",magnetId:submitted.id});
        $("linkStatus").textContent="Magnet-ссылка ещё обрабатывается. Статус будет обновляться в истории поиска. Не отправляйте её повторно, чтобы не создать дубликат.";
        $("linkStatus").className="status error";
      } else {
        updateHistory(activeHistoryValue,{status:"Готово",percent:100,ready:true,magnetId:submitted.id});
        const result=await api(`/api/magnets/${submitted.id}/files`);
        const list=$("magnetФайлы"); list.replaceChildren();
        if(!result.files || !result.files.length) {
          $("magnetStatus").textContent += "  — готово, но ссылки на файлы не получены. Проверьте ответ API AllDebrid.";
        } else {
          for(const file of result.files){
            const wrap=document.createElement("div"); wrap.style.cssText="width:100%;padding:10px 0;border-bottom:1px solid #292d3a";
            const title=document.createElement("div"); title.textContent=file.name; title.style.cssText="overflow-wrap:anywhere;margin-bottom:8px";
            if (typeof file.url === "string" && /^https?:\/\//i.test(file.url)) {
              const actions=document.createElement("div"); actions.className="file-link-actions";
              const directGroup=document.createElement("div"); directGroup.className="split-actions";
              const directКопировать=document.createElement("button"); directКопировать.className="secondary split-first"; directКопировать.textContent="Копировать прямую ссылку";
              directКопировать.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(file.url);$("linkStatus").textContent="Ссылка успешно скопирована.";$("linkStatus").className="status success";}catch{$("linkStatus").textContent="Не удалось скопировать ссылку.";$("linkStatus").className="status error";}});
              const directOpen=document.createElement("a"); directOpen.className="button secondary split-last"; directOpen.href=file.url; directOpen.target="_blank"; directOpen.rel="noopener noreferrer"; directOpen.textContent="Открыть";
              directGroup.append(directКопировать,directOpen); actions.append(directGroup);
              if (typeof file.sourceUrl === "string" && /^https?:\/\//i.test(file.sourceUrl)) {
                const mirrorData = await api(`/api/file/mirror-url?link=${encodeURIComponent(file.sourceUrl)}&name=${encodeURIComponent(file.name || "video")}`); const vpsUrl = mirrorData.url;
                const vpsGroup=document.createElement("div"); vpsGroup.className="split-actions";
                const vpsКопировать=document.createElement("button"); vpsКопировать.className="secondary split-first"; vpsКопировать.textContent="Копировать ссылку-зеркало";
                vpsКопировать.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(vpsUrl);$("linkStatus").textContent="Ссылка-зеркало успешно скопирована.";$("linkStatus").className="status success";}catch{$("linkStatus").textContent="Не удалось скопировать ссылку-зеркало.";$("linkStatus").className="status error";}});
                const vpsОткрыть=document.createElement("a"); vpsОткрыть.className="button secondary split-last"; vpsОткрыть.href=vpsUrl; vpsОткрыть.target="_blank"; vpsОткрыть.rel="noopener noreferrer"; vpsОткрыть.textContent="Открыть";
                vpsGroup.append(vpsКопировать,vpsОткрыть); actions.append(vpsGroup);
                if (!/Android/i.test(navigator.userAgent || "")) {
                  const potVps=document.createElement("button"); potVps.className="secondary"; potVps.textContent="▶ PotPlayer · зеркало";
                  potVps.addEventListener("click",()=>{window.location.href=`potplayer://${vpsUrl}`;});
                  actions.append(potVps);
                }
              }
              if (!/Android/i.test(navigator.userAgent || "")) {
                const potDirect=document.createElement("button"); potDirect.className="secondary"; potDirect.textContent="▶ PotPlayer · напрямую";
                potDirect.addEventListener("click",()=>{window.location.href=`potplayer://${file.url}`;});
                actions.append(potDirect);
              }
              wrap.append(title,actions);
            } else {
              const unavailable=document.createElement("div"); unavailable.className="file-meta"; unavailable.textContent=file.note || "Прямая ссылка на этот файл пока недоступна.";
              wrap.append(title,unavailable);
            }
            list.append(wrap);
          }
          $("magnetStatus").textContent += ` — ${result.files.length} file(s) available.`;
          $("linkStatus").textContent="Magnet-ссылка готова. Выберите файл ниже."; $("linkStatus").className="status success";
        }
      }
    } else {
      const data=await api("/api/unlock",{method:"POST",body:JSON.stringify({url:value})});
      // Ошибка/unsupported text never reaches history.
      rememberПоиск(historyValue);
      updateHistory(historyValue,{title:data.filename || "Ссылка для воспроизведения",status:"Готово",ready:true,percent:100});
      $("filename").textContent=data.filename || "Ссылка для воспроизведения";
      $("metadata").textContent=[data.host, data.mimeType, data.filesize ? `${data.filesize} bytes` : ""].filter(Boolean).join(" · ");
      $("copyBtn").dataset.url=data.url; $("openBtn").href=data.url;
      $("copyBtn").textContent="Копировать прямую ссылку"; $("openBtn").textContent="Открыть";
      $("copyBtn").className="secondary split-first"; $("openBtn").className="button secondary split-last";
      $("openBtn").style.marginLeft="0"; $("openBtn").style.borderLeft="0";
      $("resultPanel").classList.remove("hidden");
      let vpsActions=$("resultVpsActions");
      if (!vpsActions) { vpsActions=document.createElement("div"); vpsActions.id="resultVpsActions"; vpsActions.className="actions"; $("resultPanel").querySelector(".actions").after(vpsActions); }
      vpsActions.replaceChildren();
      const parsedInput=(()=>{try{return new URL(value)}catch{return null}})();
      if (parsedInput && ["alldebrid.com","www.alldebrid.com"].includes(parsedInput.hostname.toLowerCase()) && parsedInput.pathname.startsWith("/f/")) {
        const mirrorData=await api(`/api/file/mirror-url?link=${encodeURIComponent(parsedInput.toString())}&name=${encodeURIComponent(data.filename || "video")}`); const vpsUrl=mirrorData.url;
        const group=document.createElement("div"); group.className="split-actions";
        const copyVps=document.createElement("button"); copyVps.className="secondary split-first"; copyVps.textContent="Копировать ссылку-зеркало";
        copyVps.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(vpsUrl);$("linkStatus").textContent="Ссылка-зеркало успешно скопирована.";$("linkStatus").className="status success";}catch{$("linkStatus").textContent="Не удалось скопировать ссылку-зеркало.";$("linkStatus").className="status error";}});
        const openVps=document.createElement("a"); openVps.className="button secondary split-last"; openVps.href=vpsUrl; openVps.target="_blank"; openVps.rel="noopener noreferrer"; openVps.textContent="Открыть";
        group.append(copyVps,openVps); vpsActions.append(group);
        const potVps=document.createElement("button"); potVps.className="secondary"; potVps.textContent="▶ PotPlayer · зеркало"; potVps.addEventListener("click",()=>{window.location.href=`potplayer://${vpsUrl}`}); vpsActions.append(potVps);
      }
      const potDirect=document.createElement("button"); potDirect.className="secondary"; potDirect.textContent="▶ PotPlayer · напрямую"; potDirect.addEventListener("click",()=>{window.location.href=`potplayer://${data.url}`}); vpsActions.append(potDirect);
      $("linkStatus").textContent="Ссылка успешно подготовлена."; $("linkStatus").className="status success";
    }
  } catch(err) {
    $("linkStatus").textContent=err.message; $("linkStatus").className="status error";
  }
  finally {btn.disabled=false;btn.textContent=inputMode==="torrent"?"Загрузить торрент":"Добавить magnet";}
});
$("copyBtn").addEventListener("click", async()=>{try{await navigator.clipboard.writeText($("copyBtn").dataset.url);$("linkStatus").textContent="Ссылка успешно скопирована."; $("linkStatus").className="status success";}catch{$("linkStatus").textContent="Не удалось скопировать ссылку. Нажмите «Открыть ссылку» или скопируйте её из адресной строки."; $("linkStatus").className="status error";}});
let cachedLibrary = [];
function setTab(tab) {
  const library = tab === "library";
  $("linkTab").classList.toggle("hidden", library);
  $("libraryTab").classList.toggle("hidden", !library);
  $("showLinkTab").classList.toggle("secondary", library);
  $("showLibraryTab").classList.toggle("secondary", !library);
  if (library && !cachedLibrary.length) loadLibrary();
}
$("showLinkTab").addEventListener("click", () => setTab("links"));
$("showLibraryTab").addEventListener("click", () => setTab("library"));
$("refreshLibrary").addEventListener("click", loadLibrary);
$("libraryПоиск").addEventListener("input", () => renderLibrary(false));
function formatBytes(value) {
  if (!value) return "";
  const units=["B","KB","MB","GB","TB"]; let n=value, i=0;
  while(n>=1024 && i<units.length-1){n/=1024;i++;}
  return `${n.toFixed(i===0?0:1)} ${units[i]}`;
}
async function loadLibrary() {
  const status=$("libraryStatus"), button=$("refreshLibrary");
  button.disabled=true; status.textContent="Загрузка торрентов и файлов из AllDebrid…"; status.className="status";
  try {
    const data=await api("/api/library");
    cachedLibrary=data.magnets || [];
    try { localStorage.setItem(LIBRARY_SNAPSHOT_KEY, JSON.stringify({ savedAt: Date.now(), magnets: cachedLibrary })); } catch {}
    renderLibrary();
    status.textContent=`Загружено ${data.total} торрент(ов). Копия библиотеки сохранена в браузере.`; status.className="status success";
  } catch(err) { status.textContent=err.message; status.className="status error"; }
  finally { button.disabled=false; }
}
function escapeTitle(value) {
  let s = String(value || "Untitled").replace(/\.[a-z0-9]{2,5}$/i, "").replace(/[._]+/g," ");
  // Strip common release tags/codecs so TMDB receives the actual title.
  s = s.replace(/\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b.*$/i, "");
  s = s.replace(/\b(19|20)\d{2}\b.*$/," ").replace(/[\[\](){}]/g," ").replace(/\s+/g," ").trim();
  return s || String(value || "Untitled").replace(/[._]+/g," ").trim();
}
function renderLibrary(animate=true) {
  const target=$("libraryResults"), query=$("libraryПоиск").value.trim().toLowerCase();
  target.replaceChildren();
  const magnets=cachedLibrary.filter(m => !query || m.name.toLowerCase().includes(query) || m.files.some(f => f.path.toLowerCase().includes(query)));
  if (!magnets.length) {
    const empty=document.createElement("div"); empty.className="panel"; empty.textContent=cachedLibrary.length ? "Совпадений не найдено." : "Сохранённой медиатеки пока нет. Нажмите «Обновить», чтобы загрузить данные из AllDebrid."; target.append(empty); return;
  }
  for (const magnet of magnets) {
    const tile=document.createElement("button"); tile.type="button"; tile.className="library-tile"+(animate?" mosaic-pending":""); if(animate) tile.style.setProperty("--tile-index", String(target.children.length));
    const art=document.createElement("div"); art.className="tile-art";
    const title=document.createElement("div"); title.className="tile-title"; title.textContent=escapeTitle(magnet.name); art.append(title);
    const videoФайл = (magnet.files || []).find(f => /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i.test(f.path || ""));
    const posterQueries = [...new Set([escapeTitle(magnet.name), videoФайл ? escapeTitle(videoФайл.name || videoФайл.path.split("/").pop()) : ""].filter(Boolean))];
    (async()=>{
      let url=null;
      for (const q of posterQueries) { url=await getPoster(q); if(url) break; }
      if(url && art.isConnected && !art.querySelector("img")){
        const img=document.createElement("img"); img.alt="Постер: "+(posterQueries[0]||magnet.name); img.decoding="async";
        img.onerror=()=>{ img.remove(); art.classList.add("poster-missing"); };
        img.src=url;
        try { if (img.decode) await img.decode(); else if (!img.complete) await new Promise(resolve=>{img.onload=resolve;img.onerror=resolve;}); } catch {}
        if (art.isConnected) { art.prepend(img); requestAnimationFrame(()=>{ if(img.isConnected) img.classList.add("poster-loaded"); }); }
      }
    })().catch(()=>{}).finally(()=>{
      if (tile.isConnected) tile.classList.remove("mosaic-pending");
    });
    const total=Number(magnet.size)||0, done=Number(magnet.downloaded)||0;
    if(Number(magnet.statusCode)===4){ const ready=document.createElement("div"); ready.className="tile-progress-label"; ready.textContent="Готово · 100%"; art.append(ready); }
    else if(total>0 && Number(magnet.statusCode)<4){ const pct=Math.max(0,Math.min(100,done/total*100)); const track=document.createElement("div"); track.className="tile-progress-track"; const fill=document.createElement("div"); fill.className="tile-progress-fill"; fill.style.width=pct+"%"; track.append(fill); const label=document.createElement("div"); label.className="tile-progress-label"; label.textContent=Math.floor(pct)+"% · "+(magnet.status||"Обработка"); art.append(track,label); }
    const info=document.createElement("div"); info.className="tile-info";
    const name=document.createElement("strong"); name.textContent=magnet.name;
    const meta=document.createElement("div"); meta.className="file-meta"; meta.textContent=`${magnet.status} · ${magnet.files.length} entries`;
    const size=document.createElement("div"); size.className="file-meta"; size.textContent=formatBytes(magnet.size);
    info.append(name,meta,size);
    const mediaФайл=(magnet.files||[]).filter(f=>f.type!=="folder" && /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts)$/i.test(String(f.path||f.name||""))).sort((a,b)=>(Number(b.size)||0)-(Number(a.size)||0))[0];
    if(mediaФайл){
      const technical=document.createElement("div"); technical.className="file-meta media-technical"; technical.textContent="Определяем качество…"; info.append(technical);
      api("/api/media-metadata",{method:"POST",body:JSON.stringify(mediaФайл.sourceLink ? {link:mediaФайл.sourceLink} : {magnetId:magnet.id,path:mediaФайл.path})}).then(result=>{
        if(!technical.isConnected)return;
        const md=result.metadata||{};
        const resolution=md.width&&md.height?`${md.width}×${md.height}`:"";
        const parts=[resolution,md.bitrateMbps?`${md.bitrateMbps} Мбит/с`:"",md.codec?String(md.codec).toUpperCase():""].filter(Boolean);
        technical.textContent=parts.length?parts.join(" · "):"Характеристики недоступны";
        technical.title=result.cached?"Данные из кэша":"Данные ffprobe";
      }).catch(()=>{if(technical.isConnected)technical.textContent="Характеристики недоступны";});
    }
    tile.append(art,info);
    tile.addEventListener("click",()=>openLibraryModal(magnet)); target.append(tile);
  }
}
async function getPoster(title) {
  const key=title.toLowerCase(); if(posterCache.has(key)) return posterCache.get(key);
  const task=api("/api/poster?q="+encodeURIComponent(title)).then(data=>data.poster||null).catch(()=>null); posterCache.set(key,task); return task;
}
let currentLibraryMagnetName = "";
function webdavMagnetPath(name, innerPath="") {
  const clean = value => String(value || "").split("/").filter(Boolean).join("/");
  const base = "magnets/" + clean(name);
  const inner = clean(innerPath);
  return inner ? base + "/" + inner : base + "/";
}
function openLibraryModal(magnet) {
  currentLibraryMagnetName = String(magnet.name || "");
  $("modalTitle").textContent=magnet.name;
  $("modalMeta").textContent=`${magnet.status} · ID ${magnet.id} · ${formatBytes(magnet.size)} · ${magnet.files.length} file/folder entries`;
  const target=$("modalФайлы"); target.replaceChildren();
  // Blu-ray main featurette shortcut: largest available M2TS stream.
  const mainM2ts=(magnet.files||[])
    .filter(f=>f.type!=="folder" && /\.m2ts$/i.test(String(f.path||f.name||"")) && f.sourceLink)
    .sort((a,b)=>(Number(b.size)||0)-(Number(a.size)||0))[0];
  if(mainM2ts){
    const quickWrap=document.createElement("div");quickWrap.className="file-row";
    const quickPlay=document.createElement("button");quickPlay.type="button";quickPlay.className="button";
    quickPlay.textContent="▶ Запустить основной фильм (M2TS · "+formatBytes(mainM2ts.size)+")";
    quickPlay.title=String(mainM2ts.path||mainM2ts.name||"");
    quickPlay.addEventListener("click",async()=>{
      quickPlay.disabled=true;quickPlay.textContent="Разблокировка M2TS…";
      try{
        const name=String(mainM2ts.name||String(mainM2ts.path||"main.m2ts").split("/").pop()||"main.m2ts");
        const result=await api("/api/file/unlock",{method:"POST",body:JSON.stringify({link:mainM2ts.sourceLink})});
        const mirror=await api("/api/file/mirror-url?link="+encodeURIComponent(mainM2ts.sourceLink)+"&name="+encodeURIComponent(name));
        const actions=document.createElement("div");actions.className="file-link-actions";
        const directGroup=document.createElement("div");directGroup.className="split-actions";
        const directКопировать=document.createElement("button");directКопировать.type="button";directКопировать.className="secondary split-first";directКопировать.textContent="Прямая ссылка";
        directКопировать.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(result.url);directКопировать.textContent="Скопировано";}catch{window.prompt("Скопируйте прямую ссылку:",result.url);}});
        const directOpen=document.createElement("a");directOpen.className="button secondary split-last";directOpen.href=result.url;directOpen.target="_blank";directOpen.rel="noopener noreferrer";directOpen.textContent="Открыть";
        directGroup.append(directКопировать,directOpen);
        const mirrorGroup=document.createElement("div");mirrorGroup.className="split-actions";
        const mirrorКопировать=document.createElement("button");mirrorКопировать.type="button";mirrorКопировать.className="secondary split-first";mirrorКопировать.textContent="Ссылка-зеркало";
        mirrorКопировать.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(mirror.url);mirrorКопировать.textContent="Скопировано";}catch{window.prompt("Скопируйте ссылку-зеркало:",mirror.url);}});
        const mirrorOpen=document.createElement("a");mirrorOpen.className="button secondary split-last";mirrorOpen.href=mirror.url;mirrorOpen.target="_blank";mirrorOpen.rel="noopener noreferrer";mirrorOpen.textContent="Открыть";
        mirrorGroup.append(mirrorКопировать,mirrorOpen);
        actions.append(directGroup,mirrorGroup);quickWrap.append(actions);quickPlay.remove();
      }catch(err){quickPlay.disabled=false;quickPlay.textContent="Повторить запуск M2TS";let note=quickWrap.querySelector(".unlock-error");if(!note){note=document.createElement("div");note.className="unlock-error status error";quickWrap.append(note);}note.textContent=err.message;}
    });
    quickWrap.append(quickPlay);target.append(quickWrap);
  }
  const query=$("libraryПоиск").value.trim().toLowerCase();
  const files=magnet.files.filter(f=>!query||f.path.toLowerCase().includes(query));
  if(!files.length){const empty=document.createElement("p");empty.className="file-meta";empty.textContent="No files returned for this magnet yet.";target.append(empty);}
  const folderState = new Map();
  const rows = [];
  const folderPaths = files.filter(f=>f.type==="folder").map(f=>String(f.path||"").replace(/\/+$/,"")).sort((a,b)=>a.length-b.length);
  for(const file of files){
    const filePath=String(file.path||"");
    const row=document.createElement("div");row.className="file-row mosaic-item";row.style.setProperty("--tile-index", String(target.children.length));
    row.dataset.filePath=filePath;row.dataset.fileType=file.type==="folder"?"folder":"file";
    const parentПапкаs=folderPaths.filter(folder=>folder!==filePath && filePath.startsWith(folder+"/"));
    const depth=parentПапкаs.length;
    row.dataset.depth=String(depth);
    row.dataset.parentПапкаs=JSON.stringify(parentПапкаs);
    const left=document.createElement("div");left.className="file-path";left.style.paddingLeft=`${depth*18}px`;
    if(depth>0){const level=document.createElement("span");level.className="file-depth";level.textContent=`L${depth}`;level.title=`Уровень вложенности: ${depth}`;left.append(level);}
    const icon=document.createElement("span");icon.textContent=file.type==="folder"?"📁 ":(/\\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i.test(filePath)?"🎬 ":"📄 ");
    if(file.type==="folder"){
      const toggle=document.createElement("button");toggle.type="button";toggle.className="folder-toggle secondary";toggle.setAttribute("aria-expanded","false");toggle.textContent="▸";
      toggle.title="Раскрыть папку";
      folderState.set(filePath,false);
      toggle.addEventListener("click",()=>{
        const expanded=!folderState.get(filePath);folderState.set(filePath,expanded);
        toggle.textContent=expanded?"▾":"▸";toggle.setAttribute("aria-expanded",String(expanded));toggle.title=expanded?"Свернуть папку":"Раскрыть папку";
        updateПапкаVisibility();
      });
      left.append(toggle);
    }
    const path=document.createElement("span");path.textContent=filePath;left.append(icon,path);row.append(left);
    rows.push(row);    const right=document.createElement("div");right.style.cssText="display:flex;align-items:center;gap:8px;flex-wrap:wrap";
    if(file.size){const size=document.createElement("span");size.className="file-meta";size.textContent=formatBytes(file.size);right.append(size);}
    if(file.type==="folder" && (/\bBDMV\b/i.test(filePath) || /\bBLURAY\b|\bBLU-RAY\b/i.test(filePath))){
      const webdavPlay=document.createElement("button");webdavPlay.type="button";webdavPlay.className="secondary";webdavPlay.textContent="▶ Blu-ray · подключить как диск";
      webdavPlay.title="Подключает через WebDAV родительскую папку, содержащую BDMV. Требуется поддержка WebDAV в Windows.";
      webdavPlay.addEventListener("click",async()=>{
        webdavPlay.disabled=true;
        try{
          const discRoot=filePath.replace(/\/+$/,"").replace(/(^|\/)BDMV(\/.*)?$/i,"");
          const info=await api("/api/webdav?mount=1&path="+encodeURIComponent(webdavMagnetPath(currentLibraryMagnetName,discRoot)+"/"));
          const u=new URL(info.url);
          const port=(u.port && u.port!=="443") ? "@"+u.port : "";
          const unc="\\\\"+u.hostname+"@SSL"+port+"\\DavWWWRoot"+u.pathname.replace(/\//g,"\\");
          const cmd='net use Z: "'+unc+'" /persistent:no';
          try{await navigator.clipboard.writeText(cmd);}catch{}
          window.prompt("Скопируйте команду в cmd (Win+R → cmd). После подключения откройте Z:\\ в PotPlayer. Отключить диск: net use Z: /delete",cmd);
        }catch(err){alert("Не удалось сформировать WebDAV-ссылку: "+err.message);}
        finally{webdavPlay.disabled=false;}
      });right.append(webdavPlay);
    }
    if(file.sourceLink){
      const unlock=document.createElement("button");unlock.className="secondary";unlock.textContent="Получить ссылки";
      unlock.addEventListener("click",async()=>{
        unlock.disabled=true;unlock.textContent="Получение ссылок…";
        try{
          const result=await api("/api/file/unlock",{method:"POST",body:JSON.stringify({link:file.sourceLink})});
          const actions=document.createElement("div");actions.className="split-actions";
          const makeSplit=(label,url,kind)=>{
            const group=document.createElement("div");group.className="split-button";
            const copy=document.createElement("button");copy.type="button";copy.className="secondary split-first";copy.textContent="Копировать "+label;
            copy.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(url);copy.textContent="Скопировано";setTimeout(()=>{copy.textContent="Копировать "+label;},1600);}catch{window.prompt("Скопируйте ссылку:",url);}});
            const open=document.createElement("a");open.className="button secondary split-last";open.href=url;open.target="_blank";open.rel="noopener noreferrer";open.textContent="Открыть";
            if(kind==="mirror") open.title="Видео передаётся через этот VPS; используется исходящий трафик сервера.";
            group.append(copy,open);return group;
          };
          actions.className="file-link-actions";
          actions.append(makeSplit("прямую ссылку",result.url,"direct"));
          const mirrorData=await api(`/api/file/mirror-url?link=${encodeURIComponent(file.sourceLink)}&name=${encodeURIComponent(file.name || "video")}`); const mirrorUrl=mirrorData.url;
          actions.append(makeSplit("ссылку-зеркало",mirrorUrl,"mirror"));
          const potDirect=document.createElement("button");potDirect.type="button";potDirect.className="secondary";potDirect.textContent="▶ PotPlayer · напрямую";
          potDirect.addEventListener("click",()=>{window.location.href="potplayer://"+result.url;});
          const potMirror=document.createElement("button");potMirror.type="button";potMirror.className="secondary";potMirror.textContent="▶ PotPlayer · зеркало";
          potMirror.addEventListener("click",()=>{window.location.href="potplayer://"+mirrorUrl;});
          actions.append(potDirect,potMirror);
          row.append(actions);unlock.remove();
        }catch(err){unlock.disabled=false;unlock.textContent="Повторить";let note=row.querySelector(".unlock-error");if(!note){note=document.createElement("div");note.className="unlock-error status error";row.append(note);}note.textContent=err.message;}
      });right.append(unlock);
    }
    row.append(right);
  }
  function updateПапкаVisibility(){
    for(const row of rows){
      const parents=JSON.parse(row.dataset.parentПапкаs||"[]");
      const visible=parents.every(folder=>folderState.get(folder)===true);
      row.classList.toggle("folder-collapsed",!visible);
      row.setAttribute("aria-hidden",String(!visible));
    }
  }
  updateПапкаVisibility();
  for(const row of rows) target.append(row);
  $("libraryModal").classList.remove("hidden");document.body.style.overflow="hidden";
}
function closeLibraryModal(){$("libraryModal").classList.add("hidden");document.body.style.overflow="";}
$("openWebdav").addEventListener("click",async()=>{
  const button=$("openWebdav");button.disabled=true;
  try{const path=currentLibraryMagnetName ? webdavMagnetPath(currentLibraryMagnetName) : "magnets/";const info=await api("/api/webdav?path="+encodeURIComponent(path));window.open(info.url,"_blank","noopener,noreferrer");}
  catch(err){alert("Не удалось открыть WebDAV: "+err.message);}
  finally{button.disabled=false;}
});
$("closeLibraryModal").addEventListener("click",closeLibraryModal);
$("libraryModal").addEventListener("click",e=>{if(e.target===$("libraryModal"))closeLibraryModal();});
document.addEventListener("keydown",e=>{if(e.key==="Escape")closeLibraryModal();});
try { const snapshot=JSON.parse(localStorage.getItem(LIBRARY_SNAPSHOT_KEY)||"null"); if(snapshot && Array.isArray(snapshot.magnets)){ cachedLibrary=snapshot.magnets; renderLibrary(); const when=new Date(snapshot.savedAt).toLocaleString(); $("libraryStatus").textContent=`Showing saved library snapshot from ${when}. Press Обновить for current AllDebrid status.`; } } catch {}
renderHistory();
// Обновить active magnet entries in the local history automatically. Poll conservatively.
let historyPollBusy = false;
async function refreshActiveHistory() {
  if (historyPollBusy || document.hidden) return;
  historyPollBusy = true;
  try {
    const active = readHistory().filter(item => item.magnetId && !item.ready && !item.cancelled && !item.failed).slice(0, 12);
    for (const item of active) {
      try {
        const status = await api(`/api/magnets/${item.magnetId}`);
        const size = Number(status.size) || 0;
        const downloaded = Number(status.downloaded) || 0;
        const ready = Boolean(status.ready || Number(status.statusCode) === 4);
        const failed = Number(status.statusCode) >= 5;
        updateHistory(item.value, {
          title: status.filename || item.title || "Загрузка по magnet-ссылке",
          status: ready ? "Готово" : (failed ? (status.status || "Ошибка") : (status.status || "Скачатьing")),
          percent: ready ? 100 : (size > 0 ? Math.floor(Math.min(100, downloaded / size * 100)) : null),
          ready,
          failed,
          lastCheckedAt: Date.now()
        });
      } catch (err) {
        // Keep the last known state on transient network/API errors.
      }
    }
  } finally { historyPollBusy = false; }
}
refreshActiveHistory();
setInterval(refreshActiveHistory, 5000);
document.addEventListener("visibilitychange",()=>{if(!document.hidden)refreshActiveHistory();});

checkSession();
