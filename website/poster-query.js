"use strict";
// Варианты запроса для поиска постера в TMDB по имени раздачи (исходное, очищенное, без дефисов, «·» вместо дефиса), не больше MAX.
const MAX = 5;

function posterQueryVariants(query) {
  const out = [query];
  const clean = query
    .replace(/^(?:\[[^\]]*\]\s*)+/, "")
    .replace(/^[\w.]+-raws?\b[\s-]*/i, "").replace(/^[^\s]*-\s+(?:RAWS?\s+)?/i, "")
    .replace(/\b(?:TrueHD|Atmos|DTS(?:-HD)?|DDP?\d?(?:\.\d)?|AAC|AC3|FLAC|HDR10?\+?|Dolby|Vision|REMUX|10bit|8bit)\b/gi, " ")
    .replace(/\s[-~]\s*\d{1,3}\s*[-~]\s*\d{1,3}\b.*$/, "")
    .replace(/\bS\d{1,2}(?:\s*[-–]\s*S?\d{1,2})?\b.*$/i, "")
    .replace(/\b(?:Season|Seasons|Сезон)\s*\d+.*$/i, "")
    .replace(/\b\d{1,2}(?:\s*[-–]\s*\d{1,2})?\s*(?:-?й\s*)?(?:сезон\p{L}*|season\p{L}*).*$/iu, "")
    .replace(/\s+[-–]\s*$/, "").replace(/\s+/g, " ").trim();
  if (clean.length >= 2 && clean !== query) out.push(clean);
  const nodash = (clean || query).replace(/[-–_:]+/g, " ").replace(/\s+/g, " ").trim();
  if (nodash.length >= 2 && !out.includes(nodash)) out.push(nodash);
  // В TMDB «WALL·E» записан с точкой посередине: дефис между буквами пробуем заменить на «·» и убрать совсем.
  const base = clean || query;
  if (/\p{L}-\p{L}/u.test(base)) for (const v of [base.replace(/(\p{L})-(\p{L})/gu, "$1·$2"), base.replace(/(\p{L})-(\p{L})/gu, "$1$2")]) if (!out.includes(v)) out.push(v);
  return out.slice(0, MAX);
}

module.exports = { posterQueryVariants };
