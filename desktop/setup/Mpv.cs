using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;

namespace SuslikSetup
{
    /// <summary>
    /// libmpv-2.dll (видеодвижок mpv) не входит ни в установщик, ни в MSI. Она лежит в папке приложения
    /// (&lt;папка&gt;\app\resources) и при установке проверяется: есть нужная версия — ничего не делаем, нет — качаем
    /// с нашего сервера (адрес и SHA-256 — в libmpv.properties, тот же файл читает приложение).
    /// Метка libmpv.version рядом = SHA-256 библиотеки, чтобы не считать хэш 120 МБ при каждом обновлении.
    /// </summary>
    static class Mpv
    {
        static readonly Dictionary<string, string> pin = Load();
        public static string Url => pin.TryGetValue("url", out var v) ? v : "";
        static string Sha256 => (pin.TryGetValue("dllSha256", out var v) ? v : "").ToLowerInvariant();

        static string Dir(string folder) => Path.Combine(folder, "app", "resources");
        static string Dll(string folder) => Path.Combine(Dir(folder), "libmpv-2.dll");
        static string Mark(string folder) => Path.Combine(Dir(folder), "libmpv.version");

        static Dictionary<string, string> Load()
        {
            var map = new Dictionary<string, string>();
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("libmpv.properties"))
            {
                if (s == null) return map;
                using (var r = new StreamReader(s))
                    for (string line; (line = r.ReadLine()) != null;)
                    {
                        line = line.Trim();
                        if (line.Length == 0 || line[0] == '#') continue;
                        int i = line.IndexOf('=');
                        if (i > 0) map[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                    }
            }
            return map;
        }

        /// <summary>Нужная версия уже лежит в папке установки: при обновлении ничего не качаем.</summary>
        public static bool Ready(string folder)
        {
            if (Sha256.Length == 0 || string.IsNullOrEmpty(folder)) return Sha256.Length == 0;
            try
            {
                var dll = Dll(folder);
                if (!File.Exists(dll)) return false;
                if (File.Exists(Mark(folder)) && File.ReadAllText(Mark(folder)).Trim() == Sha256) return true;
                // Положили руками, без метки: проверяем один раз и ставим метку.
                using (var s = File.OpenRead(dll))
                using (var sha = SHA256.Create())
                    if (Hex(sha.ComputeHash(s)) != Sha256) return false;
                File.WriteAllText(Mark(folder), Sha256);
                return true;
            }
            catch { return false; }
        }

        static string Keep => Path.Combine(Path.GetTempPath(), "SuslikSetup", "mpv-keep");

        /// <summary>Уносит готовую библиотеку из папки установки во временную. true — унесли (потом [Restore]).</summary>
        public static bool Stash(string folder)
        {
            if (!Ready(folder)) return false;
            try
            {
                if (Directory.Exists(Keep)) Directory.Delete(Keep, true);
                Directory.CreateDirectory(Keep);
                File.Move(Dll(folder), Path.Combine(Keep, "libmpv-2.dll"));
                File.Copy(Mark(folder), Path.Combine(Keep, "libmpv.version"), true);
                return true;
            }
            catch { return false; }
        }

        /// <summary>Возвращает унесённую библиотеку в папку установки (если там уже есть своя — оставляет её).</summary>
        public static void Restore(string folder)
        {
            var kept = Path.Combine(Keep, "libmpv-2.dll");
            if (!File.Exists(kept)) return;
            try
            {
                Directory.CreateDirectory(Dir(folder));
                if (!File.Exists(Dll(folder)))
                {
                    File.Move(kept, Dll(folder));
                    File.WriteAllText(Mark(folder), Sha256);
                }
                Directory.Delete(Keep, true);
            }
            catch { }
        }

        /// <summary>Качает с сервера и проверяет; progress(доля, скачано байт, всего байт). Ошибка — понятным текстом.</summary>
        public static void Download(string folder, Action<double, long, long> progress, CancellationToken ct)
        {
            if (Url.Length == 0) throw new IOException("Не задан адрес видеодвижка.");
            Directory.CreateDirectory(Dir(folder));
            var dll = Dll(folder);
            var part = dll + ".part";
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var req = (HttpWebRequest)WebRequest.Create(Url);
            req.Timeout = 20000;
            req.ReadWriteTimeout = 60000;
            req.UserAgent = "SuslikSetup/" + Product.Version;
            req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            try
            {
                using (ct.Register(() => { try { req.Abort(); } catch { } }))
                using (var res = (HttpWebResponse)req.GetResponse())
                using (var body = res.GetResponseStream())
                using (var sha = SHA256.Create())
                {
                    long total = res.ContentLength;
                    long got = 0;
                    using (var file = File.Create(part))
                    {
                        var buf = new byte[1 << 16];
                        int n;
                        while ((n = body.Read(buf, 0, buf.Length)) > 0)
                        {
                            ct.ThrowIfCancellationRequested();
                            file.Write(buf, 0, n);
                            sha.TransformBlock(buf, 0, n, null, 0);
                            got += n;
                            progress(total > 0 ? (double)got / total : 0, got, total);
                        }
                    }
                    sha.TransformFinalBlock(new byte[0], 0, 0);
                    if (Hex(sha.Hash) != Sha256) throw new IOException("Файл видеодвижка с сервера повреждён или другой версии.");
                }
                if (File.Exists(dll)) File.Delete(dll);
                File.Move(part, dll);
                File.WriteAllText(Mark(folder), Sha256);
            }
            catch (WebException e) when (!ct.IsCancellationRequested)
            {
                TryDelete(part);
                if (e.Response is HttpWebResponse r)
                    throw new IOException($"Сервер видеодвижка ответил ошибкой {(int)r.StatusCode}.", e);
                throw new IOException("Сервер видеодвижка недоступен. Проверьте интернет или попробуйте позже.", e);
            }
            catch
            {
                TryDelete(part);
                throw;
            }
        }

        static void TryDelete(string f) { try { File.Delete(f); } catch { } }

        static string Hex(byte[] b) => BitConverter.ToString(b).Replace("-", "").ToLowerInvariant();
    }
}
