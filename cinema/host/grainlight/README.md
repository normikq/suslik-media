# Шейдеры GRAINLIGHT

Эмуляция плёнки 35 мм для mpv из https://github.com/claudiofabian-sudo/grainlight (коммит `d69a5fb`, 29.08.2026),
лицензия MIT — `LICENSE` рядом. Файлы `35mm-*.glsl` взяты без изменений; хост встраивает их ресурсами и пишет в
`%APPDATA%\SuslikCinema\shaders\<пресет>.glsl`. Свои пресеты («немое кино», «домашний проектор») — те же файлы с
другими `#define`, таблица в `../ProjectorLook.cpp`. Звук плёнки — фильтры из `scripts/audio-presets.lua` GRAINLIGHT.

Обновить: заменить `35mm-*.glsl` и `LICENSE`, проверить `ProjectorLookTest` (свои `#define` должны найтись в файлах).
