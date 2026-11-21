# Кодировщик HLS (T2)

Воркер на домашнем ПК владельца (RTX 3060 Ti): берёт задания у основного сервера, кодирует фильм в HLS
и выкладывает на CDN. Контракт — `docs/HLS.md` (§4 раскладка, §5 профили, §7 отпечаток, §10 API, §13 конфиг).
Node ≥ 20, без зависимостей (только встроенные модули), ffmpeg ≥ 6.1.

## Как работает

```
GET /internal/hls/next ──204──► пауза SM_IDLE_S
   │ задание {job_id, mirror_url, size, …}
   ├─ size > SM_MAX_SOURCE_GB → failed (retryable=false)
   ├─ ffprobe по mirror_url (+ цвет первого кадра, если в заголовке его нет)
   ├─ keyframe_interval_max_s по первым SM_KEYFRAME_PROBE_S (только если файл подходит под v1-copy по остальным условиям)
   ├─ отпечаток: 1–3 Range-запроса по 1 МиБ к mirror_url (§7)
   ├─ POST probe → alias: готово (сервер сам записал алиас, done не шлётся)
   │              wait:  повтор probe через retry_after_s
   │              encode {hls_id, profile}
   ├─ ffmpeg читает исходник прямо по HTTP (-reconnect…), одна команда на всё:
   │     1080p/ 720p/ (v1 и v1-copy; у v1-copy 1080p/ — копия) — video.m4s + index.m3u8
   │     a0…a3/ — AAC-LC стерео 160 кбит, audio.m4s + index.m3u8
   │     s0…s5/ — subs.vtt (текстовые субтитры) + тривиальный index.m3u8; PGS/VobSub/DVB → skipped
   ├─ проверка плейлистов (EXT-X-MAP, EXT-X-BYTERANGE, смещения без дыр до конца файла), manifest.json
   ├─ rsync → /srv/hls/.tmp/<hls_id>.<profile>.<rand>/ (rrsync), затем ssh «finalize …» (атомарный перенос на CDN)
   └─ POST done {job_id, hls_id, profile, manifest_sha256, bytes}
Пока задание идёт — POST progress раз в SM_PROGRESS_S (продлевает аренду). Ошибка → POST failed {stage, error, retryable}.
```

Стадии в `progress`/`failed`: `probe`, `fingerprint`, `wait`, `encode`, `verify`, `upload`, `done`.
Один фильм за раз (+ pid-файл `work/.lock` от второго экземпляра). Временные файлы — `SM_WORK_DIR`, чистятся при старте и после каждого задания.
Журнал — `SM_LOG_DIR/encoder.log`, ротация по `SM_LOG_MAX_MB`, хранится `SM_LOG_KEEP` старых файлов.
Ошибки цикла не роняют процесс; если процесс всё же упал — его поднимает NSSM/systemd.
На SIGINT/SIGTERM (NSSM — Ctrl+Break) ffmpeg останавливается, серверу уходит `failed` с `retryable=true`.

### Снятие задания на сайте (`docs/HLS.md` §19)

Если владелец остановил задание в админке, сайт отвечает на его `progress`/`probe`/`done`/`audio-done`/`failed`
**410 `{code:"cancelled"}`** (или 404 `{code:"unknown_job"}`) — в `api.js` это `JobCancelled`, а не сетевая ошибка.
У каждого задания свой `AbortController` (`src/cancel.js`): первый же такой ответ (обычно на ближайший `progress`, раз в `SM_PROGRESS_S`)
убивает текущий ffmpeg/ffprobe/rsync/ssh, рабочая папка задания удаляется, `failed` не шлётся,
результат `{result:"cancelled"}`, в журнале `[job] <job_id> отменено на сайте`; цикл без паузы берёт следующее задание.
Остановка самого воркера (SIGTERM) — как раньше, `failed` с `retryable=true`.

### Профили

- **v1** — HEVC (`hevc_nvenc`, `-rc vbr -cq` + `-maxrate`/`-bufsize`): 1080p ≤ 7 Мбит, 720p ≤ 4 Мбит, `-cq 21`; вписывание в 1920×1080 и 1280×720 без увеличения
  (исходник ≤ 1280×720 — только `720p/`). Ключевые кадры — только принудительные каждые 6 с
  (`-force_key_frames expr:gte(t,n_forced*6)`, `-forced-idr 1`, `-no-scenecut 1`, большой `-g`), поэтому сегменты 1080p и 720p совпадают.
  HDR (PQ/HLG/DV) → SDR: `libplacebo` на видеокарте или запасной `zscale` + `tonemap=hable` (см. «Тонмаппинг HDR → SDR»), метки bt709.
  Чересстрочный исходник — `bwdif`. `-tag:v hvc1`.
- **v1-copy** — верхняя дорожка `-c:v copy` (`1080p/`, у исходника ≤ 720 по высоте — `720p/` и больше ничего), звук в AAC. Решает сервер по probe.
  Если исходник выше 720p, в том же проходе ffmpeg добавляется `720p/` (вписывание в 1280×720, тот же кодировщик, `-cq` и ≤ 4 Мбит, как 720p в v1;
  с nvenc и `SM_HWDEC` — `-hwaccel cuda`) с `-force_key_frames source`, `-forced-idr 1` и без своих ключевых кадров (большой `-g`, без scenecut):
  ключевые кадры 720p ровно там, где у исходника, и сегменты 720p режутся в тех же точках, что копия. Проверка после кодирования: число сегментов
  и EXTINF (начала ±1 кадр) у копии и 720p совпадают, иначе `failed` (`verify`) без повтора. В журнале — `[encode] v1-copy nvenc: копия 1080p + 720p (force_key_frames source)`.
- `SM_ENCODER=x265` — то же на процессоре (медленно), `x264` — H.264 (`avc1`), только для проверки без видеокарты.

### Тонмаппинг HDR → SDR

Два пути (`src/tonemap.js`, аргументы — `src/plan.js`):

- **libplacebo** (основной): `-init_hw_device vulkan=vk:<N> -filter_hw_device vk`, один фильтр
  `libplacebo=w=1920:h=1080:format=yuv420p:colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv:tonemapping=bt.2390:apply_dolbyvision=1`
  делает на видеокарте масштаб до верхней ступени, тонмаппинг и перевод в bt709; 720p — `scale` от готового 1080p.
  Декодирование — как и раньше, NVDEC (`-hwaccel cuda`, кадры в память); метаданные Dolby Vision (RPU) при этом доходят до фильтра,
  поэтому `apply_dolbyvision` работает (проверено на DV 7, ffmpeg 9.0.2).
- **cpu** (запасной): сначала `scale` до верхней ступени в 10 битах, затем прежняя цепочка `zscale=t=linear` → `tonemap=hable` → `zscale` bt709
  уже на 1080p, а не на 4K float32. Цвет тот же, что был (PSNR к прежней цепочке ≈ 56 дБ — разница только в масштабировании).

Dolby Vision без базы HDR10/HLG (профиль 5; или DV, у которого в заголовке нет PQ/HLG) восстанавливается только по RPU — через `zscale`
получается зелёно-фиолетовая картинка. Такое задание без libplacebo — `failed` с `retryable=false` и понятной ошибкой.
DV 7 и 8.1 (база HDR10) и 8.4 (HLG) — можно и cpu.

Выбор при старте (`[start] тонмаппинг: libplacebo (NVIDIA GeForce RTX 3060 Ti, vulkan:0)` или `cpu (причина)` в журнале;
путь каждого задания — в строке `[encode] … тонмаппинг libplacebo|cpu|нет`):

1. есть ли в ffmpeg фильтр `libplacebo`;
2. список устройств Vulkan — из `ffmpeg -v verbose -init_hw_device vulkan=vk:0 …` («GPU listing»), запасной — `vulkaninfo --summary`;
   программные (`llvmpipe`, `lavapipe`, `SwiftShader`) не берутся никогда, из остальных — дискретная, потом встроенная;
3. самопроверка: секунда синтетического HDR10 (PQ, bt2020, 10 бит) через `libplacebo` на выбранном устройстве.

| Переменная | По умолчанию | Что |
|---|---|---|
| `SM_TONEMAP` | `auto` | `auto` — libplacebo, если проверки прошли, иначе cpu с предупреждением; `libplacebo` — только он (не прошёл — воркер не стартует); `cpu` — без Vulkan |
| `SM_VULKAN_DEVICE` | пусто | индекс или часть имени устройства Vulkan (`0`, `3060`); пусто — первое не-программное |
| `SM_VULKANINFO` | `vulkaninfo` | путь к `vulkaninfo` (только запасной способ получить список) |

Проверить руками (Ubuntu): `vulkaninfo --summary` (пакет `vulkan-tools`) — в списке должна быть `NVIDIA GeForce …`
(`PHYSICAL_DEVICE_TYPE_DISCRETE_GPU`); `llvmpipe` рядом — это нормально, его воркер пропустит.
`mesa-vulkan-drivers` (это и есть llvmpipe) не нужен; нужен Vulkan ICD драйвера NVIDIA — `/usr/share/vulkan/icd.d/nvidia_icd.json`
(или `/etc/vulkan/icd.d/`), он ставится вместе с драйвером (`libnvidia-gl-<версия>`; в «headless»-наборах драйвера его может не быть).
То же, что делает воркер при старте: `node index.js --tonemap-check` (код выхода 0 — libplacebo, 3 — cpu, 1 — ошибка);
`install.sh` запускает эту проверку от пользователя службы с её ограничениями.

В журнале `cpu (…)` — по причине:
- `в ffmpeg нет фильтра libplacebo` — ffmpeg собран без него (`ffmpeg -filters | grep libplacebo`; у ffmpeg 8.0.1 из apt на кодировщике он есть);
- `устройств Vulkan нет` / `только программные` — нет ICD NVIDIA: `ls /usr/share/vulkan/icd.d/`, `sudo apt install libnvidia-gl-<версия драйвера>`,
  у службы — доступ к `/dev/nvidia*` (`install.sh` добавляет группы);
- `самопроверка … не прошла: …` — ошибка ffmpeg в скобках; попробовать вручную
  `ffmpeg -v verbose -init_hw_device vulkan=vk:0 -filter_hw_device vk -f lavfi -i testsrc2=d=1 -vf "format=yuv420p10le,libplacebo=w=1280:h=720:format=yuv420p" -f null -`;
- нужно другое устройство — `SM_VULKAN_DEVICE`.
Пока путь cpu, всё кодируется как раньше (медленнее), кроме DV профиля 5.

## Задания «звук» — внешние озвучки (`docs/HLS.md` §18)

Задание с `type: "audio"` (без `type` — видео, как выше) добавляет к уже выложенному профилю внешние озвучки (`.mka` из раздачи
или привязанные через `/api/custom-audio`): `{job_id, hls_id, profile, duration_ms, tracks:[{key, dir, mirror_url, offset_ms, name, language}]}`
(`next-job-audio.json`). Без ffprobe, отпечатка и probe; код — `src/audiojob.js`.

```
для каждой дорожки (x0…x7) — своя команда ffmpeg по mirror_url (-reconnect…, как у видео):
   offset > 0 → adelay=<offset>:all=1 (тишина в начале)
   offset < 0 → -ss |offset| до -i (первые |offset| мс дорожки пропускаются)
   apad + -t duration_ms — длина ровно как у видео (короткая дорожка дополняется тишиной, длинная обрезается)
   первая звуковая дорожка файла (-map 0:a:0) → AAC-LC стерео 128 кбит, 48 кГц, HLS fMP4 single_file по 6 с → xN/index.m3u8 + xN/audio.m4s
проверка: EXT-X-MAP, BYTERANGE без дыр до конца audio.m4s, сумма EXTINF = duration_ms ± 1 с
rsync → <hls_id>.<profile>.a<rand>/ (только папки xN/), ssh «finalize-audio <имя>» → ответ «ok <hls_id>/<profile> <байт>»
POST /internal/hls/audio-done {job_id, hls_id, profile, tracks:[{key, dir, codec:"mp4a.40.2", channels:2, bytes}]}
```

Сдвиг — как на ТВ (`docs/CUSTOM-AUDIO-API.md` §8): место в дорожке = место в видео − `offset_ms`; `offset_ms` уже с `shift_ms` ассета — его считает сайт.
`progress` идёт со стадиями `encode` (общая доля по всем дорожкам и `eta_s`), `verify`, `upload`.
Любая дорожка с ошибкой — `failed` всего задания: нет файла (404/410), нет звука, битый файл — `retryable=false`; сеть, подпись, 5xx — `retryable=true`.
Плохое задание (`dir` не `x0…x7`, повтор `dir`, больше 8 дорожек, нет `duration_ms`) — `failed` без повтора; неизвестный `type` — тоже.
`SM_UPLOAD_MODE=local` — каждая `xN/` переносится в `<SM_UPLOAD_LOCAL_DIR>/<hls_id>/<profile>/xN/` с заменой, остальное в профиле не трогается.

## Переменные окружения

| Переменная | По умолчанию | Что |
|---|---|---|
| `SM_SITE_URL` | — | адрес основного сервера, напр. `https://site.example.org` (с путём, если сервер не в корне) |
| `HLS_WORKER_TOKEN` | — | Bearer для `/internal/hls/*` |
| `SM_ENCODER` | `nvenc` | `nvenc` \| `x265` \| `x264` |
| `SM_MAX_SOURCE_GB` | `40` | исходник больше — `failed` без повтора |
| `SM_CDN_SSH` | — | `hls-upload@cdn.example.org` |
| `SM_CDN_SSH_KEY` | — | путь к закрытому ключу (пусто — ssh ищет сам) |
| `SM_CDN_SSH_PORT` | — | порт ssh |
| `SM_CDN_TMP_DIR` | пусто | подпапка для rsync внутри корня rrsync у `hls-upload` (корень на CDN уже `/srv/hls/.tmp/`); обычно не задаётся |
| `SM_CDN_FINALIZE_CMD` | `finalize {name}` (так принимает `infra/cdn/bin/hls-upload-shell`, ответ `ok <hls_id>/<profile> <байт>`) | команда по ssh после rsync; подстановки `{name}` (имя папки в `.tmp`), `{tmp}` (`.tmp/<name>`), `{hls_id}`, `{profile}` |
| `SM_CDN_FINALIZE_AUDIO_CMD` | `finalize-audio {name}` | то же для задания «звук» (папка `<hls_id>.<profile>.a<rand>` только с `xN/`); те же подстановки |
| `SM_UPLOAD_MODE` | `rsync` | `rsync` или `local` (копия в папку — для проверки без CDN) |
| `SM_UPLOAD_LOCAL_DIR` | — | корень «CDN» для `local`: `<dir>/.tmp/<name>/` → `<dir>/<hls_id>/<profile>/` |
| `SM_RSYNC`, `SM_SSH` | `rsync`, `ssh` | пути к программам |
| `SM_FFMPEG`, `SM_FFPROBE` | `ffmpeg`, `ffprobe` | пути к программам |
| `SM_TONEMAP`, `SM_VULKAN_DEVICE` | `auto`, пусто | путь HDR → SDR — раздел «Тонмаппинг HDR → SDR» |
| `SM_HWDEC` | `1` при nvenc | декодирование исходника на видеокарте (`-hwaccel cuda`) |
| `SM_GPU_SCALE` | `1` при nvenc | масштаб на видеокарте: `-hwaccel_output_format cuda`, `scale_cuda` (`bwdif_cuda` для черезстрочного) — кадры не копируются в память и обратно. Только без тонмаппинга и для исходников, которые декодирует NVDEC (H.264 8 бит, HEVC/AV1/VP9 8–10 бит 4:2:0, MPEG-2); иначе и при сбое ffmpeg (повтор сразу) — масштаб на CPU. Нет `scale_cuda` в ffmpeg — выключается при старте. 4K SDR → 1080p+720p в 2,3 раза быстрее (RTX 5070 Ti) |
| `SM_CQ` | `21` (nvenc) / `23` | качество: `-cq` у nvenc, `-crf` у x265/x264 |
| `SM_NVENC_PRESET` | `p5` | пресет nvenc |
| `SM_SW_PRESET` | `medium` | пресет x265/x264 (`ultrafast` для тестов) |
| `SM_WORK_DIR` | `encoder/work` | временные файлы (нужно место на весь фильм в HLS, ~3–5 ГБ) |
| `SM_LOG_DIR` | `encoder/logs` | журнал |
| `SM_LOG_MAX_MB`, `SM_LOG_KEEP` | `10`, `5` | ротация журнала |
| `SM_IDLE_S` | `30` | пауза после `204` |
| `SM_ERROR_S` | `60` | пауза после ошибки связи с сервером |
| `SM_PROGRESS_S` | `2` | период `progress` (аренда и полоса прогресса на сайте) |
| `SM_KEYFRAME_PROBE_S` | `600` | окно замера интервала ключевых кадров |
| `SM_RECONNECT_DELAY_MAX` | `30` | `-reconnect_delay_max` для ffmpeg/ffprobe |
| `SM_ONCE` | `0` | одно задание (или одно `204`) и выход |

Домены, токены и ключи — только в окружении службы, не в репозитории.

## Выгрузка на CDN (согласовать с T1, `infra/cdn/`)

1. `rsync -a --partial --chmod=D755,F644 -e "ssh …" ./ hls-upload@cdn:.tmp/<hls_id>.<profile>.<rand>/` (rsync запускается из папки профиля — без букв дисков в путях).
2. `ssh hls-upload@cdn "finalize <name> <hls_id> <profile>"` — CDN переносит `.tmp/<name>` в `/srv/hls/<hls_id>/<profile>/` атомарно (`mv`; старую версию профиля — заменить).

Если у `hls-upload` в `authorized_keys` стоит `command="rrsync …"`, команду `finalize` должна принимать обёртка
(смотрит `$SSH_ORIGINAL_COMMAND`: `rsync --server …` → rrsync, `finalize …` → перенос). Формат команды меняется через `SM_CDN_FINALIZE_CMD`.

### SSH-ключ для hls-upload

```
ssh-keygen -t ed25519 -f ~/.ssh/hls-upload -N "" -C "suslik-encoder"
# hls-upload.pub → владельцу CDN в /home/hls-upload/.ssh/authorized_keys (с ограничениями из infra/cdn/README.md)
ssh-keyscan -H cdn.example.org >> ~/.ssh/known_hosts      # ssh запускается с BatchMode=yes: без known_hosts не подключится
```

На Windows ключ и `known_hosts` — в профиле пользователя, от имени которого работает служба
(`C:\Users\<user>\.ssh\`); путь к ключу лучше задать явно `SM_CDN_SSH_KEY`.

## Установка — Windows (NSSM)

1. **Драйвер NVIDIA** — свежий Game Ready/Studio: новые сборки ffmpeg требуют новую версию API NVENC, и старый драйвер даёт
   `Driver does not support the required nvenc API version` — тогда обновить драйвер. Проверка: `nvidia-smi`.
2. **ffmpeg ≥ 6.1** с `hevc_nvenc`, `libx265`, `zscale` — сборка gyan.dev «full» или BtbN «gpl». Проверка:
   `ffmpeg -hide_banner -encoders | findstr nvenc`, `ffmpeg -hide_banner -filters | findstr "zscale tonemap"`.
3. **Node.js ≥ 20** (LTS).
4. **rsync и ssh.** ssh есть в Windows (OpenSSH). rsync — cwRsync или MSYS2 (`pacman -S rsync`); путь — в `SM_RSYNC`,
   и `SM_SSH` — на ssh из того же комплекта, если системный с ним не дружит (у cwRsync — свой `ssh.exe`).
5. Проверка на заглушке (без сервера и CDN) — раздел «Проверка» ниже.
6. Служба:

```bat
nssm install SuslikEncoder "C:\Program Files\nodejs\node.exe" "C:\suslik\encoder\index.js"
nssm set SuslikEncoder AppDirectory C:\suslik\encoder
nssm set SuslikEncoder AppEnvironmentExtra SM_SITE_URL=https://site.example.org HLS_WORKER_TOKEN=… SM_ENCODER=nvenc SM_CDN_SSH=hls-upload@cdn.example.org SM_CDN_SSH_KEY=C:\Users\<user>\.ssh\hls-upload SM_RSYNC=C:\cwrsync\bin\rsync.exe SM_SSH=C:\cwrsync\bin\ssh.exe SM_FFMPEG=C:\ffmpeg\bin\ffmpeg.exe SM_FFPROBE=C:\ffmpeg\bin\ffprobe.exe
nssm set SuslikEncoder ObjectName .\<user> <пароль>      &:: от имени пользователя: его ~/.ssh и драйвер видеокарты
nssm set SuslikEncoder AppStopMethodConsole 30000         &:: Ctrl+Break → воркер сообщает серверу failed и выходит
nssm set SuslikEncoder AppExit Default Restart
nssm set SuslikEncoder AppRestartDelay 30000
nssm set SuslikEncoder AppStdout C:\suslik\encoder\logs\service.out.log
nssm set SuslikEncoder AppStderr C:\suslik\encoder\logs\service.err.log
nssm start SuslikEncoder
```

Служба от `LocalSystem` тоже видит NVENC, но ей придётся отдельно положить ключ и `known_hosts`.

## Установка — Linux (systemd)

Быстрее — `sudo ./install.sh` (Ubuntu: пакеты, проверки, пользователь, ssh-ключ, служба; см. `docs/HLS-DEPLOY.md`, раздел 3). Ниже — вручную.

```
# драйвер NVIDIA (Ubuntu): sudo ubuntu-drivers install  → nvidia-smi
# ffmpeg ≥ 6.1 с nvenc: статическая сборка BtbN (ffmpeg-master-latest-linux64-gpl) в /opt/ffmpeg
sudo useradd -r -m -d /var/lib/suslik-encoder suslik-encoder
sudo -u suslik-encoder install -d -m 700 /var/lib/suslik-encoder/.ssh
sudo -u suslik-encoder ssh-keygen -t ed25519 -f /var/lib/suslik-encoder/.ssh/hls-upload -N ""
sudo -u suslik-encoder sh -c 'ssh-keyscan -H cdn.example.org >> ~/.ssh/known_hosts'
```

`/etc/suslik-encoder.env` (права 600):

```
SM_SITE_URL=https://site.example.org
HLS_WORKER_TOKEN=…
SM_ENCODER=nvenc
SM_CDN_SSH=hls-upload@cdn.example.org
SM_CDN_SSH_KEY=/var/lib/suslik-encoder/.ssh/hls-upload
SM_FFMPEG=/opt/ffmpeg/bin/ffmpeg
SM_FFPROBE=/opt/ffmpeg/bin/ffprobe
SM_WORK_DIR=/var/lib/suslik-encoder/work
SM_LOG_DIR=/var/log/suslik-encoder
```

`/etc/systemd/system/suslik-encoder.service`:

```
[Unit]
Description=Suslik Media HLS encoder
After=network-online.target
Wants=network-online.target

[Service]
User=suslik-encoder
EnvironmentFile=/etc/suslik-encoder.env
WorkingDirectory=/opt/suslik/encoder
ExecStart=/usr/bin/node /opt/suslik/encoder/index.js
Restart=always
RestartSec=30
KillSignal=SIGTERM
TimeoutStopSec=30
LogsDirectory=suslik-encoder

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl daemon-reload && sudo systemctl enable --now suslik-encoder
journalctl -u suslik-encoder -f        # плюс /var/log/suslik-encoder/encoder.log
```

## Проверка

```
cd encoder
npm test                    # = node --test: отпечаток (vectors.json), плейлисты, план, интеграция на образце
```

Интеграционный тест сам делает образец (testsrc2 1080p, 2 × AC3 5.1, SRT, MKV, 36 с), гоняет весь цикл через заглушку
и проверяет: EXT-X-MAP и EXT-X-BYTERANGE, непрерывные смещения до размера `.m4s`, ключевые кадры 1080p и 720p на одних
временах с шагом 6 с, `subs.vtt` и его плейлист, `manifest.json`, `done`; затем v1-copy на втором образце с неровными ключевыми кадрами (0; 4,2; 9,1; 13; 20,5; 24; 31,3 с, B-кадры):
копия + 720p, совпадение EXTINF и ключевых кадров 720p с исходником и началами сегментов копии; alias, wait, лимит размера.
`test/tonemap.test.js` — выбор устройства Vulkan (llvmpipe отбрасывается), аргументы cpu (уменьшение до тонмаппинга) и libplacebo,
DV 5 без libplacebo → ошибка без повтора. `test/hdr.test.js` — синтетический HDR10 4K (PQ, bt2020, 10 бит, mastering display, 12 с, x265)
через весь цикл путём cpu и путём libplacebo (без Vulkan-видеокарты — пропуск с причиной): bt709, yuv420p, ключевые кадры, средняя яркость,
время обоих путей — в диагностике теста (`SM_TEST_HDR_SIZE=2560x1440` — меньше кадр).
`test/audio.test.js` — задание «звук»: аргументы ffmpeg для сдвигов +, −, 0; на образце `.mka` (AC3 5.1, 34 с, `duration_ms` 36000) —
сдвиги −2000 и +1500 (плейлисты, AAC-LC стерео, длина 36 с, тишина в начале при +), `audio-done`, замена той же `xN`, ошибка на несуществующем файле.
Кодировщик — nvenc, если он работает, иначе x265; задать явно: `SM_TEST_ENCODER=x265 npm test`. Оставить файлы: `SM_TEST_KEEP=1`.
ffmpeg не в PATH — `SM_FFMPEG`/`SM_FFPROBE`. Без ffmpeg интеграционный тест пропускается.

Вручную на своём файле:

```
node stub/server.js D:\films\sample.mkv --port 8787 --profile v1      # или v1-copy | alias | wait
node stub/server.js D:\films\dub.mka --port 8787 --profile audio --duration-ms 60000 --offsets -2000,1500   # задание «звук»
# в другом окне:
set SM_SITE_URL=http://127.0.0.1:8787& set HLS_WORKER_TOKEN=test-token& set SM_UPLOAD_MODE=local& set SM_UPLOAD_LOCAL_DIR=D:\hls-cdn& set SM_ONCE=1& node index.js
```

Заглушка отдаёт `next-job.json` (с `mirror_url` на этот файл, Range поддерживается), на probe — `probe-response-*.json`
из `docs/hls-fixtures/`, принимает progress/done/audio-done/failed; что пришло — `GET /stub/state`.
`GET /stub/cancel` снимает текущее задание: дальше на его запросы — 410 `cancelled` (`?code=unknown_job` — 404, `?job_id=…` — другое задание).
С `--profile audio` `/next` отдаёт `next-job-audio.json` с дорожками на этот файл (по одной на каждый `--offsets`).
