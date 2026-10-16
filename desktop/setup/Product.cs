using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using Microsoft.Win32;

namespace SuslikSetup
{
    /// <summary>Что и куда ставится, где приложение хранит данные, запись в «Установленных приложениях».</summary>
    static class Product
    {
        public const string Name = "Suslik Media";
        public const string ExeName = "Suslik Media.exe";
        /// <summary>upgradeUuid из desktop/build.gradle.kts: один на все версии.</summary>
        public const string UpgradeCode = "{6F1D2B9E-4C7A-4F3E-9A51-2D8C5E7B3A10}";
        const string ArpKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\SuslikMedia";
        const string UninstallRoot = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\";

        public static string Version =>
            Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion?.Split('+')[0] ?? "";

        static string AppData => Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        static string LocalAppData => Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        public static string DefaultFolder => Path.Combine(LocalAppData, Name);

        /// <summary>Настройки, вход, прогресс, постеры, журнал и libmpv (mpv\).</summary>
        public static string DataFolder => Path.Combine(AppData, "SuslikMedia");
        public static string MpvFolder => Path.Combine(DataFolder, "mpv");
        /// <summary>Запас фильмов и скачанное обновление.</summary>
        static string[] TempFolders => new[] { "SuslikMedia-stream", "SuslikMedia-update" }.Select(n => Path.Combine(Path.GetTempPath(), n)).ToArray();

        // ---------- установленная копия ----------

        public static string InstalledProduct => Msi.RelatedProducts(UpgradeCode).FirstOrDefault();

        public static string InstalledVersion
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(ArpKey))
                    if (k?.GetValue("DisplayVersion") is string v && v.Length > 0) return v;
                var p = InstalledProduct;
                return p == null ? null : Msi.ProductInfo(p, "VersionString");
            }
        }

        /// <summary>Папка установленной копии (её записывает /register), иначе null.</summary>
        public static string InstalledFolder
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(ArpKey))
                    if (k?.GetValue("InstallLocation") is string d && File.Exists(Path.Combine(d, ExeName))) return d;
                return File.Exists(Path.Combine(DefaultFolder, ExeName)) ? DefaultFolder : null;
            }
        }

        // ---------- запущенное приложение ----------

        public static bool IsRunning => Process.GetProcessesByName(Path.GetFileNameWithoutExtension(ExeName)).Length > 0;

        /// <summary>Просит приложение закрыться (оно сохранит прогресс), а если не закрылось — завершает.</summary>
        public static void CloseRunning()
        {
            foreach (var p in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(ExeName)))
            {
                try
                {
                    if (!p.CloseMainWindow() || !p.WaitForExit(8000)) { p.Kill(); p.WaitForExit(5000); }
                }
                catch { }
            }
        }

        public static void Launch(string folder)
        {
            var exe = Path.Combine(folder ?? InstalledFolder ?? DefaultFolder, ExeName);
            if (File.Exists(exe)) Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) });
        }

        // ---------- «Установленные приложения» ----------

        /// <summary>
        /// Своя запись вместо записи MSI: «Удалить» открывает наше окно (с очисткой данных), а запись MSI прячется
        /// (SystemComponent), чтобы приложение не было в списке дважды. Вызывается установщиком и самим приложением
        /// (SuslikUninstall.exe /register &lt;версия&gt;) — после самообновления.
        /// </summary>
        public static void Register(string uninstaller, string version)
        {
            var folder = FindAppFolder(Path.GetDirectoryName(uninstaller)) ?? InstalledFolder ?? DefaultFolder;
            using (var k = Registry.CurrentUser.CreateSubKey(ArpKey))
            {
                k.SetValue("DisplayName", Name);
                k.SetValue("DisplayVersion", version ?? "");
                k.SetValue("Publisher", Name);
                k.SetValue("DisplayIcon", Path.Combine(folder, ExeName));
                k.SetValue("InstallLocation", folder);
                k.SetValue("UninstallString", $"\"{uninstaller}\" /uninstall");
                k.SetValue("QuietUninstallString", $"\"{uninstaller}\" /uninstall /quiet");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                k.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, FolderSize(folder) / 1024), RegistryValueKind.DWord);
            }
            foreach (var code in Msi.RelatedProducts(UpgradeCode))
                using (var k = Registry.CurrentUser.OpenSubKey(UninstallRoot + code, true))
                    k?.SetValue("SystemComponent", 1, RegistryValueKind.DWord);
        }

        /// <summary>Окна удаления в приложении нет (старая сборка): пусть остаётся обычная запись MSI.</summary>
        public static void Unhide()
        {
            foreach (var code in Msi.RelatedProducts(UpgradeCode))
                using (var k = Registry.CurrentUser.OpenSubKey(UninstallRoot + code, true))
                    try { k?.DeleteValue("SystemComponent", false); } catch { }
        }

        public static void Unregister()
        {
            try { Registry.CurrentUser.DeleteSubKeyTree(ArpKey, false); } catch { }
        }

        /// <summary>SuslikUninstall.exe лежит в &lt;папка&gt;\app\resources: поднимаемся до папки с Suslik Media.exe.</summary>
        static string FindAppFolder(string start)
        {
            for (var d = start; !string.IsNullOrEmpty(d); d = Path.GetDirectoryName(d))
                if (File.Exists(Path.Combine(d, ExeName))) return d;
            return null;
        }

        // ---------- очистка ----------

        public static long DataSize() => FolderSize(DataFolder) + TempFolders.Sum(FolderSize);

        /// <summary>Всё, что приложение оставляет вне папки установки: настройки, вход, прогресс, кэш, запас, libmpv.</summary>
        public static void DeleteData()
        {
            foreach (var d in TempFolders.Concat(new[] { DataFolder })) DeleteFolder(d);
        }

        /// <summary>То, что приложение само создаёт в папке установки (suslik.jsa, отчёты о сбоях): MSI о нём не знает.</summary>
        public static void DeleteLeftovers(string folder)
        {
            if (string.IsNullOrEmpty(folder) || !Directory.Exists(folder)) return;
            // Только если от программы ничего не осталось: чужие файлы в выбранной папке не трогаем.
            if (File.Exists(Path.Combine(folder, ExeName))) return;
            foreach (var f in new[] { "suslik.jsa" }.Select(n => Path.Combine(folder, n))
                         .Concat(SafeFiles(folder, "hs_err_pid*.log")))
                try { File.Delete(f); } catch { }
            foreach (var sub in new[] { "app", "runtime" }) DeleteFolderIfOnlyEmptyDirs(Path.Combine(folder, sub));
            DeleteFolderIfOnlyEmptyDirs(folder);
        }

        static string[] SafeFiles(string dir, string mask) { try { return Directory.GetFiles(dir, mask); } catch { return new string[0]; } }

        static void DeleteFolder(string d)
        {
            try { if (Directory.Exists(d)) Directory.Delete(d, true); } catch { }
        }

        static void DeleteFolderIfOnlyEmptyDirs(string d)
        {
            try
            {
                if (!Directory.Exists(d)) return;
                if (Directory.EnumerateFiles(d, "*", SearchOption.AllDirectories).Any()) return;
                Directory.Delete(d, true);
            }
            catch { }
        }

        public static long FolderSize(string d)
        {
            try
            {
                if (!Directory.Exists(d)) return 0;
                return new DirectoryInfo(d).EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => { try { return f.Length; } catch { return 0L; } });
            }
            catch { return 0; }
        }

        public static string Mb(long bytes) => bytes >= 1L << 30
            ? $"{bytes / (double)(1L << 30):0.0} ГБ"
            : $"{Math.Max(1, bytes / (1L << 20))} МБ";
    }
}
