'use strict';

// Тонмаппинг HDR → SDR: выбор Vulkan-устройства, libplacebo/CPU пути, Dolby Vision и настройки.

const test = require('node:test');
const assert = require('node:assert/strict');

const {
  parseFfmpegVulkanDevices,
  parseVulkaninfoSummary,
  isSoftwareDevice,
  pickVulkanDevice,
  libplaceboFilter,
  vulkanArgs,
  selfTestArgs,
  doviNeedsRpu,
  tonemapPathFor,
  describeTonemap,
} = require('../src/tonemap');
const { buildPlan, buildFfmpegArgs } = require('../src/plan');
const { loadConfig } = require('../src/config');

function hdrProbe({ width = 3840, height = 2160, transfer = 'smpte2084', dovi = false } = {}) {
  return {
    duration_ms: 10000,
    format: { format_name: 'matroska,webm', bit_rate: 40e6 },
    streams: [
      {
        index: 0,
        codec_type: 'video',
        codec_name: 'hevc',
        profile: 'Main 10',
        pix_fmt: 'yuv420p10le',
        width,
        height,
        color_transfer: transfer,
        avg_frame_rate: '24000/1001',
        bit_rate: null,
        keyframe_interval_max_s: null,
        dovi,
      },
      {
        index: 1,
        codec_type: 'audio',
        codec_name: 'truehd',
        channels: 8,
        language: 'eng',
        title: '',
        default: true,
      },
    ],
  };
}

function rawDv(profile, compat) {
  return {
    streams: [
      {
        index: 0,
        side_data_list: [
          {
            side_data_type: 'DOVI configuration record',
            dv_profile: profile,
            dv_bl_signal_compatibility_id: compat,
          },
        ],
      },
    ],
  };
}

const cfg = {
  encoder: 'nvenc',
  hwdec: true,
  nvencPreset: 'p5',
  swPreset: 'medium',
  cq: 24,
  reconnectDelayMax: 30,
};
const gpu = {
  ...cfg,
  tonemapPath: {
    mode: 'libplacebo',
    device: { index: 1, name: 'NVIDIA GeForce RTX 3060 Ti', type: 'discrete' },
  },
};
const cpuCfg = {
  ...cfg,
  tonemapPath: { mode: 'cpu', device: null, reason: 'тест' },
};

function graphOf(a) {
  return a[a.indexOf('-filter_complex') + 1];
}

test('устройства Vulkan из журнала ffmpeg: llvmpipe отбрасывается', () => {
  const log = [
    '[Vulkan @ 0x55d0] Supported layers:',
    '[Vulkan @ 0x55d0] \tVK_LAYER_MESA_device_select',
    '[Vulkan @ 0x55d0] GPU listing:',
    '[Vulkan @ 0x55d0]     0: llvmpipe (LLVM 15.0.7, 256 bits) (software) (0x0)',
    '[Vulkan @ 0x55d0]     1: NVIDIA GeForce RTX 3060 Ti (discrete) (0x2489)',
    '[Vulkan @ 0x55d0] Device 0 selected: llvmpipe (LLVM 15.0.7, 256 bits) (software) (0x0)',
  ].join('\n');

  const list = parseFfmpegVulkanDevices(log);
  assert.deepEqual(list, [
    { index: 0, name: 'llvmpipe (LLVM 15.0.7, 256 bits)', type: 'software' },
    { index: 1, name: 'NVIDIA GeForce RTX 3060 Ti', type: 'discrete' },
  ]);
  assert.equal(pickVulkanDevice(list).device.index, 1);
  assert.equal(isSoftwareDevice({ index: 0, name: 'llvmpipe (LLVM 15.0.7)', type: 'unknown' }), true);
  assert.equal(isSoftwareDevice(list[1]), false);
});

test('vulkaninfo --summary', () => {
  const summary = [
    'Devices:',
    '========',
    'GPU0:',
    '\tapiVersion         = 1.3.242',
    '\tvendorID           = 0x10de',
    '\tdeviceType         = PHYSICAL_DEVICE_TYPE_DISCRETE_GPU',
    '\tdeviceName         = NVIDIA GeForce RTX 3060 Ti',
    'GPU1:',
    '\tapiVersion         = 1.3.238',
    '\tvendorID           = 0x10005',
    '\tdeviceType         = PHYSICAL_DEVICE_TYPE_CPU',
    '\tdeviceName         = llvmpipe (LLVM 15.0.7, 256 bits)',
  ].join('\n');

  const list = parseVulkaninfoSummary(summary);
  assert.deepEqual(list, [
    { index: 0, name: 'NVIDIA GeForce RTX 3060 Ti', type: 'discrete' },
    { index: 1, name: 'llvmpipe (LLVM 15.0.7, 256 bits)', type: 'software' },
  ]);
  assert.equal(pickVulkanDevice(list).device.index, 0);
});

test('SM_VULKAN_DEVICE: индекс, имя, программное — нет', () => {
  const devices = [
    { index: 0, name: 'llvmpipe (LLVM 15.0.7, 256 bits)', type: 'software' },
    { index: 1, name: 'NVIDIA GeForce RTX 3060 Ti', type: 'discrete' },
    { index: 2, name: 'Intel(R) UHD Graphics', type: 'integrated' },
  ];

  assert.equal(pickVulkanDevice(devices, '1').device.index, 1);
  assert.equal(pickVulkanDevice(devices, 'rtx').device.index, 1);

  const swByIdx = pickVulkanDevice(devices, '0');
  assert.equal(swByIdx.device, null);
  assert.match(swByIdx.reason, /программн/);

  const swByName = pickVulkanDevice(devices, 'llvmpipe');
  assert.equal(swByName.device, null);
  assert.match(swByName.reason, /программн/);

  const missing = pickVulkanDevice(devices, 'radeon');
  assert.equal(missing.device, null);
  assert.match(missing.reason, /нет такого/);

  const onlySw = pickVulkanDevice([devices[0]]);
  assert.equal(onlySw.device, null);
  assert.match(onlySw.reason, /программн/);

  const empty = pickVulkanDevice([]);
  assert.equal(empty.device, null);
  assert.match(empty.reason, /нет/);
});

test('cpu: сначала уменьшение до 1080p (10 бит), потом zscale+tonemap; 720p — scale от результата', () => {
  const plan = buildPlan(hdrProbe(), 'v1');
  const a = buildFfmpegArgs(cpuCfg, plan, '/src.mkv', '/w/v1');
  const g = graphOf(a);

  const iScale = g.indexOf('scale=1920:1080');
  const iFmt = g.indexOf('format=yuv420p10le');
  const iZscale = g.indexOf('zscale=t=linear');
  assert.ok(iScale >= 0 && iFmt >= 0 && iZscale >= 0);
  assert.ok(iScale < iFmt && iFmt < iZscale);

  assert.ok(g.includes('tonemap=tonemap=hable:desat=0'));
  assert.ok(g.includes('zscale=t=bt709:m=bt709:r=tv'));
  assert.ok(g.includes('split=2'));
  assert.ok(g.includes('[s1]scale=1280:720,setsar=1[v1]'));
  assert.ok(!g.includes('libplacebo'));

  assert.ok(!a.includes('-init_hw_device'));
  const hwIdx = a.indexOf('-hwaccel');
  assert.ok(hwIdx >= 0);
  assert.equal(a[hwIdx + 1], 'cuda');
  assert.equal(a.filter((x) => x === '-color_trc').length, 2);

  assert.deepEqual(buildFfmpegArgs(cfg, plan, '/src.mkv', '/w/v1'), a);
});

test('libplacebo: Vulkan-устройство, масштаб + bt709 за один проход', () => {
  const plan = buildPlan(hdrProbe(), 'v1');
  const a = buildFfmpegArgs(gpu, plan, '/src.mkv', '/w/v1');
  const i = a.indexOf('-init_hw_device');
  assert.deepEqual(a.slice(i, i + 4), ['-init_hw_device', 'vulkan=vk:1', '-filter_hw_device', 'vk']);
  assert.ok(i < a.indexOf('-i'));

  const g = graphOf(a);
  assert.match(g, /libplacebo=w=1920:h=1080:/);
  for (const part of [
    'format=yuv420p',
    'colorspace=bt709',
    'color_primaries=bt709',
    'color_trc=bt709',
    'range=tv',
    'tonemapping=bt.2390',
    'apply_dolbyvision=1',
  ]) {
    assert.ok(g.includes(part), `ожидалось в графе: ${part}`);
  }
  assert.ok(!g.includes('zscale'));
  assert.ok(!g.includes('tonemap=tonemap'));
  assert.ok(g.includes('[s1]scale=1280:720'));
  assert.equal((g.match(/libplacebo=/g) || []).length, 1);
  assert.ok(a.includes('-hwaccel'));
});

test('SDR — без тонмаппинга и без Vulkan даже при libplacebo', () => {
  const plan = buildPlan(hdrProbe({ transfer: 'bt709', width: 1920, height: 1080 }), 'v1');
  assert.equal(plan.tonemap, false);

  const a = buildFfmpegArgs(gpu, plan, '/src.mkv', '/w/v1');
  const g = graphOf(a);
  assert.ok(!g.includes('libplacebo'));
  assert.ok(!g.includes('zscale'));
  assert.ok(!a.includes('-init_hw_device'));
});

test('Dolby Vision 5 без libplacebo → ошибка без повтора; 8.1 и 7 — можно cpu', () => {
  const dv5 = buildPlan(hdrProbe({ dovi: true, transfer: '' }), 'v1', rawDv(5, 0));
  assert.equal(dv5.tonemap, true);
  assert.deepEqual(dv5.dovi, { profile: 5, compat: 0, needsRpu: true });

  assert.throws(
    () => tonemapPathFor(cpuCfg, dv5),
    (e) => e.retryable === false && /Dolby Vision/.test(e.message) && /libplacebo/.test(e.message)
  );
  assert.throws(
    () => tonemapPathFor(cfg, dv5),
    (e) => e.retryable === false && /Dolby Vision/.test(e.message) && /libplacebo/.test(e.message)
  );
  assert.throws(() => buildFfmpegArgs(cpuCfg, dv5, '/src.mkv', '/w/v1'), /Dolby Vision/);

  assert.equal(tonemapPathFor(gpu, dv5), 'libplacebo');
  assert.ok(graphOf(buildFfmpegArgs(gpu, dv5, '/src.mkv', '/w/v1')).includes('apply_dolbyvision=1'));

  const dv81 = buildPlan(hdrProbe({ dovi: true }), 'v1', rawDv(8, 1));
  assert.deepEqual(dv81.dovi, { profile: 8, compat: 1, needsRpu: false });
  assert.equal(tonemapPathFor(cpuCfg, dv81), 'cpu');

  const dv7 = buildPlan(hdrProbe({ dovi: true }), 'v1', rawDv(7, 6));
  assert.equal(dv7.dovi.needsRpu, false);

  const dvNoRecord = buildPlan(hdrProbe({ dovi: true, transfer: '' }), 'v1', {});
  assert.deepEqual(dvNoRecord.dovi, { profile: null, compat: null, needsRpu: true });

  assert.equal(doviNeedsRpu(null, 'smpte2084'), false);
  assert.equal(doviNeedsRpu({ profile: 8, compat: 4 }, 'arib-std-b67'), false);
});

test('SDR: tonemapPathFor → null', () => {
  assert.equal(tonemapPathFor(cpuCfg, { tonemap: false }), null);
  assert.equal(tonemapPathFor(gpu, { tonemap: false }), null);
});

test('настройки SM_TONEMAP, SM_VULKAN_DEVICE', () => {
  const def = loadConfig({});
  assert.equal(def.tonemapMode, 'auto');
  assert.equal(def.vulkanDevice, '');
  assert.equal(def.tonemapPath, null);

  assert.equal(loadConfig({ SM_TONEMAP: 'CPU' }).tonemapMode, 'cpu');
  assert.equal(loadConfig({ SM_VULKAN_DEVICE: 'NVIDIA' }).vulkanDevice, 'NVIDIA');
  assert.throws(() => loadConfig({ SM_TONEMAP: 'gpu' }), /SM_TONEMAP/);
});

test('самопроверка и описание', () => {
  const a = selfTestArgs(2);
  assert.ok(a.includes('vulkan=vk:2'));
  const vf = a[a.indexOf('-vf') + 1];
  assert.ok(vf.includes('libplacebo='));
  assert.ok(vf.includes('smpte2084'));
  assert.ok(vf.includes('bt2020'));

  assert.match(describeTonemap({ mode: 'libplacebo', device: { index: 1, name: 'NVIDIA X' } }), /^libplacebo \(NVIDIA X/);
  assert.equal(describeTonemap({ mode: 'cpu', device: null, reason: 'нет фильтра' }), 'cpu (нет фильтра)');
  assert.ok(libplaceboFilter(1280, 720).startsWith('libplacebo=w=1280:h=720:'));
});
