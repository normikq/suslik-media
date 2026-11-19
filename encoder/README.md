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
   │     1080p/ 720p/ (v1) или 1080p/ (v1-copy) — video.m4s + index.m3u8
   │     a0…a3/ — AAC-LC стерео 160 кбит, audio.m4s + index.m3u8
   │     s0…s5/ — subs.vtt (текстовые субтитры) + тривиальный index.m3u8; PGS/VobSub/DVB → skipped
   ├─ проверка плейлистов (EXT-X-MAP, EXT-X-BYTERANGE, смещения без дыр до конца файла), manifest.json
   ├─ rsync → <SM_CDN_TMP_DIR>/<hls_id>.<profile>.<rand>/, затем ssh «finalize …» (атомарный перенос на CDN)
   └─ POST done {job_id, hls_id, profile, manifest_sha256, bytes}
Пока задание идёт — POST progress раз в SM_PROGRESS_S (продлевает аренду). Ошибка → POST failed {stage, error, retryable}.
```

Стадии в `progress`/`failed`: `probe`, `fingerprint`, `wait`, `encode`, `verify`, `upload`, `done`.
Один фильм за раз (+ pid-файл `work/.lock` от второго экземпляра). Временные файлы — `SM_WORK_DIR`, чистятся при старте и после каждого задания.
Журнал — `SM_LOG_DIR/encoder.log`, ротация по `SM_LOG_MAX_MB`, хранится `SM_LOG_KEEP` старых файлов.
Ошибки цикла не роняют процесс; если процесс всё же упал — его поднимает NSSM/systemd.
На SIGINT/SIGTERM (NSSM — Ctrl+Break) ffmpeg останавливается, серверу уходит `failed` с `retryable=true`.

### Профили

- **v1** — HEVC (`hevc_nvenc`, `-rc vbr -cq` + `-maxrate`/`-bufsize`): 1080p ≤ 5 Мбит, 720p ≤ 3 Мбит; вписывание в 1920×1080 и 1280×720 без увеличения
  (исходник ≤ 1280×720 — только `720p/`). Ключевые кадры — только принудительные каждые 6 с
  (`-force_key_frames expr:gte(t,n_forced*6)`, `-forced-idr 1`, `-no-scenecut 1`, большой `-g`), поэтому сегменты 1080p и 720p совпадают.
  HDR (PQ/HLG/DV) → SDR: `zscale` + `tonemap=hable`, метки bt709. Чересстрочный исходник — `bwdif`. `-tag:v hvc1`.
- **v1-copy** — `-c:v copy` одной дорожкой (`1080p/`), звук в AAC. Решает сервер по probe.
- `SM_ENCODER=x265` — то же на процессоре (медленно), `x264` — H.264 (`avc1`), только для проверки без видеокарты.

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
| `SM_CDN_TMP_DIR` | `.tmp` | куда rsync кладёт папку (относительно корня rrsync у `hls-upload`) |
| `SM_CDN_FINALIZE_CMD` | `finalize {name}` (так принимает `infra/cdn/bin/hls-upload-shell`, ответ `ok <hls_id>/<profile> <байт>`) | команда по ssh после rsync; подстановки `{name}` (имя папки в `.tmp`), `{tmp}` (`.tmp/<name>`), `{hls_id}`, `{profile}` |
| `SM_UPLOAD_MODE` | `rsync` | `rsync` или `local` (копия в папку — для проверки без CDN) |
| `SM_UPLOAD_LOCAL_DIR` | — | корень «CDN» для `local`: `<dir>/.tmp/<name>/` → `<dir>/<hls_id>/<profile>/` |
| `SM_RSYNC`, `SM_SSH` | `rsync`, `ssh` | пути к программам |
| `SM_FFMPEG`, `SM_FFPROBE` | `ffmpeg`, `ffprobe` | пути к программам |
| `SM_HWDEC` | `1` при nvenc | декодирование исходника на видеокарте (`-hwaccel cuda`) |
| `SM_CQ` | `24` (nvenc) / `23` | качество: `-cq` у nvenc, `-crf` у x265/x264 |
| `SM_NVENC_PRESET` | `p5` | пресет nvenc |
| `SM_SW_PRESET` | `medium` | пресет x265/x264 (`ultrafast` для тестов) |
| `SM_WORK_DIR` | `encoder/work` | временные файлы (нужно место на весь фильм в HLS, ~3–5 ГБ) |
| `SM_LOG_DIR` | `encoder/logs` | журнал |
| `SM_LOG_MAX_MB`, `SM_LOG_KEEP` | `10`, `5` | ротация журнала |
| `SM_IDLE_S` | `30` | пауза после `204` |
| `SM_ERROR_S` | `60` | пауза после ошибки связи с сервером |
| `SM_PROGRESS_S` | `10` | период `progress` |
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

```
# драйвер NVIDIA (Ubuntu): sudo ubuntu-drivers install  → nvidia-smi
# ffmpeg ≥ 6.1 с nvenc: статическая сборка BtbN (ffmpeg-master-latest-linux64-gpl) в /opt/ffmpeg
sudo useradd -r -m -d /var/lib/suslik-encoder suslik-encoder
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
временах с шагом 6 с, `subs.vtt` и его плейлист, `manifest.json`, `done`; затем v1-copy, alias, wait, лимит размера.
Кодировщик — nvenc, если он работает, иначе x265; задать явно: `SM_TEST_ENCODER=x265 npm test`. Оставить файлы: `SM_TEST_KEEP=1`.
ffmpeg не в PATH — `SM_FFMPEG`/`SM_FFPROBE`. Без ffmpeg интеграционный тест пропускается.

Вручную на своём файле:

```
node stub/server.js D:\films\sample.mkv --port 8787 --profile v1      # или v1-copy | alias | wait
# в другом окне:
set SM_SITE_URL=http://127.0.0.1:8787& set HLS_WORKER_TOKEN=test-token& set SM_UPLOAD_MODE=local& set SM_UPLOAD_LOCAL_DIR=D:\hls-cdn& set SM_ONCE=1& node index.js
```

Заглушка отдаёт `next-job.json` (с `mirror_url` на этот файл, Range поддерживается), на probe — `probe-response-*.json`
из `docs/hls-fixtures/`, принимает progress/done/failed; что пришло — `GET /stub/state`.
