using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using Microsoft.Win32;

namespace SuslikSetup
{
    /// <summary>
    /// Что и куда ставится, где приложение хранит данные, запись в «Установленных приложениях».
    /// Qt-версия (desktop-qt): своя программа, папка, данные и UpgradeCode — ставится рядом со старой Kotlin-версией и её не трогает.
    /// </summary>
    static class Product
    {
        /// <summary>Название в окне мастера — как у Kotlin-версии.</summary>
        public const string Name = "Suslik Media";
        /// <summary>Название в «Установленных приложениях» и папка установки — как у MSI (installer/build-msi.sh).</summary>
        public const string DisplayName = "Suslik Media Qt";
        public const string FolderName = "Suslik Media Qt";
        public const string ExeName = "SuslikMediaQt.exe";
        /// <summary>UpgradeCode из installer/build-msi.sh и app/src/Updater.cpp: один на все версии.</summary>
        public const string UpgradeCode = "{BFBB56B9-7E7F-4AF8-BA0B-C1BA1A054E70}";
        const string ArpKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\SuslikMediaQt";
        const string UninstallRoot = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\";

        public static string Version =>
            Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion?.Split('+')[0] ?? "";

        static string AppData => Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        static string LocalAppData => Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        /// <summary>Папка MSI по умолчанию: %LOCALAPPDATA%\Programs\Suslik Media Qt.</summary>
        public static string DefaultFolder => Path.Combine(LocalAppData, "Programs", FolderName);

        /// <summary>Настройки, вход, прогресс, постеры, журнал (app/src/AppDir.cpp).</summary>
        public static string DataFolder => Path.Combine(AppData, "SuslikMediaQt");
        /// <summary>Видеодвижок libmpv: там же его ищет и докачивает программа (player/Mpv.cpp, MpvInstall.cpp).</summary>
        public static string MpvFolder => Path.Combine(DataFolder, "mpv");
        /// <summary>Запас фильмов и скачанное обновление.</summary>
        static string[] TempFolders => new[] { "SuslikMediaQt-stream", "SuslikMediaQt-update" }.Select(n => Path.Combine(Path.GetTempPath(), n)).ToArray();

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
                k.SetValue("DisplayName", DisplayName);
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

        /// <summary>SuslikUninstall.exe лежит рядом с SuslikMediaQt.exe; на всякий случай поднимаемся до папки с программой.</summary>
        static string FindAppFolder(string start)
        {
            for (var d = start; !string.IsNullOrEmpty(d); d = Path.GetDirectoryName(d))
                if (File.Exists(Path.Combine(d, ExeName))) return d;
            return null;
        }

        // ---------- очистка ----------

        /// <summary>Без видеодвижка: его удаление убирает в любом случае.</summary>
        public static long DataSize() => FolderSize(DataFolder) - FolderSize(MpvFolder) + TempFolders.Sum(FolderSize);

        /// <summary>Всё, что приложение оставляет вне папки установки: настройки, вход, прогресс, кэш, запас.</summary>
        public static void DeleteData()
        {
            foreach (var d in TempFolders.Concat(new[] { DataFolder })) DeleteFolder(d);
        }

        /// <summary>
        /// То, чего MSI не знает: видеодвижок (его качают установщик и программа, в MSI его нет) и libmpv, положенная
        /// в папку программы руками.
        /// </summary>
        public static void DeleteLeftovers(string folder)
        {
            DeleteFolder(MpvFolder);
            if (string.IsNullOrEmpty(folder) || !Directory.Exists(folder)) return;
            // Только если от программы ничего не осталось: чужие файлы в выбранной папке не трогаем.
            if (File.Exists(Path.Combine(folder, ExeName))) return;
            foreach (var f in new[] { "libmpv-2.dll", "libmpv-2.dll.part", "libmpv.version" }.Select(n => Path.Combine(folder, n)))
                try { File.Delete(f); } catch { }
            DeleteFolderIfOnlyEmptyDirs(folder);
        }

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
