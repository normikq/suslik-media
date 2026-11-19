'use strict';

// Путь тонмаппинга HDR → SDR (docs/HLS.md §5): libplacebo на видеокарте через Vulkan или запасной cpu (zscale+tonemap).
// При старте: список устройств Vulkan (ffmpeg -v verbose печатает «GPU listing»), выбор не-программного (llvmpipe нельзя),
// самопроверка — секунда синтетического HDR10 через libplacebo на этом устройстве. Не вышло — cpu с причиной в журнале.

const { run, lastLine } = require('./proc');

const MODES = ['auto', 'libplacebo', 'cpu'];
const SOFTWARE_RE = /llvmpipe|lavapipe|swiftshader|softpipe/i;

/** Опции libplacebo: масштаб + тонмаппинг + bt709 за один проход; apply_dolbyvision — RPU Dolby Vision (DV5 без него не цветной). */
function libplaceboFilter(w, h) {
  return `libplacebo=w=${w}:h=${h}:format=yuv420p:colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv` +
    ':tonemapping=bt.2390:apply_dolbyvision=1';
}

/** Глобальные опции ffmpeg для libplacebo: устройство Vulkan по индексу и оно же — для фильтров. */
function vulkanArgs(index) {
  return ['-init_hw_device', `vulkan=vk:${index}`, '-filter_hw_device', 'vk'];
}

/**
 * Устройства из журнала ffmpeg -v verbose:
 *   [Vulkan @ …] GPU listing:
 *   [Vulkan @ …]     0: NVIDIA GeForce RTX 3060 Ti (discrete) (0x2489)
 *   [Vulkan @ …]     1: llvmpipe (LLVM 15.0.7, 256 bits) (software) (0x0)
 */
function parseFfmpegVulkanDevices(text) {
  const out = [];
  let listing = false;
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.replace(/^\[[^\]]*\]\s?/, '');
    if (/GPU listing:/.test(line)) { listing = true; continue; }
    if (!listing) continue;
    const m = /^\s+(\d+): (.+) \((\w+)\) \(0x[0-9a-f]+\)\s*$/i.exec(line);
    if (!m) { if (out.length) listing = false; continue; }
    out.push({ index: Number(m[1]), name: m[2], type: m[3].toLowerCase() });
  }
  return out;
}

/** То же из `vulkaninfo --summary` (GPU0: … deviceType = PHYSICAL_DEVICE_TYPE_DISCRETE_GPU … deviceName = …). */
function parseVulkaninfoSummary(text) {
  const out = [];
  let cur = null;
  for (const line of String(text || '').split(/\r?\n/)) {
    const g = /^GPU(\d+):\s*$/.exec(line.trim());
    if (g) { cur = { index: Number(g[1]), name: '', type: 'unknown' }; out.push(cur); continue; }
    if (!cur) continue;
    const t = /deviceType\s*=\s*PHYSICAL_DEVICE_TYPE_(\w+)/.exec(line);
    if (t) cur.type = t[1].toLowerCase().replace(/_gpu$/, '').replace(/^cpu$/, 'software');
    const n = /deviceName\s*=\s*(.+?)\s*$/.exec(line);
    if (n) cur.name = n[1];
  }
  return out.filter((d) => d.name);
}

function isSoftwareDevice(d) { return d.type === 'software' || d.type === 'cpu' || SOFTWARE_RE.test(d.name); }

/**
 * Выбор устройства: override (SM_VULKAN_DEVICE) — индекс или подстрока имени; иначе дискретная, потом встроенная, потом любая
 * не-программная. Программные (llvmpipe и т. п.) не берём никогда.
 * @returns {{device:object|null, reason:string}}
 */
function pickVulkanDevice(devices, override = '') {
  const ov = String(override || '').trim();
  if (ov) {
    const d = /^\d+$/.test(ov)
      ? devices.find((x) => x.index === Number(ov))
      : devices.find((x) => x.name.toLowerCase().includes(ov.toLowerCase()));
    if (!d) return { device: null, reason: `SM_VULKAN_DEVICE=${ov}: нет такого устройства Vulkan (${describe(devices)})` };
    if (isSoftwareDevice(d)) return { device: null, reason: `SM_VULKAN_DEVICE=${ov}: ${d.name} — программное устройство на CPU` };
    return { device: d, reason: '' };
  }
  const hw = devices.filter((d) => !isSoftwareDevice(d));
  const d = hw.find((x) => x.type === 'discrete') || hw.find((x) => x.type === 'integrated') || hw[0];
  if (d) return { device: d, reason: '' };
  if (!devices.length) return { device: null, reason: 'устройств Vulkan нет (нужен Vulkan ICD драйвера NVIDIA)' };
  return { device: null, reason: `только программные устройства Vulkan (${describe(devices)})` };
}

function describe(devices) {
  return devices.length ? devices.map((d) => `${d.index}: ${d.name}`).join('; ') : 'список пуст';
}

/** Список устройств Vulkan: через ffmpeg (то, что увидит кодирование), запасной — vulkaninfo --summary. */
async function listVulkanDevices(cfg) {
  const r = await run(cfg.ffmpeg, ['-hide_banner', '-nostdin', '-v', 'verbose', '-init_hw_device', 'vulkan=vk:0',
    '-f', 'lavfi', '-i', 'nullsrc=s=64x64:d=0.04', '-frames:v', '1', '-f', 'null', '-'], { timeoutMs: 60 * 1000, tailBytes: 65536 })
    .catch((e) => ({ code: -1, stderr: e.message }));
  let devices = parseFfmpegVulkanDevices(r.stderr);
  if (devices.length) return { devices, source: 'ffmpeg' };
  const vi = await run(cfg.vulkaninfo || 'vulkaninfo', ['--summary'], { timeoutMs: 30 * 1000, tailBytes: 65536 })
    .catch(() => ({ code: -1, stdout: '' }));
  devices = vi.code === 0 ? parseVulkaninfoSummary(vi.stdout) : [];
  return { devices, source: devices.length ? 'vulkaninfo' : '', error: lastLine(r.stderr) };
}

/** Аргументы самопроверки: 1 с синтетического HDR10 (PQ, bt2020, 10 бит) → libplacebo 1280×720 → yuv420p. */
function selfTestArgs(index) {
  return ['-hide_banner', '-nostdin', '-loglevel', 'error', ...vulkanArgs(index),
    '-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=24:duration=1',
    '-vf', `format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv,${libplaceboFilter(1280, 720)}`,
    '-f', 'null', '-'];
}

async function selfTest(cfg, device) {
  const r = await run(cfg.ffmpeg, selfTestArgs(device.index), { timeoutMs: 2 * 60 * 1000 }).catch((e) => ({ code: -1, stderr: e.message }));
  return r.code === 0 ? { ok: true } : { ok: false, error: lastLine(r.stderr) || `ffmpeg exit ${r.code}` };
}

/**
 * Решение при старте.
 * @param opts.filters  вывод `ffmpeg -filters` (если уже есть)
 * @returns {Promise<{mode:'libplacebo'|'cpu', device:{index,name,type}|null, reason:string}>}
 * SM_TONEMAP=libplacebo и libplacebo не работает — исключение (воркер не стартует, причина в журнале).
 */
async function detectTonemap(cfg, { filters } = {}) {
  const want = cfg.tonemapMode || 'auto';
  if (want === 'cpu') return { mode: 'cpu', device: null, reason: 'SM_TONEMAP=cpu' };
  const fail = (reason) => {
    if (want === 'libplacebo') throw new Error(`SM_TONEMAP=libplacebo: ${reason}`);
    return { mode: 'cpu', device: null, reason };
  };
  if (filters === undefined) filters = (await run(cfg.ffmpeg, ['-hide_banner', '-filters']).catch(() => ({ stdout: '' }))).stdout;
  if (!/\blibplacebo\b/.test(filters)) return fail('в ffmpeg нет фильтра libplacebo');
  const { devices, error } = await listVulkanDevices(cfg);
  const { device, reason } = pickVulkanDevice(devices, cfg.vulkanDevice);
  if (!device) return fail(error && !devices.length ? `${reason}; ffmpeg: ${error}` : reason);
  const st = await selfTest(cfg, device);
  if (!st.ok) return fail(`самопроверка libplacebo на «${device.name}» не прошла: ${st.error}`);
  return { mode: 'libplacebo', device, reason: '' };
}

function describeTonemap(tm) {
  if (!tm) return 'cpu (не определён)';
  return tm.mode === 'libplacebo' ? `libplacebo (${tm.device.name}, vulkan:${tm.device.index})` : `cpu (${tm.reason})`;
}

/**
 * Dolby Vision без базы HDR10/HLG (профиль 5, совместимость 0, или в VUI нет PQ/HLG) — цвет восстанавливается только по RPU,
 * zscale даёт зелёно-фиолетовую картинку. Такому — только libplacebo.
 */
function doviNeedsRpu(dovi, colorTransfer) {
  if (!dovi) return false;
  if (dovi.profile === 5 || dovi.compat === 0) return true;
  return !['smpte2084', 'arib-std-b67'].includes(colorTransfer || '');
}

/** Путь для задания: null (SDR), 'libplacebo' или 'cpu'; DV без базы HDR10 и без libplacebo — ошибка без повтора. */
function tonemapPathFor(cfg, plan) {
  if (!plan.tonemap) return null;
  const mode = cfg.tonemapPath?.mode === 'libplacebo' ? 'libplacebo' : 'cpu';
  if (mode === 'cpu' && plan.dovi?.needsRpu) {
    const p = plan.dovi.profile ? `профиль ${plan.dovi.profile}` : 'без базы HDR10';
    throw Object.assign(new Error(`Dolby Vision ${p}: нужен libplacebo (Vulkan на видеокарте), а тонмаппинг — ${describeTonemap(cfg.tonemapPath)}`),
      { retryable: false });
  }
  return mode;
}

module.exports = {
  MODES, libplaceboFilter, vulkanArgs, parseFfmpegVulkanDevices, parseVulkaninfoSummary, isSoftwareDevice, pickVulkanDevice,
  listVulkanDevices, selfTestArgs, selfTest, detectTonemap, describeTonemap, doviNeedsRpu, tonemapPathFor,
};
