"use strict";
// Определение типа раздачи: "series" | "movie" | "other" (игры, софт, музыка, книги, 18+) | "unknown".
// 1) если индексатор отдал категории Torznab/Newznab (Prowlarr, Jackett) — доверяем им;
// 2) иначе эвристика по названию (русские и английские релиз-теги).
const SERIES = [
  /\bS\d{1,2}E\d{1,3}\b/i, /\bS\d{2}\b/i, /\bSeason\s*\d/i, /\bSeasons?\b/i, /\bComplete\s+Series\b/i, /\bEpisodes?\b/i,
  /сезон/i, /сериал/i, /(?:^|[^а-яa-z0-9])сери[яи](?:[^а-яa-z0-9]|$)/i, /(?:^|[^а-яa-z0-9])эпизод/i, /\[TV(?:-\d+)?\]/i, /\b\d+\s*(?:из|of)\s*\d+\b/i
];
const VIDEO = /\b(BDRip|BD-?Rip|BDRemux|BluRay|Blu-?ray|WEB-?DL(?:Rip)?|WEB-?Rip|HDRip|DVDRip|DVD[59]|HDTV(?:Rip)?|TVRip|SATRip|REMUX|CAMRip|HDCAM|HDTS|DVDScr|1080[pi]|720p|2160p|480p|4K|UHD|HDR10?|x26[45]|HEVC|AVC|H\.?26[45]|Dolby\s*Vision|IMAX)\b/i;
const NON_VIDEO = new RegExp([
  "\\bPC\\b", "\\bPS[2-5]\\b", "\\bPSP\\b", "\\bXbox\\b", "\\bNSW\\b", "\\bNintendo\\b", "\\bFitGirl\\b", "\\bxatab\\b", "\\bR\\.G\\.", "\\bRePack\\b",
  "\\b(?:FLAC|MP3|APE|WavPack|Lossless|Discography|OST)\\b", "Дискографи", "Саундтрек", "Аудиокниг", "\\bAudiobook",
  "\\b(?:PDF|FB2|EPUB|DJVU|MOBI|CBR|CBZ)\\b", "\\b(?:Portable|Crack|Keygen|Activator|Adobe|Microsoft\\s+Office)\\b",
  "\\bWindows\\s?\\d+", "\\b(?:Linux|Android|macOS)\\b", "\\[(?:P|L|ISO)\\]", "\\bISO\\b"
].join("|"), "i");

function classify(title, cats) {
  if (Array.isArray(cats) && cats.length) {
    if (cats.some(c => c >= 6000 && c < 7000)) return "other"; // 18+
    if (cats.some(c => c >= 5000 && c < 6000)) return "series"; // TV (в т.ч. аниме 5070)
    if (cats.some(c => c >= 2000 && c < 3000)) return "movie";
    if (cats.some(c => (c >= 1000 && c < 2000) || (c >= 3000 && c < 5000) || (c >= 6000 && c < 9000))) return "other"; // консоли, аудио, PC, 18+, книги
  }
  const t = String(title || "");
  if (SERIES.some(r => r.test(t))) return "series";
  if (VIDEO.test(t)) return "movie";
  if (NON_VIDEO.test(t)) return "other";
  return "unknown";
}
module.exports = { classify };
