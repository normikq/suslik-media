'use strict';

// Языки дорожек: ISO 639-2 из контейнера («rus») → двухбуквенный код для HLS («ru») и имя дорожки.

const ISO2 = {
  rus: 'ru', eng: 'en', ukr: 'uk', bel: 'be', kaz: 'kk', jpn: 'ja', kor: 'ko', chi: 'zh', zho: 'zh',
  fre: 'fr', fra: 'fr', ger: 'de', deu: 'de', spa: 'es', ita: 'it', por: 'pt', pol: 'pl', cze: 'cs', ces: 'cs',
  tur: 'tr', ara: 'ar', heb: 'he', hin: 'hi', tha: 'th', vie: 'vi', ind: 'id', dut: 'nl', nld: 'nl', swe: 'sv',
  nor: 'no', dan: 'da', fin: 'fi', hun: 'hu', rum: 'ro', ron: 'ro', bul: 'bg', gre: 'el', ell: 'el', srp: 'sr',
  hrv: 'hr', slv: 'sl', slo: 'sk', slk: 'sk', lit: 'lt', lav: 'lv', est: 'et', geo: 'ka', kat: 'ka', arm: 'hy',
  hye: 'hy', aze: 'az', uzb: 'uz', per: 'fa', fas: 'fa', lat: 'la',
};

// Имена для звука; для субтитров у русского — «Русские»
const NAMES = {
  ru: 'Русский', en: 'English', uk: 'Українська', be: 'Беларуская', kk: 'Қазақша', ja: '日本語', ko: '한국어',
  zh: '中文', fr: 'Français', de: 'Deutsch', es: 'Español', it: 'Italiano', pt: 'Português', pl: 'Polski',
  cs: 'Čeština', tr: 'Türkçe',
};
const SUB_NAMES = { ru: 'Русские' };

function lang2(code) {
  const c = String(code || '').trim().toLowerCase();
  if (!c || c === 'und' || c === 'unk' || c === 'mis' || c === 'zxx') return '';
  if (c.length === 2) return c;
  if (ISO2[c]) return ISO2[c];
  // en-US и подобное
  const m = /^([a-z]{2})[-_]/.exec(c);
  return m ? m[1] : '';
}

/** Имя дорожки в плеере: «Русский (Дубляж)», «English», «Русские (полные)». */
function trackName(language, title, kind, n) {
  const l = lang2(language);
  const base = (kind === 'subtitle' ? SUB_NAMES[l] : undefined) || NAMES[l] || (l ? l.toUpperCase() : '');
  let t = String(title || '').trim();
  if (t && base && t.toLowerCase() === base.toLowerCase()) t = '';
  if (!base) return t || (kind === 'subtitle' ? `Субтитры ${n + 1}` : `Дорожка ${n + 1}`);
  if (!t) return base;
  if (kind === 'subtitle' && l === 'ru') t = t[0].toLowerCase() + t.slice(1);
  return `${base} (${t})`;
}

module.exports = { lang2, trackName };
