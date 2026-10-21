using System.Diagnostics;
using Peredatchik.Core;
using QRCoder;

namespace Peredatchik;

public sealed class MainForm : Form
{
    readonly Settings _s = Settings.Load();
    readonly Identity _me;
    readonly PeerStore _store;
    readonly Node _node;
    readonly NotifyIcon _tray = new();
    readonly ListView _peers = new() { View = View.Details, FullRowSelect = true, MultiSelect = false, HideSelection = false, Dock = DockStyle.Fill };
    readonly ComboBox _method = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 380 };
    readonly ProgressBar _bar = new() { Dock = DockStyle.Fill, Height = 18 };
    readonly Label _status = new() { AutoSize = false, Dock = DockStyle.Fill, Text = "Готов", TextAlign = ContentAlignment.MiddleLeft };
    readonly Label _info = new() { AutoSize = false, Dock = DockStyle.Fill, Height = 46 };
    readonly TextBox _log = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = SystemColors.Window };
    readonly List<string> _pending = new();
    readonly System.Windows.Forms.Timer _batch = new() { Interval = 800 };
    readonly System.Windows.Forms.Timer _refresh = new() { Interval = 3000 };
    bool _startHidden, _exiting, _busy;
    PairForm _pairForm;
    string _lastReceived;

    public MainForm(bool startHidden)
    {
        _startHidden = startHidden;
        Directory.CreateDirectory(Program.DataDir);
        _me = Identity.LoadOrCreate(Path.Combine(Program.DataDir, "identity.json"), Environment.MachineName);
        _store = new PeerStore(Path.Combine(Program.DataDir, "peers.json"));
        _node = new Node(_me, _store, new DirSink(_s.InboxDir)) { Bluetooth = BtWin.Open };
        ApplyRelay();

        Text = "Передатчик — телефон ⇄ компьютер";
        Icon = LoadIcon();
        Font = new Font("Segoe UI", 10f);
        MinimumSize = new Size(760, 560); Size = new Size(860, 640);
        StartPosition = FormStartPosition.CenterScreen;
        AllowDrop = true;
        DragEnter += (_, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        DragDrop += (_, e) => QueueFiles(((string[])e.Data.GetData(DataFormats.FileDrop)).Where(File.Exists).ToList());

        BuildUi();
        BuildTray();

        _node.Received += (p, saved) => BeginInvoke(() => OnReceived(p, saved));
        _node.Paired += p => BeginInvoke(() => { Log($"Сопряжено: «{p.Name}»"); _pairForm?.Close(); RefreshPeers(); Balloon("Сопряжено", $"Телефон «{p.Name}» подключён."); });
        _node.Error += m => BeginInvoke(() => Log(m));
        _node.IncomingProgress += (p, n, i, c, d, t) => BeginInvoke(() => ShowProgress($"Приём от «{p.Name}»: {n}", d, t));
        try { _node.Start(Node.DefaultPort, true); _node.Discovery.Changed += () => BeginInvoke(RefreshPeers); }
        catch (Exception e) { Log("Не удалось открыть порт: " + e.Message); }
        _node.StartRelayWaiters();
        _batch.Tick += (_, _) => { _batch.Stop(); List<string> f; lock (_pending) { f = _pending.ToList(); _pending.Clear(); } if (f.Count > 0) _ = SendFiles(f); };
        _refresh.Tick += (_, _) => RefreshPeers(); _refresh.Start();
        RefreshPeers();
        if (!ShellIntegration.IsRegistered()) { try { ShellIntegration.Register(); Log("Добавлено в «Открыть с помощью», контекстное меню и «Отправить»."); } catch (Exception e) { Log("Меню Проводника: " + e.Message); } }
        Log($"Готов к приёму. Порт {_node.Port}. Принятые файлы: {_s.InboxDir}");
    }

    static Icon LoadIcon()
    {
        using var s = typeof(MainForm).Assembly.GetManifestResourceStream("app.ico");
        return s != null ? new Icon(s) : SystemIcons.Application;
    }

    protected override void SetVisibleCore(bool value)
    {
        if (_startHidden && !IsHandleCreated) { CreateHandle(); value = false; _startHidden = false; }
        base.SetVisibleCore(value);
    }

    void BuildUi()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(10) };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 30));
        Controls.Add(root);

        root.Controls.Add(_info);
        var bar = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false, AutoScroll = true };
        bar.Controls.Add(Btn("📱 Сопрячь телефон", (_, _) => ShowPair()));
        bar.Controls.Add(Btn("🔢 Ввести код телефона", (_, _) => EnterPhoneCode()));
        bar.Controls.Add(Btn("📤 Отправить файлы…", (_, _) => PickAndSend()));
        bar.Controls.Add(Btn("📂 Принятые", (_, _) => OpenInbox()));
        bar.Controls.Add(Btn("⚙ Настройки", (_, _) => ShowSettings()));
        root.Controls.Add(bar);

        _peers.Columns.Add("Телефон", 220); _peers.Columns.Add("Связь", 330); _peers.Columns.Add("Bluetooth", 150);
        var menu = new ContextMenuStrip();
        menu.Items.Add("Отправить файлы…", null, (_, _) => PickAndSend());
        menu.Items.Add("Выбрать телефон в Bluetooth…", null, (_, _) => ChooseBt());
        menu.Items.Add("Забыть устройство", null, (_, _) => ForgetPeer());
        _peers.ContextMenuStrip = menu;
        root.Controls.Add(_peers);

        var mrow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
        mrow.Controls.Add(new Label { Text = "Способ связи:", AutoSize = true, Padding = new Padding(0, 6, 0, 0) });
        foreach (Method m in Enum.GetValues<Method>()) _method.Items.Add(MethodInfo.Title(m));
        _method.SelectedIndex = (int)_s.DefaultMethod;
        mrow.Controls.Add(_method);
        root.Controls.Add(mrow);

        var drop = new Label
        {
            Text = "Перетащите сюда фото или файлы — они уйдут на телефон.\nИли в Проводнике: правой кнопкой → «Отправить на телефон» / «Открыть с помощью → Передатчик».",
            Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, BorderStyle = BorderStyle.FixedSingle, BackColor = Color.FromArgb(236, 243, 252), ForeColor = Color.FromArgb(21, 101, 192)
        };
        drop.AllowDrop = true;
        drop.DragEnter += (_, e) => e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        drop.DragDrop += (_, e) => QueueFiles(((string[])e.Data.GetData(DataFormats.FileDrop)).Where(File.Exists).ToList());
        drop.Click += (_, _) => PickAndSend();
        root.Controls.Add(drop);
        root.Controls.Add(_bar);
        root.Controls.Add(_status);
        root.Controls.Add(_log);
    }

    static Button Btn(string t, EventHandler h) { var b = new Button { Text = t, AutoSize = true, Height = 34, Margin = new Padding(0, 0, 8, 0) }; b.Click += h; return b; }

    void BuildTray()
    {
        _tray.Icon = Icon; _tray.Text = "Передатчик — готов к приёму"; _tray.Visible = true;
        var m = new ContextMenuStrip();
        m.Items.Add("Открыть", null, (_, _) => ShowMain());
        m.Items.Add("Отправить файлы на телефон…", null, (_, _) => { ShowMain(); PickAndSend(); });
        m.Items.Add("Сопрячь телефон", null, (_, _) => { ShowMain(); ShowPair(); });
        m.Items.Add("Папка с принятыми", null, (_, _) => OpenInbox());
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Выход", null, (_, _) => { _exiting = true; Close(); });
        _tray.ContextMenuStrip = m;
        _tray.DoubleClick += (_, _) => ShowMain();
        _tray.BalloonTipClicked += (_, _) => { if (_lastReceived != null && File.Exists(_lastReceived)) Process.Start("explorer.exe", $"/select,\"{_lastReceived}\""); else OpenInbox(); };
    }

    public void ShowMain() { Show(); if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal; Activate(); }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; Hide();
            Balloon("Передатчик работает в фоне", "Приём файлов продолжается. Значок — в области уведомлений.");
            return;
        }
        _tray.Visible = false; _node.Dispose();
        base.OnFormClosing(e);
    }

    void Balloon(string title, string text) { _tray.BalloonTipTitle = title; _tray.BalloonTipText = text; _tray.ShowBalloonTip(5000); }

    void Log(string s) { _log.AppendText($"{DateTime.Now:HH:mm:ss}  {s}\r\n"); }

    void RefreshPeers()
    {
        var ips = Discovery.LocalAddresses();
        _info.Text = $"💻 {_me.Name}   ·   адрес: {(ips.Count == 0 ? "нет сети" : string.Join(", ", ips))}   порт {(_node.Discovery != null ? _node.Port : 0)}\n" +
                     $"Принятые файлы: {_s.InboxDir}   ·   если телефон не видит ПК — «Настройки → Разрешить в брандмауэре».";
        var sel = _peers.SelectedItems.Count > 0 ? (string)_peers.SelectedItems[0].Tag : _s.LastPeer;
        _peers.BeginUpdate(); _peers.Items.Clear();
        foreach (var p in _store.All())
        {
            var seen = _node.Discovery?.Get(p.Id);
            var it = new ListViewItem(p.Name) { Tag = p.Id };
            it.SubItems.Add(seen != null ? $"🟢 в сети: {seen.Host}" : "⚪ не найден" + (p.LastHost != null ? $" (был {p.LastHost})" : ""));
            it.SubItems.Add(p.BtAddress ?? "—");
            _peers.Items.Add(it);
            if (p.Id == sel) it.Selected = true;
        }
        foreach (var s in _node.Discovery?.All() ?? new())
            if (_store.Get(s.Id) == null) { var it = new ListViewItem($"➕ {s.Name}") { Tag = null, ForeColor = Color.Gray }; it.SubItems.Add($"не сопряжён · {s.Host} — нажмите «Ввести код телефона»"); _peers.Items.Add(it); }
        if (_peers.SelectedItems.Count == 0 && _peers.Items.Count > 0 && _peers.Items[0].Tag != null) _peers.Items[0].Selected = true;
        _peers.EndUpdate();
    }

    Peer SelectedPeer()
    {
        if (_peers.SelectedItems.Count > 0 && _peers.SelectedItems[0].Tag is string id) return _store.Get(id);
        var all = _store.All();
        return all.FirstOrDefault(p => p.Id == _s.LastPeer) ?? all.FirstOrDefault();
    }

    // ---------- отправка ----------
    public void QueueFiles(List<string> files)
    {
        lock (_pending) _pending.AddRange(files);
        BeginInvoke(() => { _batch.Stop(); _batch.Start(); });
    }

    void PickAndSend()
    {
        using var d = new OpenFileDialog { Multiselect = true, Title = "Что отправить на телефон?", Filter = "Все файлы|*.*|Изображения|*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp;*.heic" };
        if (d.ShowDialog(this) == DialogResult.OK) _ = SendFiles(d.FileNames.ToList());
    }

    async Task SendFiles(List<string> files)
    {
        var p = SelectedPeer();
        if (p == null)
        {
            ShowMain();
            MessageBox.Show(this, "Сначала сопрягите телефон: «Сопрячь телефон» и отсканируйте QR-код в приложении «Передатчик» на телефоне.", "Передатчик");
            return;
        }
        if (_busy) { lock (_pending) _pending.AddRange(files); _batch.Start(); return; }
        _busy = true;
        var m = (Method)_method.SelectedIndex;
        _s.LastPeer = p.Id; _s.Save();
        var size = files.Sum(f => new FileInfo(f).Length);
        Log($"Отправка {files.Count} файл(ов), {size / 1024.0 / 1024:0.0} МБ → «{p.Name}» ({MethodInfo.Title(m)})");
        _status.Text = "Подключение…";
        try
        {
            var srcs = files.Select(FileSource.Of).ToList();
            var used = await Task.Run(() =>
            {
                if (m is Method.Auto or Method.Wifi) for (int i = 0; i < 30 && _node.Discovery?.Get(p.Id) == null && p.LastHost == null; i++) Thread.Sleep(100);
                string host = null; int port = 0;
                if (m == Method.Manual && !string.IsNullOrWhiteSpace(_s.Manual)) { var sp = _s.Manual.Trim().Split(':'); host = sp[0]; port = sp.Length > 1 ? int.Parse(sp[1]) : Node.DefaultPort; }
                long lastTick = 0;
                return _node.Send(p, srcs, m, host, port, (n, i, c, d, t) =>
                {
                    var now = Environment.TickCount64; if (now - lastTick < 150 && d < t) return; lastTick = now;
                    BeginInvoke(() => ShowProgress($"Отправка «{p.Name}»: {(c > 1 ? $"{i + 1}/{c} " : "")}{n}", d, t));
                });
            });
            ShowProgress($"✓ Отправлено {files.Count} файл(ов) через {used}", 1, 1);
            Log($"Отправлено через {used}");
            if (!Visible) Balloon("Отправлено на телефон", $"{files.Count} файл(ов) · {used}");
        }
        catch (Exception e)
        {
            _status.Text = "✗ " + e.Message.Split('\n')[0];
            Log("Ошибка отправки: " + e.Message);
            ShowMain();
            MessageBox.Show(this, e.Message + "\n\nПроверьте, что «Передатчик» открыт на телефоне, или выберите другой способ связи.", "Не отправлено", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { _busy = false; }
    }

    void ShowProgress(string text, long done, long total)
    {
        _status.Text = text + (total > 0 && done < total ? $" — {done * 100 / total}%" : "");
        _bar.Value = total == 0 ? 100 : (int)Math.Min(100, done * 100 / total);
    }

    void OnReceived(Peer p, List<string> saved)
    {
        _lastReceived = saved.LastOrDefault();
        ShowProgress($"✓ Принято от «{p.Name}»: {saved.Count} файл(ов)", 1, 1);
        foreach (var f in saved) Log($"Принято: {f}");
        Balloon($"Принято от «{p.Name}»", $"{saved.Count} файл(ов): {string.Join(", ", saved.Select(Path.GetFileName).Take(3))}\nНажмите, чтобы открыть папку.");
        if (_s.OpenFolderAfterReceive && _lastReceived != null) Process.Start("explorer.exe", $"/select,\"{_lastReceived}\"");
    }

    void OpenInbox() { Directory.CreateDirectory(_s.InboxDir); Process.Start("explorer.exe", $"\"{_s.InboxDir}\""); }

    // ---------- сопряжение ----------
    void ShowPair()
    {
        var pairing = Pairing.NewRandom(TimeSpan.FromMinutes(5));
        _node.Pairing = pairing;
        _pairForm = new PairForm(_node.PairingUri(pairing.Code), pairing, Discovery.LocalAddresses(), _node.Port, Icon);
        _pairForm.FormClosed += (_, _) => { if (_node.Pairing == pairing && pairing.Active) _node.Pairing = null; _pairForm = null; };
        _pairForm.Show(this);
    }

    void EnterPhoneCode()
    {
        var near = (_node.Discovery?.All() ?? new()).ToList();
        using var f = new Form { Text = "Сопряжение с телефоном по его коду", Size = new Size(520, 300), StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, Font = Font };
        var t = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Padding = new Padding(12) };
        var cb = new ComboBox { DropDownStyle = ComboBoxStyle.DropDown, Width = 320 };
        foreach (var s in near) cb.Items.Add($"{s.Host}:{s.Port}  ({s.Name})");
        if (cb.Items.Count > 0) cb.SelectedIndex = 0;
        var code = new TextBox { Width = 120, MaxLength = 7 };
        t.Controls.Add(new Label { Text = "Телефон (IP:порт):", AutoSize = true }); t.Controls.Add(cb);
        t.Controls.Add(new Label { Text = "Код с телефона:", AutoSize = true }); t.Controls.Add(code);
        t.Controls.Add(new Label { Text = "На телефоне: «Передатчик» → «Показать мой код».", AutoSize = true }); t.SetColumnSpan(t.Controls[^1], 2);
        var ok = new Button { Text = "Сопрячь", DialogResult = DialogResult.OK, AutoSize = true };
        t.Controls.Add(ok); f.AcceptButton = ok; f.Controls.Add(t);
        if (f.ShowDialog(this) != DialogResult.OK) return;
        var addr = cb.Text.Split(' ')[0];
        var parts = addr.Split(':');
        var c = code.Text.Replace(" ", "");
        Task.Run(() =>
        {
            try
            {
                var p = _node.Pair(Link.Tcp(parts[0], parts.Length > 1 ? int.Parse(parts[1]) : Node.DefaultPort, 4000), c, null);
                BeginInvoke(() => MessageBox.Show(this, $"Сопряжено с «{p.Name}».", "Передатчик"));
            }
            catch (Exception e) { BeginInvoke(() => MessageBox.Show(this, "Не удалось: " + e.Message, "Передатчик", MessageBoxButtons.OK, MessageBoxIcon.Warning)); }
        });
    }

    void ChooseBt()
    {
        var p = SelectedPeer(); if (p == null) return;
        var devs = BtWin.Paired();
        if (devs.Count == 0) { MessageBox.Show(this, "Сначала сопрягите телефон в «Параметры Windows → Bluetooth».", "Bluetooth"); return; }
        using var f = new Form { Text = "Какой это телефон в Bluetooth?", Size = new Size(420, 320), StartPosition = FormStartPosition.CenterParent, Font = Font };
        var lb = new ListBox { Dock = DockStyle.Fill };
        foreach (var d in devs) lb.Items.Add($"{d.Name}  ({d.Mac})");
        var ok = new Button { Text = "Выбрать", Dock = DockStyle.Bottom, DialogResult = DialogResult.OK, Height = 36 };
        f.Controls.Add(lb); f.Controls.Add(ok);
        if (f.ShowDialog(this) == DialogResult.OK && lb.SelectedIndex >= 0) { p.BtAddress = devs[lb.SelectedIndex].Mac; _store.Put(p); RefreshPeers(); }
    }

    void ForgetPeer()
    {
        var p = SelectedPeer(); if (p == null) return;
        if (MessageBox.Show(this, $"Забыть «{p.Name}»? Для повторной связи понадобится новое сопряжение.", "Передатчик", MessageBoxButtons.YesNo) == DialogResult.Yes) { _store.Remove(p.Id); RefreshPeers(); }
    }

    void ApplyRelay()
    {
        var r = (_s.Relay ?? "").Trim();
        _node.StopRelayWaiters();
        if (r.Length == 0) { _node.RelayHost = null; return; }
        var i = r.LastIndexOf(':');
        _node.RelayHost = i > 0 ? r[..i] : r;
        _node.RelayPort = i > 0 && int.TryParse(r[(i + 1)..], out var pp) ? pp : 47233;
    }

    void ShowSettings()
    {
        using var f = new SettingsForm(_s, _me, Font);
        if (f.ShowDialog(this) != DialogResult.OK) return;
        _s.Save(); _me.Save(Path.Combine(Program.DataDir, "identity.json"));
        ApplyRelay(); _node.StartRelayWaiters();
        _method.SelectedIndex = (int)_s.DefaultMethod;
        MessageBox.Show(this, "Сохранено. Новая папка для приёма начнёт работать после перезапуска программы.", "Передатчик");
        RefreshPeers();
    }
}

sealed class PairForm : Form
{
    readonly System.Windows.Forms.Timer _t = new() { Interval = 1000 };
    public PairForm(string uri, Pairing p, List<string> ips, int port, Icon icon)
    {
        Text = "Сопряжение с телефоном"; Icon = icon; Font = new Font("Segoe UI", 10f);
        Size = new Size(460, 640); StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false;
        var qr = new QRCodeGenerator().CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(qr).GetGraphic(8);
        var pic = new PictureBox { Image = Image.FromStream(new MemoryStream(png)), SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Top, Height = 360 };
        var code = new Label { Text = p.Code[..3] + " " + p.Code[3..], Font = new Font("Segoe UI", 30f, FontStyle.Bold), Dock = DockStyle.Top, Height = 64, TextAlign = ContentAlignment.MiddleCenter };
        var help = new Label
        {
            Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopCenter,
            Text = "На телефоне откройте «Передатчик» → «Сопрячь с ПК по QR-коду»\nили «по 6-значному коду» и введите код выше.\n\n" +
                   $"Адрес ПК: {string.Join(", ", ips)}  порт {port}"
        };
        var left = new Label { Dock = DockStyle.Bottom, Height = 28, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.Gray };
        Controls.Add(help); Controls.Add(left); Controls.Add(code); Controls.Add(pic);
        _t.Tick += (_, _) => { left.Text = p.Active ? $"Код действует ещё {p.SecondsLeft / 60}:{p.SecondsLeft % 60:00}" : "Код истёк — откройте окно заново"; };
        _t.Start();
        FormClosed += (_, _) => _t.Dispose();
    }
}

sealed class SettingsForm : Form
{
    public SettingsForm(Settings s, Identity me, Font font)
    {
        Text = "Настройки"; Font = font; Size = new Size(640, 560); StartPosition = FormStartPosition.CenterParent; FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false;
        var t = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Padding = new Padding(12), AutoScroll = true };
        t.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220)); t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        TextBox Tb(string v) => new() { Text = v, Dock = DockStyle.Fill };
        var name = Tb(me.Name); var inbox = Tb(s.InboxDir); var relay = Tb(s.Relay); var manual = Tb(s.Manual);
        var method = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        foreach (Method m in Enum.GetValues<Method>()) method.Items.Add(MethodInfo.Title(m));
        method.SelectedIndex = (int)s.DefaultMethod;
        var openAfter = new CheckBox { Text = "Открывать папку после приёма", Checked = s.OpenFolderAfterReceive, AutoSize = true };
        var auto = new CheckBox { Text = "Запускать вместе с Windows (в трее)", Checked = ShellIntegration.Autostart, AutoSize = true };
        void Row(string l, Control c) { t.Controls.Add(new Label { Text = l, AutoSize = true, Padding = new Padding(0, 6, 0, 0) }); t.Controls.Add(c); }
        Row("Имя компьютера:", name);
        Row("Папка для принятых:", inbox);
        Row("Способ по умолчанию:", method);
        Row("Ретранслятор (интернет):", relay);
        Row("Телефон вручную (IP:порт):", manual);
        t.Controls.Add(new Label()); t.Controls.Add(openAfter);
        t.Controls.Add(new Label()); t.Controls.Add(auto);
        var fw = new Button { Text = "Разрешить в брандмауэре Windows…", AutoSize = true };
        fw.Click += (_, _) => { try { ShellIntegration.AddFirewallRules(); } catch (Exception e) { MessageBox.Show(e.Message); } };
        var adb = new Button { Text = "Настроить USB через ADB", AutoSize = true };
        adb.Click += (_, _) => MessageBox.Show(this, Adb.Setup(), "USB (ADB)");
        var reg = new Button { Text = "Встроить в меню Проводника", AutoSize = true };
        reg.Click += (_, _) => { ShellIntegration.Register(); MessageBox.Show(this, "Готово: «Отправить на телефон», «Открыть с помощью» и «Отправить → Телефон».", "Передатчик"); };
        var unreg = new Button { Text = "Убрать из меню Проводника", AutoSize = true };
        unreg.Click += (_, _) => { ShellIntegration.Unregister(); MessageBox.Show(this, "Убрано.", "Передатчик"); };
        var btns = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        btns.Controls.AddRange(new Control[] { fw, adb, reg, unreg });
        t.Controls.Add(btns); t.SetColumnSpan(btns, 2);
        var hint = new Label
        {
            AutoSize = true, MaximumSize = new Size(590, 0),
            Text = "Способы связи: Wi-Fi — ПК и телефон в одной сети; точка доступа — ПК подключается к Wi-Fi телефона; USB-модем — кабель + «USB-модем» на телефоне " +
                   "(тоже работает как Wi-Fi-режим); USB через ADB — нужен adb.exe и отладка по USB; Bluetooth — выберите телефон (ПКМ по устройству); " +
                   "ретранслятор — свой сервер с «peredatchik-host relay 47233», данные шифруются сквозным образом."
        };
        t.Controls.Add(hint); t.SetColumnSpan(hint, 2);
        var ok = new Button { Text = "Сохранить", DialogResult = DialogResult.OK, AutoSize = true };
        t.Controls.Add(ok); AcceptButton = ok;
        Controls.Add(t);
        FormClosing += (_, e) =>
        {
            if (DialogResult != DialogResult.OK) return;
            if (name.Text.Trim().Length > 0) me.Name = name.Text.Trim();
            s.InboxDir = inbox.Text.Trim(); s.DefaultMethod = (Method)method.SelectedIndex;
            s.Relay = relay.Text.Trim(); s.Manual = manual.Text.Trim(); s.OpenFolderAfterReceive = openAfter.Checked;
            try { ShellIntegration.Autostart = auto.Checked; } catch { }
        };
    }
}
