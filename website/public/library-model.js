/* Модель медиатеки сайта: серии и сезоны раздачи, плейлист фильма или сериала, одна плитка на несколько версий.
 * Код скопирован из index.html один в один (parseEpisode, buildPlaylistByFiles, numberedEpisodesOf,
 * groupLibraryVersions и соседи; Kotlin — android/core/.../core/Model.kt). Меняешь правило — меняй и там, и там:
 * index.html пока на этот модуль не переведён. Страница фильма (watch.js) берёт его отсюда.
 * Браузер: window.SuslikLibrary; Node: module.exports (тесты website/test/library-model.test.js). ES2018, без DOM. */
(function (root, factory) {
  "use strict";
  var mod = factory();
  if (typeof module === "object" && module && module.exports) module.exports = mod;
  else root.SuslikLibrary = mod;
})(typeof window !== "undefined" ? window : globalThis, function () {
"use strict";

// Сохранённые ответы о постерах (localStorage "posterCacheV2" в браузере) — их даёт страница: setPosterDb(db)
let posterStore={};
function posterDb(){ return posterStore||{}; }
function setPosterDb(db){ posterStore=db&&typeof db==="object"?db:{}; }
const LATEST_YEAR=new Date().getFullYear()+2;
// Сезон по папке (как seasonOf внутри buildPlaylistByFiles)
function seasonOf(path){
  const p=String(path||"").replace(/\\/g,"/");
  const m=p.match(/(?:^|\/)(?:season|сезон)[ ._-]?(\d{1,2})(?:\/|$)/i)||p.match(/(?:^|\/)S(\d{1,2})(?:\/|$)/i);
  return m?Number(m[1]):null;
}
// Метки сервера по самому файлу (/api/tags: { "<id раздачи>": [...] }) — важнее меток по названию
function setServerTags(map){ for(const k of Object.keys(serverTagMap)) delete serverTagMap[k]; if(map&&typeof map==="object") Object.assign(serverTagMap,map); }

function formatBytes(value) {
  if (!value) return "";
  const units=["B","KB","MB","GB","TB"]; let n=value, i=0;
  while(n>=1024 && i<units.length-1){n/=1024;i++;}
  return `${n.toFixed(i===0?0:1)} ${units[i]}`;
}
function escapeTitle(value) {
  let s = String(value || "Untitled").replace(/\.[a-z0-9]{2,5}$/i, "").replace(/[._]+/g," ");
  // Strip common release tags/codecs so TMDB receives the actual title.
  s = s.replace(/\b(2160p|1080p|720p|480p|4k|8k|web[- ]?dl|web[- ]?rip|bluray|blu[- ]?ray|bdrip|bdremux|remux|hdr10?\+?|dv|dovi|hevc|h\.?265|x265|h\.?264|x264|aac|dts|ddp?5?\.?1|proper|repack|extended|limited|internal|amzn|nf|dsnp|hmax|yts|rarbg)\b.*$/i, "");
  s = s.replace(/\b(19|20)\d{2}\b.*$/," ").replace(/[\[\](){}]/g," ").replace(/\s+/g," ").trim();
  return s || String(value || "Untitled").replace(/[._]+/g," ").trim();
}
function pickMainVideo(magnet){
  const vids=(magnet.files||[]).filter(f=>f.type!=="folder" && /\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i.test(f.path||""));
  const real=vids.filter(f=>!/\.iso$/i.test(f.path));
  return (real.length?real:vids).sort((a,b)=>(Number(b.size)||0)-(Number(a.size)||0))[0]||null;
}
const VIDEO_EXT=/\.(mkv|mp4|avi|mov|webm|m4v|m2ts|ts|iso)$/i;
// Один сезон в имени раздачи («Show.S02.1080p», «Сезон: 2», «2-й сезон»); диапазон («S01-S03», «1-3 сезон») — ничего
// (nameSeason в Model.kt). Серии, у которых в файлах сезона нет, берут его, а не 1: иначе 2 сезон в общей плитке — «Сезон 1».
const NAME_SEASON_RANGE=/(?:season|сезон)[a-zа-яё]*[ ._:]*\d{1,2}[ ._]*[-–][ ._]*\d|\d[ ._]*[-–][ ._]*\d{1,2}[ ._]*(?:-?[a-zа-яё]{1,2}[ ._]*)?(?:season|сезон)|(?:^|[^a-zа-яё0-9])s\d{1,2}[ ._]*[-–][ ._]*s?\d/i;
const NAME_SEASON=/(?:^|[^a-zа-яё0-9])s(\d{1,2})(?=e\d|[^a-zа-яё0-9]|$)|(?:season|сезон)[ ._:]*(\d{1,2})(?!\d)|(?:^|\D)(\d{1,2})[ ._]*(?:-?(?:й|ый|ий|st|nd|rd|th))?[ ._]*(?:season|сезон)(?![a-zа-яё0-9])/gi;
function nameSeason(name){
  name=String(name||"");
  if(NAME_SEASON_RANGE.test(name)) return null;
  const nums=new Set([...name.matchAll(NAME_SEASON)].map(m=>Number(m[1]||m[2]||m[3])));
  const n=nums.size===1?[...nums][0]:0;
  return n>0?n:null;
}
function parseEpisode(path,defaultSeason=1){
  const p=String(path||"").replace(/\\/g,"/"), name=p.split("/").pop();
  const seasonMatch=p.match(/(?:season|сезон)[ ._-]?(\d{1,2})/i)||p.match(/(?:^|\/)S(\d{1,2})(?:\/|$)/i);
  const folderSeason=seasonMatch?+seasonMatch[1]:null;
  let m=name.match(/S(\d{1,2})[ ._-]?E(\d{1,3})/i)||p.match(/S(\d{1,2})[ ._-]?E(\d{1,3})/i);
  if(m) return {season:+m[1],episode:+m[2]};
  m=name.match(/(?:^|[^\d])(\d{1,2})x(\d{2,3})(?!\d)/i);
  if(m) return {season:+m[1],episode:+m[2]};
  m=name.match(/(?:^|[^a-z0-9а-я])(?:e|ep|episode|серия)[ ._-]?(\d{1,3})(?!\d)/i);
  if(m) return {season:folderSeason||defaultSeason,episode:+m[1]};
  if(folderSeason!==null){
    m=name.match(/^\s*(\d{1,3})(?=[ ._-])/);
    if(m) return {season:folderSeason,episode:+m[1]};
  }
  return null;
}
// Тип раздачи и сезон, которые владелец принял от ИИ (services/aititle.js; applyAiTitle в Model.kt), поверх разбора
// по файлам: «сериал» — видео по порядку имён серий одного сезона, «сборник» — отдельные фильмы, «фильм» — главный файл;
// сезон — только если сезон в раздаче один (сериал из нескольких сезонов по папкам он не трогает).
// Сборник фильмов (isCollection / collectionKind в Model.kt). Границы слов — по буквам любого алфавита: \b в JS без
// флага u видит только латиницу, и «\bтрилогия\b» не совпадало никогда.
// Сильные слова называют набор фильмов; слабые («сага», «коллекция», «антология») бывают и у сериалов («Сага о Винланде»,
// «Complete Collection»), поэтому считаются, только если файлы — фильмы разных лет.
const COLLECTION_STRONG=/(?<![\p{L}\p{N}])(?:трилоги[яи]|дилоги[яи]|тетралоги[яи]|квадр[ио]логи[яи]|пенталоги[яи]|гексалоги[яи]|франшиз[аеы]|киносаг[аеиы]|trilogy|duology|dilogy|tetralogy|quadrilogy|franchise|\d{1,2}\s*[-–—]?\s*(?:в|in)\s*1|все\s+части|все\s+фильмы|\d{1,2}\s+фильм(?:а|ов))(?![\p{L}\p{N}])/iu;
const COLLECTION_WEAK=/(?<![\p{L}\p{N}])(?:коллекци[яи]|сборник|антологи[яи]|саг[аеиы]|boxset|box[- ]set|collection|anthology|saga)(?![\p{L}\p{N}])/iu;
const COLLECTION_WORDS=new RegExp(COLLECTION_STRONG.source+"|"+COLLECTION_WEAK.source,"giu");
const SERIES_HINT=/(?<![\p{L}\p{N}])(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?|эпизод\p{L}*|s\d{1,2}|ova|ona)(?![\p{L}\p{N}])/iu;
const YEAR_RE=/\b(19\d{2}|20\d{2})\b/;
// У каждого файла в имени год, и годы разные: фильмы франшизы, а не серии
function filmsByYear(files){
  const years=files.map(f=>YEAR_RE.exec(String(f.path||"").split("/").pop()));
  return years.every(Boolean)&&new Set(years.map(y=>y[1])).size>=2;
}
// Имя раздачи говорит, что это набор фильмов, и не говорит про сезон
function namedCollection(m,files){
  const name=(m&&m.name)||"";
  return !SERIES_HINT.test(name)&&(COLLECTION_STRONG.test(name)||(COLLECTION_WEAK.test(name)&&filmsByYear(files)));
}
// Принятый тип от ИИ решает сам («сериал» и «фильм» — не сборник); иначе фильмы с названиями или сильное слово без сезона
function isCollection(p,m){
  const ai=m&&m.aiTitle;
  if(ai&&ai.status==="accepted"&&ai.kind) return ai.kind==="collection";
  if(!p||p.type!=="series") return false;
  if(p.seasons&&p.seasons[0]&&p.seasons[0].items&&p.seasons[0].items[0]&&p.seasons[0].items[0].title!=null) return true;
  const name=(m&&m.name)||"";
  return COLLECTION_STRONG.test(name)&&!SERIES_HINT.test(name);
}
const KIND_RE=[
  [/(?<![\p{L}\p{N}])(?:трилоги[яи]|trilogy)(?![\p{L}\p{N}])/iu,"Кинотрилогия"],
  [/(?<![\p{L}\p{N}])(?:дилоги[яи]|duology|dilogy)(?![\p{L}\p{N}])/iu,"Дилогия"],
  [/(?<![\p{L}\p{N}])(?:квадр[ио]логи[яи]|тетралоги[яи]|quadrilogy|tetralogy)(?![\p{L}\p{N}])/iu,"Кинотетралогия"]
];
const KIND_LATE_RE=[
  [/(?<![\p{L}\p{N}])сборник(?![\p{L}\p{N}])/iu,"Сборник"],
  [/(?<![\p{L}\p{N}])(?:антологи[яи]|anthology)(?![\p{L}\p{N}])/iu,"Антология"],
  [/(?<![\p{L}\p{N}])(?:коллекци[яи]|collection|boxset|box[- ]set)(?![\p{L}\p{N}])/iu,"Коллекция"]
];
// Сначала слова имени («Дилогия» из трёх файлов — всё равно дилогия), потом число фильмов
function collectionTypeLabel(filmCount, name) {
  name = name || "";
  for (const [re, label] of KIND_RE) if (re.test(name)) return label;
  if (filmCount === 2) return "Дилогия";
  if (filmCount === 3) return "Кинотрилогия";
  if (filmCount === 4) return "Кинотетралогия";
  for (const [re, label] of KIND_LATE_RE) if (re.test(name)) return label;
  return "Кинофраншиза";
}
// Плейлист раздачи считается один раз на объект медиатеки (плитка, тип, метки спрашивают его по нескольку раз);
// принятый ответ ИИ меняет его — psSetAi сбрасывает кэш
const playlistCache=new WeakMap();
function buildPlaylist(magnet){
  let pl=playlistCache.get(magnet);
  if(!pl){ pl=applyAiTitle(buildPlaylistByFiles(magnet), magnet); playlistCache.set(magnet,pl); }
  return pl;
}
function applyAiTitle(pl, m){
  const ai=m&&m.aiTitle;
  if(!pl||!ai||ai.status!=="accepted") return pl;
  const EXTRAS=/(?:^|\/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:\/|$)/i;
  const basename=p=>String(p||"").replace(/\\/g,"/").split("/").pop();
  const vids=(m.files||[]).filter(f=>f.type!=="folder"&&VIDEO_EXT.test(f.path||"")&&!/(^|[\/._ -])sample([\/._ -]|$)/i.test(f.path||""));
  const noExtras=vids.filter(f=>!EXTRAS.test(f.path||"")), main=noExtras.length?noExtras:vids;
  const sorted=()=>main.slice().sort((a,b)=>basename(a.path).localeCompare(basename(b.path),undefined,{numeric:true,sensitivity:"base"}));
  let out=pl;
  if(ai.kind==="movie"&&pl.type==="series"){ const f=pickMainVideo(m); if(f) out={type:"movie",file:f}; }
  else if(ai.kind==="series"&&(pl.type==="movie"||isCollection(pl))&&main.length>=2){
    const s=ai.season>0?ai.season:1;
    out={type:"series",seasons:[{season:s,items:sorted().map((file,i)=>({season:s,episode:i+1,file}))}]};
  } else if(ai.kind==="collection"&&(pl.type==="movie"||(pl.type==="series"&&!isCollection(pl)))&&main.length>=2){
    out={type:"series",seasons:[{season:1,items:sorted().map((file,i)=>({season:1,episode:i+1,file,title:escapeTitle(basename(file.path))||("Фильм "+(i+1))}))}]};
  }
  const s=ai.season;
  if(s>0&&out.type==="series"&&!isCollection(out)&&out.seasons.length===1&&out.seasons[0].season!==s)
    out={type:"series",seasons:[{season:s,items:out.seasons[0].items.map(e=>({...e,season:s}))}]};
  return out;
}
function buildPlaylistByFiles(magnet){
  const vids=(magnet.files||[]).filter(f=>f.type!=="folder"&&VIDEO_EXT.test(f.path||"")&&!/(^|[\/._ -])sample([\/._ -]|$)/i.test(f.path||""));
  const seasonOf=(path)=>{
    const p=String(path||"").replace(/\\/g,"/");
    const m=p.match(/(?:^|\/)(?:season|сезон)[ ._-]?(\d{1,2})(?:\/|$)/i)||p.match(/(?:^|\/)S(\d{1,2})(?:\/|$)/i);
    return m?Number(m[1]):null;
  };
  const basename=(path)=>String(path||"").replace(/\\/g,"/").split("/").pop();
  // Prefer the actual directory structure: many releases have descriptive episode
  // titles with no SxxEyy or numeric prefix in the filename.
  const bySeason=new Map();
  for(const file of vids){
    const season=seasonOf(file.path);
    if(season===null) continue;
    if(!bySeason.has(season)) bySeason.set(season,[]);
    bySeason.get(season).push(file);
  }
  if(bySeason.size>=2 || (bySeason.size===1 && vids.length>1)){
    const seasons=[...bySeason.entries()].sort((a,b)=>a[0]-b[0]).map(([season,files])=>({
      season,
      items:(()=>{
        const sorted=files.sort((a,b)=>basename(a.path).localeCompare(basename(b.path),undefined,{numeric:true,sensitivity:"base"}));
        const nums=sorted.map(file=>{const e=parseEpisode(file.path);return e?e.episode:null;});
        const own=nums.every(n=>n!==null)&&new Set(nums).size===nums.length&&nums.every((n,i)=>!i||n>nums[i-1]);
        return sorted.map((file,index)=>({season,episode:own?nums[index]:index+1,file}));
      })()
    }));
    return {type:"series",seasons};
  }
  // Файлы без своей пометки сезона берут сезон из имени раздачи
  const named=nameSeason(magnet.name)||1;
  const eps=[];
  for(const f of vids){const e=parseEpisode(f.path,named);if(e)eps.push({season:e.season,episode:e.episode,file:f});}
  if(eps.length>=2){
    const seasons=new Map();
    for(const e of eps){if(!seasons.has(e.season))seasons.set(e.season,[]);seasons.get(e.season).push(e);}
    const list=[...seasons.entries()].sort((a,b)=>a[0]-b[0]).map(([season,items])=>({season,items:items.sort((a,b)=>a.episode-b.episode||String(a.file.path).localeCompare(String(b.file.path)))}));
    return {type:"series",seasons:list};
  }
  // Серии, названные только номером («Show - 01 [1080p].mkv»), — сериал, а не сборник фильмов.
  const numbered = numberedEpisodesOf(magnet, vids, named);
  if (numbered) return numbered;
  const EXTRAS = /(?:^|\/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:\/|$)/i;
  const BLURAY_DIR = /(?:^|\/)(?:BDMV|STREAM|VIDEO_TS)(?:\/|$)/i;
  if (vids.length >= 2 && !vids.some(f => BLURAY_DIR.test(f.path || ""))) {
    const main = vids.filter(f => !EXTRAS.test(f.path || ""));
    const largest = Math.max(0, ...main.map(f => Number(f.size) || 0));
    if (largest > 0) {
      const films = main.filter(f => (Number(f.size) || 0) >= 300 * 1024 * 1024 && (Number(f.size) || 0) >= largest / 4);
      if (films.length >= 2) {
        const sorted = films.sort((a, b) => basename(a.path).localeCompare(basename(b.path), undefined, { numeric: true, sensitivity: "base" }));
        const items = sorted.map((f, i) => ({ season: 1, episode: i + 1, file: f, title: escapeTitle(basename(f.path)) || ("Фильм " + (i + 1)) }));
        return { type: "series", seasons: [{ season: 1, items }] };
      }
    }
  }
  const mainVideo = pickMainVideo(magnet);
  return {type: mainVideo ? "movie" : "none", file: mainVideo};
}
// ---- одна плитка на несколько версий (groupVersions / numberedEpisodesOf / titleKey в android/core/.../core/Model.kt) ----
const NUM_NOISE=/(?:2160|1080|720|480|576)[pi]|[xh][ .]?26[45]|\d+(?:[.,]\d+)?\s?(?:fps|kbps|mbps|bit|gb|mb)|(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)[1-9]\.[01](?!\d)/gi;
const NUM_IN_NAME=/(?<!\d)\d{1,3}(?!\d)/g;
function numberedEpisodesOf(magnet, vids, season=1){
  const EXTRAS=/(?:^|\/)(?:extras?|bonus|featurettes?|behind[ ._-]?the[ ._-]?scenes|trailers?|доп[^/]*|бонус[^/]*|допы)(?:\/|$)/i;
  const BLURAY_DIR=/(?:^|\/)(?:BDMV|STREAM|VIDEO_TS)(?:\/|$)/i;
  if(vids.length<2||vids.some(f=>BLURAY_DIR.test(f.path||""))) return null;
  const main=vids.filter(f=>!EXTRAS.test(f.path||""));
  if(main.length<2) return null;
  // «Пила: Трилогия», «Коллекция 1996-2018» с фильмами по годам — фильмы, а не серии (их берёт collectionOf)
  if(namedCollection(magnet, main)) return null;
  const basename=(path)=>String(path||"").replace(/\\/g,"/").split("/").pop();
  const parsed=main.map(f=>{
    const base=basename(f.path).replace(/\.[a-z0-9]{2,5}$/i,"").replace(/[._]+/g," ").replace(NUM_NOISE," ");
    return {base,nums:[...base.matchAll(NUM_IN_NAME)]};
  });
  const slots=Math.min(...parsed.map(x=>x.nums.length));
  for(let slot=0;slot<slots;slot++){
    const nums=parsed.map(x=>Number(x.nums[slot][0]));
    if(new Set(nums).size!==nums.length) continue;
    const templates=new Set(parsed.map(x=>{const n=x.nums[slot];return x.base.slice(0,n.index)+"#"+x.base.slice(n.index+n[0].length);}));
    if(templates.size!==1) continue;
    const sorted=[...nums].sort((a,b)=>a-b);
    const consecutive=sorted.every((v,i)=>!i||v-sorted[i-1]===1);
    const sizes=main.map(f=>Number(f.size)||0);
    const similar=Math.max(...sizes)<=Math.min(...sizes)*2;
    if(!SERIES_HINT.test(magnet.name||"")&&!(main.length>=5&&consecutive&&similar)) continue;
    const items=main.map((file,i)=>({season,episode:nums[i],file})).sort((a,b)=>a.episode-b.episode);
    return {type:"series",seasons:[{season,items}]};
  }
  return null;
}
const SEASON_NOISE=/(?<![\p{L}\p{N}])(?:\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:сезон\p{L}*|season\p{L}*)|(?:сезон\p{L}*|season\p{L}*|серии|серия|series|episodes?)\s*\d{1,3}(?:\s*[-–]\s*\d{1,3})?|s\d{1,2}(?:\s*[-–]?\s*[se]\d{1,3})?|complete|полный|полностью|(?:сезон\p{L}*|season\p{L}*|серии|episodes))(?![\p{L}\p{N}])/giu;
// Название плитки, постера и группировки: принятое предложение ИИ (services/aititle.js), иначе по регуляркам
function displayTitle(m){
  const ai=m&&m.aiTitle;
  return ai&&ai.status==="accepted"&&ai.base?ai.base:escapeTitle((m&&m.name)||"");
}
const groupBaseOf=displayTitle;
// Запрос постера по принятому ответу ИИ: оригинальное название с годом — «Dune (2021)» (сервер берёт результат TMDB
// того же года); без ответа — null, ищем по названию плитки
function aiPosterQuery(m){
  const ai=m&&m.aiTitle;
  if(!ai||ai.status!=="accepted"||!ai.original) return null;
  return ai.year?ai.original+" ("+ai.year+")":ai.original;
}
function titleKey(baseTitle){
  const plain=String(baseTitle||"").toLowerCase().replace(/ё/g,"е");
  const key=plain.replace(SEASON_NOISE," ").replace(/[^\p{L}\p{N}]+/gu," ").trim();
  return key||plain.replace(/[^\p{L}\p{N}]+/gu," ").trim();
}
const playableKindCache=new WeakMap();
function playableKind(m){
  let k=playableKindCache.get(m);
  if(k===undefined){ try{ k=buildPlaylist(m).type; }catch{ k="none"; } playableKindCache.set(m,k); }
  return k;
}
// Один фильм или сериал, добавленный несколько раз (другие релизы, озвучки, сезоны): одна плитка. Только готовые;
// фильмы с фильмами (по названию и году), сериалы и сборники с сериалами (по названию без пометок сезона).
function groupLibraryVersions(list){
  const byKey=new Map(), groups=[], shown=[];
  // Один постер = один фильм, как бы ни назывались раздачи (постер берётся из сохранённых ответов сервера, см. fillPosterPaths)
  for(const m of list){
    const base=groupBaseOf(m);
    const kind0=Number(m.statusCode)===4&&base.trim()?playableKind(m):"none";
    if(kind0==="none"){ shown.push(m); continue; }
    const kind=kind0==="movie"?"m":"s";
    // год принятого ответа ИИ важнее года в имени раздачи: «Dune.2021» и «Dune.BDRip» — одна плитка
    const aiYear=m.aiTitle&&m.aiTitle.status==="accepted"&&m.aiTitle.year;
    const year=aiYear?[null,String(aiYear)]:/\b(19\d{2}|20\d{2})\b/.exec(m.name||"");
    const poster=posterPathOf(m);
    const keys=[];
    if(poster) keys.push(kind+"|poster|"+poster);
    keys.push(kind==="m"?"m|"+titleKey(base)+"|"+(year?year[1]:""):"s|"+titleKey(base));
    let group=null;
    for(const k of keys){ group=byKey.get(k)||null; if(group) break; }
    if(!group){ group=[m]; groups.push(group); shown.push(m); for(const k of keys) byKey.set(k,group); }
    else { group.push(m); for(const k of keys) if(!byKey.has(k)) byKey.set(k,group); }
  }
  const versions=new Map();
  for(const g of groups) if(g.length>1) versions.set(g[0].id,g);
  return {shown,versions};
}
// Подпись плитки: принятое название ИИ, иначе русское название TMDB по запросу постера (title_ru сервера), иначе
// название раздачи. У сборника — своё: TMDB находит один его фильм. Как tileTitle в Model.kt.
function tileTitle(m){
  const ai=m&&m.aiTitle;
  if((ai&&ai.status==="accepted"&&ai.base)||isCollection(buildPlaylist(m),m)) return displayTitle(m);
  const db=posterDb();
  for(const q of getPosterQueries(m)){ const h=db[q.toLowerCase()]; if(h&&h.r) return h.r; }
  return displayTitle(m);
}
// Ответ о постере, сохранённый до русских названий (без r): спросить ещё раз
function posterTitleUnknown(m){
  const q=getPosterQueries(m)[0], h=q&&posterDb()[q.toLowerCase()];
  return !!(h&&h.u&&h.r===undefined);
}
function posterPathOf(m){
  const db=posterDb();
  for(const q of getPosterQueries(m)){ const h=db[q.toLowerCase()]; if(h&&typeof h.u==="string"&&h.u.startsWith("/api/poster/img/")) return h.u; }
  return null;
}
// Год выхода из имени: «(2001)» важнее, потом отдельно стоящий год
function extractYear(name){
  const s=String(name||"");
  const b=/[(\[{](19\d{2}|20\d{2})[)\]}]/.exec(s); if(b&&Number(b[1])<=LATEST_YEAR) return Number(b[1]);
  for(const m of s.matchAll(/\b(19\d{2}|20\d{2})\b/g)) if(Number(m[1])<=LATEST_YEAR) return Number(m[1]);
  return null;
}
function releaseYear(m){
  const ai=m&&m.aiTitle; if(ai&&ai.status==="accepted"&&ai.year) return Number(ai.year);
  return extractYear(m.name)||extractYear(displayTitle(m))||(m.files||[]).map(f=>extractYear(f.name||f.path)).find(Boolean)||null;
}
function getPosterQueries(m) {
  const base = displayTitle(m);
  const queries = [...new Set([aiPosterQuery(m), base].filter(Boolean))];
  const main = pickMainVideo(m);
  if (main) {
    const mainName = String(main.name || main.path || "").replace(/\\/g, "/").split("/").pop();
    const mainBase = escapeTitle(mainName);
    if (mainBase && !queries.includes(mainBase)) queries.push(mainBase);
  }
  return queries;
}
// Раздача только из .mka — загруженная внешняя озвучка (/api/custom-audio): она играет внутри своего фильма и отдельной плиткой не показывается (как на ПК, Android и ТВ).
function isVoiceOverOnly(m){ const files=(m.files||[]).filter(f=>f&&f.path&&f.type!=="folder"); return files.length>0&&files.every(f=>/\.mka$/i.test(f.path)); }
// Метки релиза для постера: Dolby Vision, HDR, DTS (по названию раздачи и файлов).
// Одна метка для картинки и одна для звука: Dolby Vision (а если его нет — HDR) и DTS, без вариантов и повторов.
function collapseTags(all) {
  const out = [];
  if (all.some(t => t.startsWith("Dolby"))) out.push("Dolby Vision"); else if (all.includes("HDR")) out.push("HDR");
  if (all.some(t => t.startsWith("DTS"))) out.push("DTS");
  return out;
}
const serverTagMap = {}; // id раздачи -> метки, найденные сервером по самому файлу (ffprobe), см. /api/tags
function getItemTags(m) {
  if (serverTagMap[m.id]) return serverTagMap[m.id];
  const text = (m.name || "") + "\n" + (m.files || []).map(f => f.path || f.name || "").join("\n");
  const out = [];
  if (/\b(dolby[ ._-]?vision|dovi|dv)\b/i.test(text)) out.push("Dolby Vision");
  if (/\bhdr(10)?(\+|plus)?\b/i.test(text)) out.push("HDR");
  const d = text.match(/\bdts(?:[ ._:-]?(hd|x))?(?:[ ._-]?(ma))?\b/i);
  if (d) { const k = (d[1] || "").toLowerCase(); out.push(k === "hd" ? (d[2] ? "DTS-HD MA" : "DTS-HD") : k === "x" ? "DTS:X" : "DTS"); }
  return out;
}


return {
  setPosterDb, setServerTags, posterDb, seasonOf, formatBytes, escapeTitle, pickMainVideo, VIDEO_EXT, nameSeason, parseEpisode,
  filmsByYear, namedCollection, isCollection, collectionTypeLabel, buildPlaylist, applyAiTitle, buildPlaylistByFiles,
  numberedEpisodesOf, displayTitle, aiPosterQuery, titleKey, playableKind, groupLibraryVersions, tileTitle,
  posterPathOf, extractYear, releaseYear, getPosterQueries, isVoiceOverOnly, collapseTags, getItemTags
};
});
