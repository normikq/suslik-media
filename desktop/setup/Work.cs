using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;

namespace SuslikSetup
{
    /// <summary>Сама установка и удаление, без окна: окно только показывает ход работы.</summary>
    static class Work
    {
        public sealed class InstallResult
        {
            public bool Ok;
            public string Error;
            public bool MpvFailed;
        }

        public enum MpvState { Waiting, Skipped, Loading, Done, Failed }

        /// <summary>files(доля, действие) — ход MSI; mpv(состояние, доля, скачано, всего) — видеодвижок.</summary>
        public static InstallResult Install(string folder, Action<double, string> files, Action<MpvState, double, long, long> mpv, CancellationToken ct)
        {
            var result = new InstallResult();
            string package = null;
            try
            {
                if (Product.IsRunning) { files(0, "Закрываем Suslik Media…"); Product.CloseRunning(); }
                files(0, "Подготовка…");
                package = Extract();
                var (code, version) = Msi.PackageInfo(package);
                var installed = Msi.RelatedProducts(Product.UpgradeCode);

                // Стоит более новая версия: Windows Installer поверх неё не поставит, поэтому сначала убираем её
                // (данные в %APPDATA% остаются).
                foreach (var other in installed.Where(p => p != code && Newer(Msi.ProductInfo(p, "VersionString"), version)).ToList())
                {
                    files(0, "Удаление более новой версии…");
                    Msi.Uninstall(other, (f, a) => { }, () => ct.IsCancellationRequested, App.Log);
                }

                var cmd = $"ARPSYSTEMCOMPONENT=1 INSTALLDIR=\"{folder.TrimEnd('\\')}\" REBOOT=ReallySuppress";
                if (installed.Contains(code)) cmd += " REINSTALL=ALL REINSTALLMODE=vomus"; // та же версия: переустановить
                var r = Msi.Install(package, cmd, files, () => ct.IsCancellationRequested, App.Log);
                if (r == Msi.ERROR_INSTALL_USEREXIT) { result.Error = "Установка отменена."; return result; }
                if (r != Msi.ERROR_SUCCESS && r != Msi.ERROR_SUCCESS_REBOOT_REQUIRED)
                {
                    result.Error = (Msi.LastError ?? $"Windows Installer вернул код {r}.").Trim();
                    return result;
                }
                files(1, "Файлы скопированы");

                var uninstaller = SafeFind(folder, "SuslikUninstall.exe");
                if (uninstaller != null) Product.Register(uninstaller, Product.Version.Length > 0 && Product.Version != "0.0.0" ? Product.Version : version);
                else Product.Unhide();
                result.Ok = true;

                if (Mpv.Ready) mpv(MpvState.Skipped, 1, 0, 0);
                else
                {
                    mpv(MpvState.Loading, 0, 0, 0);
                    try
                    {
                        Mpv.Download((f, done, total) => mpv(MpvState.Loading, f, done, total), ct).GetAwaiter().GetResult();
                        mpv(MpvState.Done, 1, 0, 0);
                    }
                    catch
                    {
                        // Не страшно: приложение докачает видеодвижок само при запуске.
                        result.MpvFailed = true;
                        mpv(MpvState.Failed, 0, 0, 0);
                    }
                }
            }
            catch (Exception e)
            {
                result.Error = e.Message;
            }
            finally
            {
                if (package != null) try { File.Delete(package); } catch { }
            }
            return result;
        }

        /// <summary>progress(доля, действие). wipe — удалить и данные приложения.</summary>
        public static bool Uninstall(bool wipe, Action<double, string> progress, Func<bool> cancelled)
        {
            try
            {
                if (Product.IsRunning) { progress(0, "Закрываем Suslik Media…"); Product.CloseRunning(); }
                var folder = Product.InstalledFolder;
                foreach (var code in Msi.RelatedProducts(Product.UpgradeCode))
                {
                    var r = Msi.Uninstall(code, progress, cancelled, App.Log);
                    if (r != Msi.ERROR_SUCCESS && r != Msi.ERROR_SUCCESS_REBOOT_REQUIRED) return false;
                }
                Product.DeleteLeftovers(folder);
                if (wipe) { progress(1, "Удаление данных и кэша…"); Product.DeleteData(); }
                Product.Unregister();
                progress(1, "Готово");
                return true;
            }
            catch { return false; }
        }

        /// <summary>Размер встроенного MSI (для оценки места на диске).</summary>
        public static long PackageSize
        {
            get
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("package.msi")) return s?.Length ?? 0;
            }
        }

        static string Extract()
        {
            var dir = Path.Combine(Path.GetTempPath(), "SuslikSetup");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, $"Suslik Media-{Guid.NewGuid():N}.msi");
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("package.msi"))
            using (var f = File.Create(path))
                s.CopyTo(f, 1 << 20);
            return path;
        }

        static string SafeFind(string dir, string name)
        {
            try { return Directory.GetFiles(dir, name, SearchOption.AllDirectories).FirstOrDefault(); } catch { return null; }
        }

        static bool Newer(string a, string b)
        {
            return System.Version.TryParse(a, out var va) && System.Version.TryParse(b, out var vb) && va > vb;
        }
    }
}
