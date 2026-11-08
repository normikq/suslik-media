"""Suslik Cinema - запуск хоста suslik-cinema.exe с телефона VAO (docs/CYBERPUNK.md, этап 3).

V's API Overlay запускает этот скрипт по VAO.StartHelper (Lua сама программы запускать не умеет). Скрипт:
  * берёт путь к хосту из ../host.txt (хост пишет его сам при каждом запуске рядом с игрой);
  * если хост уже жив (host.json обновлялся последние 4 с) - ничего не делает;
  * иначе запускает хост с --remote отдельным процессом (он живёт дольше скрипта и самого VAO) и ждёт до 15 с,
    пока хост ответит;
  * выходит с кодом 0 (VAO не перезапускает), при ошибке - 2; последняя строка вывода видна игроку в Logs.
Связь телефона с хостом - файлы в папке мода (remote.json, host.json, library.json), этот скрипт в ней не участвует.
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.dirname(HERE)


def host_alive():
    try:
        with open(os.path.join(MOD, "host.json"), "r", encoding="utf-8") as f:
            data = json.load(f)
        return time.time() - float(data.get("time", 0)) <= 4
    except (OSError, ValueError):
        return False


def main():
    if host_alive():
        print("Suslik Cinema: host is already running", flush=True)
        return 0
    try:
        with open(os.path.join(MOD, "host.txt"), "r", encoding="utf-8") as f:
            exe = f.readline().strip()
    except OSError:
        exe = ""
    if not exe or not os.path.isfile(exe):
        print("Suslik Cinema: start suslik-cinema.exe once by hand - it will remember its place", flush=True)
        return 2
    flags = 0
    if os.name == "nt":
        # свой процесс, не дочерний: VAO гасит helper-ы при выходе из игры, хост при этом должен вернуть звук сам
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | 0x01000000  # CREATE_BREAKAWAY_FROM_JOB
    args = [exe, "--remote"]
    try:
        subprocess.Popen(args, cwd=os.path.dirname(exe), creationflags=flags, close_fds=True)
    except OSError:
        try:  # задание VAO не разрешает отрыв - запускаем без него
            subprocess.Popen(args, cwd=os.path.dirname(exe),
                             creationflags=flags & ~0x01000000, close_fds=True)
        except OSError as e:
            print("Suslik Cinema: could not start the host: %s" % e, flush=True)
            return 2
    for _ in range(30):
        time.sleep(0.5)
        if host_alive():
            print("Suslik Cinema: host started", flush=True)
            return 0
    print("Suslik Cinema: the host did not answer in 15 s (see %%APPDATA%%\\SuslikCinema\\player.log)", flush=True)
    return 2


if __name__ == "__main__":
    sys.exit(main())
