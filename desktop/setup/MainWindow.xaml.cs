using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using IOPath = System.IO.Path;
using Path = System.Windows.Shapes.Path;

namespace SuslikSetup
{
    public partial class MainWindow : Window
    {
        static readonly Color Accent = Color.FromRgb(0x5A, 0xB0, 0xFF);
        static readonly IEasingFunction Ease = new CubicEase { EasingMode = EasingMode.EaseOut };

        readonly bool uninstall;
        readonly List<StepView> steps = new List<StepView>();
        int step;
        bool busy, finished, failed, wasInstalled;
        FrameworkElement page;
        CancellationTokenSource cts;
        long mpvSize = 121L << 20;

        public MainWindow(bool uninstall)
        {
            this.uninstall = uninstall;
            InitializeComponent();
            Shell.SizeChanged += (_, e) => ShellClip.Rect = new Rect(e.NewSize);
            Loaded += (_, __) => { if (!preview) StartAmbient(); };

            var names = uninstall
                ? new[] { ("Подтверждение", "Что удалить"), ("Удаление", "Удаление файлов"), ("Завершение", "Всё убрано") }
                : new[] { ("Добро пожаловать", "Подготовка к установке"), ("Выбор папки", "Куда установить"), ("Установка", "Копирование файлов"), ("Завершение", "Готово к запуску") };
            for (int i = 0; i < names.Length; i++)
            {
                var s = new StepView(i + 1, names[i].Item1, names[i].Item2, i < names.Length - 1);
                steps.Add(s);
                Steps.Children.Add(s.Root);
            }

            if (uninstall) PrepareUninstall(); else PrepareInstall();
        }

        // ---------- подготовка ----------

        void PrepareInstall()
        {
            Title = "Установка Suslik Media";
            var installed = Product.InstalledVersion;
            var version = Product.Version;
            wasInstalled = installed != null;
            if (installed != null)
            {
                WelcomeTitle.Text = "С возвращением!";
                WelcomeLead.Text = installed == version || string.IsNullOrEmpty(version)
                    ? $"Suslik Media {installed} уже установлена — её можно переустановить."
                    : $"Мастер обновит Suslik Media {installed} до версии {version}.";
            }
            if (!string.IsNullOrEmpty(version) && version != "0.0.0") FooterNote.Text = $"Версия {version}";
            FolderBox.Text = Product.InstalledFolder ?? Product.DefaultFolder;
            Show(PageWelcome, 0, animate: false);
        }

        void PrepareUninstall()
        {
            Title = "Удаление Suslik Media";
            TitleMode.Text = "Удаление";
            var installed = Product.InstalledVersion;
            if (installed != null) UninstallLead.Text = $"Suslik Media {installed} будет удалена с этого компьютера.";
            NextText.Text = "Удалить";
            NextArrow.Visibility = Visibility.Collapsed;
            NextButton.Style = (Style)FindResource("DangerButton");
            NextButton.IsDefault = false;
            CancelButton.IsDefault = true;
            Task.Run(() => Product.DataSize()).ContinueWith(t =>
            {
                if (t.Result > 0) WipeDetails.Text += $" Сейчас занимают {Product.Mb(t.Result)}.";
            }, TaskScheduler.FromCurrentSynchronizationContext());
            Show(PageUninstall, 0, animate: false);
        }

        // ---------- переходы ----------

        void Show(FrameworkElement next, int stepIndex, bool animate = true)
        {
            step = stepIndex;
            for (int i = 0; i < steps.Count; i++) steps[i].SetState(i < stepIndex ? StepState.Done : i == stepIndex ? StepState.Active : StepState.Pending, animate);

            var prev = page;
            page = next;
            if (prev != null && prev != next)
            {
                if (animate)
                {
                    var slide = new TranslateTransform();
                    prev.RenderTransform = slide;
                    var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(160));
                    fade.Completed += (_, __) => { if (page != prev) prev.Visibility = Visibility.Collapsed; prev.BeginAnimation(OpacityProperty, null); };
                    prev.BeginAnimation(OpacityProperty, fade);
                    slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-18, TimeSpan.FromMilliseconds(160)));
                }
                else prev.Visibility = Visibility.Collapsed;
            }
            next.Visibility = Visibility.Visible;
            if (animate)
            {
                var slide = new TranslateTransform(28, 0);
                next.RenderTransform = slide;
                next.Opacity = 0;
                var delay = TimeSpan.FromMilliseconds(prev != null && prev != next ? 120 : 0);
                next.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(340)) { BeginTime = delay, EasingFunction = Ease });
                slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(28, 0, TimeSpan.FromMilliseconds(420)) { BeginTime = delay, EasingFunction = Ease });
            }
            UpdateFooter();
        }

        void UpdateFooter()
        {
            BackButton.Visibility = !uninstall && page == PageFolder ? Visibility.Visible : Visibility.Collapsed;
            CancelButton.Visibility = finished || page == PageProgress ? Visibility.Collapsed : Visibility.Visible;
            NextButton.Visibility = page == PageProgress ? Visibility.Collapsed : Visibility.Visible;
            CloseButton.IsEnabled = !busy;
            NextButton.IsEnabled = true;
            if (page == PageFolder) { NextText.Text = "Установить"; NextArrow.Visibility = Visibility.Visible; }
            else if (page == PageWelcome) { NextText.Text = "Далее"; NextArrow.Visibility = Visibility.Visible; }
            else if (finished)
            {
                NextText.Text = failed ? "Закрыть" : "Готово";
                NextArrow.Visibility = Visibility.Collapsed;
                NextButton.Style = (Style)FindResource("PrimaryButton");
                NextButton.IsDefault = true;
            }
            if (page == PageFolder) FolderBox_TextChanged(null, null);
        }

        // ---------- кнопки ----------

        void Next_Click(object sender, RoutedEventArgs e)
        {
            if (busy) return;
            if (finished)
            {
                if (!uninstall && !failed && LaunchCheck.IsChecked == true) try { Product.Launch(FolderBox.Text.Trim()); } catch { }
                Close();
                return;
            }
            if (uninstall) { RunUninstall(); return; }
            if (page == PageWelcome) { Show(PageFolder, 1); return; }
            if (page == PageFolder)
            {
                var folder = FolderBox.Text.Trim();
                if (!ValidFolder(folder)) { Shake(FolderBox); return; }
                RunInstall(folder);
            }
        }

        void Back_Click(object sender, RoutedEventArgs e)
        {
            if (!busy && page == PageFolder) Show(PageWelcome, 0);
        }

        void Close_Click(object sender, RoutedEventArgs e)
        {
            if (busy) return;
            Close();
        }

        void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

        void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ButtonState == MouseButtonState.Pressed) try { DragMove(); } catch { }
        }

        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            if (busy) e.Cancel = true;
            base.OnClosing(e);
        }

        void Browse_Click(object sender, RoutedEventArgs e)
        {
            var current = FolderBox.Text.Trim();
            var picked = FolderPicker.Pick(new WindowInteropHelper(this).Handle, Directory.Exists(current) ? current : IOPath.GetDirectoryName(current));
            if (picked == null) return;
            // Выбрали общую папку (например, «Программы»): ставим в подпапку Suslik Media, а не прямо в неё.
            if (!string.Equals(IOPath.GetFileName(picked.TrimEnd('\\')), Product.Name, StringComparison.OrdinalIgnoreCase))
                picked = IOPath.Combine(picked, Product.Name);
            FolderBox.Text = picked;
        }

        void FolderBox_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (NeedText == null) return;
            var mpvNeeded = !Mpv.Ready;
            var need = (long)(Work.PackageSize * 2.4) + (mpvNeeded ? mpvSize : 0);
            NeedText.Text = "≈ " + Product.Mb(need);
            long free = -1;
            try
            {
                var root = IOPath.GetPathRoot(IOPath.GetFullPath(FolderBox.Text.Trim()));
                free = new DriveInfo(root).AvailableFreeSpace;
            }
            catch { }
            FreeText.Text = free >= 0 ? Product.Mb(free) : "—";
            FreeText.Foreground = free >= 0 && free < need ? (Brush)FindResource("Bad") : (Brush)FindResource("Text");
            FolderNote.Text = (mpvNeeded
                    ? "Видеодвижок mpv (≈ 47 МБ) скачается во время установки."
                    : "Видеодвижок mpv уже установлен — повторно скачиваться не будет.")
                + " Приложение ставится только для вас, права администратора не нужны.";
            NextButton.IsEnabled = ValidFolder(FolderBox.Text.Trim());
        }

        static bool ValidFolder(string f)
        {
            try { return f.Length > 3 && IOPath.IsPathRooted(f) && IOPath.GetFullPath(f).Length > 3; } catch { return false; }
        }

        void WipeCheck_Changed(object sender, RoutedEventArgs e)
        {
            WipeHint.Text = WipeCheck.IsChecked == true
                ? "Будет удалено всё: потом придётся заново войти, а прогресс просмотра на этом компьютере пропадёт."
                : "Без галочки всё это останется: после повторной установки библиотека, вход и прогресс будут на месте.";
            WipeHint.Foreground = (Brush)FindResource(WipeCheck.IsChecked == true ? "Bad" : "Muted");
        }

        // ---------- установка ----------

        void RunInstall(string folder)
        {
            busy = true;
            cts = new CancellationTokenSource();
            ProgressTitle.Text = "Установка";
            ProgressLead.Text = "Копирование файлов.";
            var files = AddTask("Файлы приложения");
            var mpv = AddTask("Видеодвижок mpv");
            files.Set(TaskState.Working, "");
            var mpvNeeded = !Mpv.Ready;
            mpv.Set(mpvNeeded ? TaskState.Waiting : TaskState.Done, mpvNeeded ? "скачается после файлов" : "уже установлен — пропущено");
            Show(PageProgress, 2);
            StartShimmer();

            double filesPart = 0, mpvPart = 0;
            void Overall(string action)
            {
                var v = mpvNeeded ? filesPart * 0.55 + mpvPart * 0.45 : filesPart;
                SetProgress(v, action);
            }

            var throttle = new Throttle(Dispatcher);
            Task.Run(() => Work.Install(folder,
                (f, action) => throttle.Post(() => { filesPart = f; Overall(action); }),
                (state, f, done, total) => throttle.Post(() =>
                {
                    switch (state)
                    {
                        case Work.MpvState.Loading:
                            files.Set(TaskState.Done, "");
                            mpvPart = f;
                            mpv.Set(TaskState.Working, total > 0 ? $"{Product.Mb(done)} из {Product.Mb(total)}" : "загрузка…");
                            Overall("Загрузка видеодвижка mpv…");
                            break;
                        case Work.MpvState.Done: mpvPart = 1; mpv.Set(TaskState.Done, "установлен"); Overall(""); break;
                        case Work.MpvState.Skipped: files.Set(TaskState.Done, ""); break;
                        case Work.MpvState.Failed: mpv.Set(TaskState.Failed, "не скачался — приложение докачает его само"); break;
                    }
                }, important: state != Work.MpvState.Loading),
                cts.Token)).ContinueWith(t =>
            {
                throttle.Flush();
                var r = t.Status == TaskStatus.RanToCompletion ? t.Result : new Work.InstallResult { Error = t.Exception?.GetBaseException().Message };
                busy = false;
                if (r.Ok)
                {
                    files.Set(TaskState.Done, "");
                    SetProgress(1, "");
                    Finish(true, "Готово!", wasInstalled ? "Suslik Media обновлена." : "Suslik Media установлена и готова к работе.",
                        r.MpvFailed ? "Видеодвижок mpv не удалось скачать сейчас — приложение скачает его само при первом запуске." : null);
                }
                else
                {
                    files.Set(TaskState.Failed, "");
                    Finish(false, "Не получилось", r.Error ?? "Установка не завершилась.", $"Подробности — в журнале установки: {App.Log}");
                }
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        // ---------- удаление ----------

        void RunUninstall()
        {
            busy = true;
            var wipe = WipeCheck.IsChecked == true;
            ProgressTitle.Text = "Удаление";
            ProgressLead.Text = "Убираем Suslik Media с компьютера.";
            var files = AddTask("Файлы приложения");
            var data = wipe ? AddTask("Данные и кэш") : null;
            files.Set(TaskState.Working, "");
            data?.Set(TaskState.Waiting, "");
            Show(PageProgress, 1);
            StartShimmer();
            var throttle = new Throttle(Dispatcher);
            Task.Run(() => Work.Uninstall(wipe, (f, action) => throttle.Post(() =>
            {
                SetProgress(wipe ? f * 0.9 : f, action);
                if (f >= 1 && data != null) { files.Set(TaskState.Done, ""); data.Set(TaskState.Working, ""); }
            }), () => false)).ContinueWith(t =>
            {
                throttle.Flush();
                busy = false;
                var ok = t.Status == TaskStatus.RanToCompletion && t.Result;
                if (ok)
                {
                    files.Set(TaskState.Done, "");
                    data?.Set(TaskState.Done, "");
                    SetProgress(1, "");
                    Finish(true, "Suslik Media удалена", wipe ? "Все данные и кэш тоже удалены." : "Настройки и прогресс сохранены на случай повторной установки.", null);
                }
                else
                {
                    files.Set(TaskState.Failed, "");
                    Finish(false, "Не получилось", "Удаление не завершилось.", $"Подробности — в журнале: {App.Log}");
                }
            }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        // ---------- ход работы ----------

        void SetProgress(double v, string action)
        {
            v = Math.Max(0, Math.Min(1, v));
            var width = Math.Max(0, (PageProgress.ActualWidth > 0 ? PageProgress.ActualWidth : 640) * v);
            BarFill.BeginAnimation(WidthProperty, new DoubleAnimation(width, TimeSpan.FromMilliseconds(380)) { EasingFunction = Ease });
            PercentText.Text = $"{(int)Math.Round(v * 100)}%";
            if (!string.IsNullOrEmpty(action)) ActionText.Text = action;
        }

        void StartShimmer()
        {
            ShimmerMove.BeginAnimation(TranslateTransform.XProperty,
                new DoubleAnimation(-140, 680, TimeSpan.FromMilliseconds(1700)) { RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
        }

        void Finish(bool ok, string title, string lead, string note)
        {
            finished = true;
            failed = !ok;
            DoneTitle.Text = title;
            DoneLead.Text = lead;
            DoneNote.Text = note ?? "";
            DoneNote.Visibility = note == null ? Visibility.Collapsed : Visibility.Visible;
            LaunchCheck.Visibility = ok && !uninstall ? Visibility.Visible : Visibility.Collapsed;
            if (!ok)
            {
                DoneC1.Color = Color.FromRgb(0xFF, 0x9C, 0x90);
                DoneC2.Color = Color.FromRgb(0xF0, 0x64, 0x5A);
                DoneGlow.Fill = new SolidColorBrush(Color.FromRgb(0xF0, 0x64, 0x5A));
                DoneMark.Data = Geometry.Parse("M34,34 L62,62 M62,34 L34,62");
                DoneMark.Stroke = new SolidColorBrush(Color.FromRgb(0x1F, 0x08, 0x06));
            }
            Show(PageDone, steps.Count - 1);
            if (ok) for (int i = 0; i < steps.Count; i++) steps[i].SetState(StepState.Done, true);
            // Значок «выпрыгивает» с лёгкой пружиной, свечение мягко пульсирует
            var pop = new DoubleAnimation(0.4, 1, TimeSpan.FromMilliseconds(620)) { BeginTime = TimeSpan.FromMilliseconds(150), EasingFunction = new BackEase { Amplitude = 0.45, EasingMode = EasingMode.EaseOut } };
            DoneScale.BeginAnimation(ScaleTransform.ScaleXProperty, pop);
            DoneScale.BeginAnimation(ScaleTransform.ScaleYProperty, pop);
            DoneGlow.BeginAnimation(OpacityProperty, new DoubleAnimation(0.25, 0.6, TimeSpan.FromSeconds(1.8)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() });
            var len = 10.0; // в толщинах линии
            DoneMark.StrokeDashArray = new DoubleCollection { len, len };
            DoneMark.BeginAnimation(Shape.StrokeDashOffsetProperty, new DoubleAnimation(len, 0, TimeSpan.FromMilliseconds(520)) { BeginTime = TimeSpan.FromMilliseconds(420), EasingFunction = Ease });
        }

        TaskRow AddTask(string title)
        {
            var row = new TaskRow(title);
            Tasks.Children.Add(row.Root);
            return row;
        }

        void Shake(UIElement e)
        {
            var t = new TranslateTransform();
            e.RenderTransform = t;
            var a = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromMilliseconds(360) };
            foreach (var (x, ms) in new[] { (-8.0, 60), (7.0, 120), (-5.0, 180), (3.0, 240), (0.0, 360) })
                a.KeyFrames.Add(new EasingDoubleKeyFrame(x, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms))));
            t.BeginAnimation(TranslateTransform.XProperty, a);
        }

        // ---------- снимки для CI ----------

        public static void Preview(string dir)
        {
            void Snap(MainWindow w, string name)
            {
                w.UpdateLayout();
                var size = new Size(w.Shell.ActualWidth, w.Shell.ActualHeight);
                var bmp = new RenderTargetBitmap((int)size.Width, (int)size.Height, 96, 96, PixelFormats.Pbgra32);
                bmp.Render(w.Shell);
                var png = new PngBitmapEncoder();
                png.Frames.Add(BitmapFrame.Create(bmp));
                using (var f = File.Create(IOPath.Combine(dir, name + ".png"))) png.Save(f);
            }

            var install = new MainWindow(false) { preview = true, Left = -20000, ShowActivated = false };
            install.Show();
            Snap(install, "1-welcome");
            install.Show(install.PageFolder, 1, animate: false);
            Snap(install, "2-folder");
            install.ProgressTitle.Text = "Установка";
            var a = install.AddTask("Файлы приложения"); a.Set(TaskState.Done, "");
            var b = install.AddTask("Видеодвижок mpv"); b.Set(TaskState.Working, "20 МБ из 47 МБ");
            install.Show(install.PageProgress, 2, animate: false);
            install.UpdateLayout();
            install.BarFill.Width = install.PageProgress.ActualWidth * 0.72;
            install.PercentText.Text = "72%";
            install.ActionText.Text = "Загрузка видеодвижка mpv…";
            Snap(install, "3-progress");
            install.Finish(true, "Готово!", "Suslik Media установлена и готова к работе.", null);
            install.DoneScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            install.DoneScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            install.DoneScale.ScaleX = install.DoneScale.ScaleY = 1;
            install.DoneMark.BeginAnimation(Shape.StrokeDashOffsetProperty, null);
            install.DoneMark.StrokeDashOffset = 0;
            Snap(install, "4-done");
            install.busy = false; install.Close();

            var remove = new MainWindow(true) { preview = true, Left = -20000, ShowActivated = false };
            remove.Show();
            remove.WipeCheck.IsChecked = true;
            Snap(remove, "5-uninstall");
            remove.Close();
        }

        bool preview;

        // ---------- фон ----------

        void StartAmbient()
        {
            var sine = new SineEase { EasingMode = EasingMode.EaseInOut };
            AuraMove.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, -70, TimeSpan.FromSeconds(9)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = sine });
            AuraMove.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, 40, TimeSpan.FromSeconds(11)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = sine });
            Aura.BeginAnimation(OpacityProperty, new DoubleAnimation(0.45, 0.75, TimeSpan.FromSeconds(6)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = sine });
            MockFloat.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(-5, 5, TimeSpan.FromSeconds(4.5)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = sine });
            // Окно появляется плавно и чуть снизу
            var rise = new TranslateTransform(0, 14);
            Shell.RenderTransform = rise;
            Shell.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(380)) { EasingFunction = Ease });
            rise.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(520)) { EasingFunction = Ease });
        }

        // ---------- элементы ----------

        enum StepState { Pending, Active, Done }

        /// <summary>Шаг слева: кружок с номером (активный светится, пройденный — с галочкой), название и линия к следующему.</summary>
        sealed class StepView
        {
            public readonly StackPanel Root = new StackPanel();
            readonly Border circle;
            readonly TextBlock number, title, sub;
            readonly Path check;
            readonly Border line;
            readonly DropShadowEffect glow = new DropShadowEffect { Color = Accent, BlurRadius = 18, ShadowDepth = 0, Opacity = 0 };
            readonly SolidColorBrush edge = new SolidColorBrush(Color.FromRgb(0x3A, 0x46, 0x5C));
            readonly SolidColorBrush fill = new SolidColorBrush(Color.FromArgb(0, 0x5A, 0xB0, 0xFF));
            readonly SolidColorBrush lineBrush = new SolidColorBrush(Color.FromRgb(0x26, 0x31, 0x45));
            readonly SolidColorBrush titleBrush = new SolidColorBrush(Color.FromRgb(0x9A, 0xA6, 0xBA));

            public StepView(int n, string titleText, string subText, bool hasLine)
            {
                var row = new Grid();
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(56) });
                row.ColumnDefinitions.Add(new ColumnDefinition());
                var badge = new Grid { Width = 44, Height = 44, VerticalAlignment = VerticalAlignment.Top, HorizontalAlignment = HorizontalAlignment.Left };
                circle = new Border { CornerRadius = new CornerRadius(22), BorderThickness = new Thickness(2), BorderBrush = edge, Background = fill, Effect = glow };
                number = new TextBlock { Text = n.ToString(), FontSize = 18, Foreground = titleBrush, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                check = new Path
                {
                    Data = Geometry.Parse("M0,6 L5,11 L14,1"), Stroke = new SolidColorBrush(Color.FromRgb(0x06, 0x12, 0x1F)), StrokeThickness = 2.6,
                    StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round,
                    HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Opacity = 0
                };
                badge.Children.Add(circle);
                badge.Children.Add(number);
                badge.Children.Add(check);
                row.Children.Add(badge);

                var text = new StackPanel { Margin = new Thickness(6, 1, 0, 0) };
                title = new TextBlock { Text = titleText, FontSize = 18, FontWeight = FontWeights.SemiBold, Foreground = titleBrush };
                sub = new TextBlock { Text = subText, FontSize = 14.5, Foreground = new SolidColorBrush(Color.FromRgb(0x7D, 0x89, 0x9F)), Margin = new Thickness(0, 5, 0, 0) };
                text.Children.Add(title);
                text.Children.Add(sub);
                Grid.SetColumn(text, 1);
                row.Children.Add(text);
                Root.Children.Add(row);

                line = new Border { Width = 2, Height = hasLine ? 46 : 0, Background = lineBrush, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(21, 4, 0, 4), CornerRadius = new CornerRadius(1) };
                Root.Children.Add(line);
            }

            public void SetState(StepState s, bool animate)
            {
                var d = TimeSpan.FromMilliseconds(animate ? 360 : 0);
                Color edgeTo = s == StepState.Pending ? Color.FromRgb(0x3A, 0x46, 0x5C) : Accent;
                Color fillTo = s == StepState.Done ? Accent : Color.FromArgb(s == StepState.Active ? (byte)0x22 : (byte)0, 0x5A, 0xB0, 0xFF);
                Color titleTo = s == StepState.Pending ? Color.FromRgb(0x9A, 0xA6, 0xBA) : Color.FromRgb(0xEE, 0xF2, 0xF8);
                edge.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(edgeTo, d) { EasingFunction = Ease });
                fill.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(fillTo, d) { EasingFunction = Ease });
                titleBrush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(titleTo, d) { EasingFunction = Ease });
                lineBrush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(s == StepState.Done ? Color.FromArgb(0xAA, 0x5A, 0xB0, 0xFF) : Color.FromRgb(0x26, 0x31, 0x45), d));
                number.BeginAnimation(OpacityProperty, new DoubleAnimation(s == StepState.Done ? 0 : 1, d));
                check.BeginAnimation(OpacityProperty, new DoubleAnimation(s == StepState.Done ? 1 : 0, d));
                number.Foreground = s == StepState.Active ? new SolidColorBrush(Color.FromRgb(0x8C, 0xCB, 0xFF)) : titleBrush;
                if (s == StepState.Active)
                    // Активный шаг мягко «дышит» свечением
                    glow.BeginAnimation(DropShadowEffect.OpacityProperty, new DoubleAnimation(0.45, 0.9, TimeSpan.FromSeconds(1.6)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() });
                else
                    glow.BeginAnimation(DropShadowEffect.OpacityProperty, new DoubleAnimation(s == StepState.Done ? 0.35 : 0, d));
            }
        }

        enum TaskState { Waiting, Working, Done, Failed }

        /// <summary>Строка «что делается»: значок (точка, крутилка, галочка, крестик), название и пояснение.</summary>
        sealed class TaskRow
        {
            public readonly Grid Root = new Grid { Margin = new Thickness(0, 0, 0, 16) };
            readonly Grid icon = new Grid { Width = 26, Height = 26 };
            readonly TextBlock status;
            TaskState? state;

            public TaskRow(string title)
            {
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                Root.ColumnDefinitions.Add(new ColumnDefinition());
                Root.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                Root.Children.Add(icon);
                var t = new TextBlock { Text = title, FontSize = 16, Foreground = new SolidColorBrush(Color.FromRgb(0xEE, 0xF2, 0xF8)), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 0, 0) };
                Grid.SetColumn(t, 1);
                Root.Children.Add(t);
                status = new TextBlock { FontSize = 14, Foreground = new SolidColorBrush(Color.FromRgb(0x7D, 0x89, 0x9F)), VerticalAlignment = VerticalAlignment.Center };
                Grid.SetColumn(status, 2);
                Root.Children.Add(status);
            }

            public void Set(TaskState s, string text)
            {
                status.Text = text;
                if (state == s) return;
                state = s;
                icon.Children.Clear();
                switch (s)
                {
                    case TaskState.Waiting:
                        icon.Children.Add(new Ellipse { Width = 10, Height = 10, Fill = new SolidColorBrush(Color.FromRgb(0x3A, 0x46, 0x5C)) });
                        break;
                    case TaskState.Working:
                        var arc = new Path
                        {
                            Data = Geometry.Parse("M13,2 A11,11 0 0 1 24,13"), Stroke = new SolidColorBrush(Accent), StrokeThickness = 2.6,
                            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, RenderTransformOrigin = new Point(0.5, 0.5),
                            Width = 26, Height = 26
                        };
                        var spin = new RotateTransform();
                        arc.RenderTransform = spin;
                        icon.Children.Add(new Ellipse { Stroke = new SolidColorBrush(Color.FromRgb(0x1E, 0x2A, 0x40)), StrokeThickness = 2.6 });
                        icon.Children.Add(arc);
                        spin.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(0, 360, TimeSpan.FromSeconds(0.9)) { RepeatBehavior = RepeatBehavior.Forever });
                        break;
                    case TaskState.Done:
                        var done = new Grid { RenderTransformOrigin = new Point(0.5, 0.5) };
                        done.Children.Add(new Ellipse { Fill = new SolidColorBrush(Accent), Effect = new DropShadowEffect { Color = Accent, BlurRadius = 12, ShadowDepth = 0, Opacity = 0.6 } });
                        done.Children.Add(new Path
                        {
                            Data = Geometry.Parse("M7.5,13.5 L11.5,17.5 L19,9.5"), Stroke = new SolidColorBrush(Color.FromRgb(0x06, 0x12, 0x1F)), StrokeThickness = 2.4,
                            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round
                        });
                        var pop = new ScaleTransform(0.5, 0.5);
                        done.RenderTransform = pop;
                        icon.Children.Add(done);
                        var a = new DoubleAnimation(0.5, 1, TimeSpan.FromMilliseconds(380)) { EasingFunction = new BackEase { Amplitude = 0.5, EasingMode = EasingMode.EaseOut } };
                        pop.BeginAnimation(ScaleTransform.ScaleXProperty, a);
                        pop.BeginAnimation(ScaleTransform.ScaleYProperty, a);
                        break;
                    case TaskState.Failed:
                        var bad = new Grid();
                        bad.Children.Add(new Ellipse { Fill = new SolidColorBrush(Color.FromRgb(0xF0, 0x64, 0x5A)) });
                        bad.Children.Add(new Path
                        {
                            Data = Geometry.Parse("M9,9 L17,17 M17,9 L9,17"), Stroke = new SolidColorBrush(Color.FromRgb(0x1F, 0x08, 0x06)), StrokeThickness = 2.4,
                            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round
                        });
                        icon.Children.Add(bad);
                        break;
                }
            }
        }

        /// <summary>Сообщения о ходе работы приходят сотнями: в окно — не чаще раза в 50 мс, важные — сразу.</summary>
        sealed class Throttle
        {
            readonly System.Windows.Threading.Dispatcher dispatcher;
            Action pending;
            DateTime last = DateTime.MinValue;
            readonly object gate = new object();

            public Throttle(System.Windows.Threading.Dispatcher d) { dispatcher = d; }

            public void Post(Action a, bool important = false)
            {
                lock (gate)
                {
                    var now = DateTime.UtcNow;
                    if (!important && (now - last).TotalMilliseconds < 50) { pending = a; return; }
                    last = now;
                    pending = null;
                }
                dispatcher.BeginInvoke(a);
            }

            public void Flush()
            {
                Action a;
                lock (gate) { a = pending; pending = null; }
                a?.Invoke();
            }
        }
    }
}
