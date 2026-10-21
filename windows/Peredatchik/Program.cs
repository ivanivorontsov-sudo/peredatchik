using System.IO.Pipes;
using System.Text;

namespace Peredatchik;

static class Program
{
    const string PipeName = "Peredatchik.Pipe.v1";
    public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Peredatchik");

    [STAThread]
    static void Main(string[] args)
    {
        // Команды установщика/удаления
        if (args.Length > 0 && args[0] == "--register") { ShellIntegration.Register(); return; }
        if (args.Length > 0 && args[0] == "--unregister") { ShellIntegration.Unregister(); return; }

        var files = args.Where(a => !a.StartsWith("--")).Where(File.Exists).ToList();
        bool tray = args.Contains("--tray");
        using var mutex = new Mutex(true, "Peredatchik.Single.v1", out bool first);
        if (!first)
        {
            // Уже запущен: передать файлы (или просьбу показать окно) работающему экземпляру
            try
            {
                using var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                c.Connect(3000);
                var b = Encoding.UTF8.GetBytes(files.Count > 0 ? string.Join("\n", files) : "--show");
                c.Write(b); c.Flush();
            }
            catch { MessageBox.Show("«Передатчик» уже запущен, но не отвечает.", "Передатчик"); }
            return;
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        MainForm form;
        try { form = new MainForm(startHidden: tray && files.Count == 0); }
        catch (Exception e)
        {
            MessageBox.Show("Не удалось запустить «Передатчик»:\n" + e.Message, "Передатчик", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }
        new Thread(() => PipeServer(form)) { IsBackground = true }.Start();
        if (files.Count > 0) form.QueueFiles(files);
        Application.Run(form);
    }

    static void PipeServer(MainForm form)
    {
        while (true)
        {
            try
            {
                using var s = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte);
                s.WaitForConnection();
                using var r = new StreamReader(s, Encoding.UTF8);
                var text = r.ReadToEnd();
                if (text == "--show") form.BeginInvoke(form.ShowMain);
                else form.QueueFiles(text.Split('\n', StringSplitOptions.RemoveEmptyEntries).ToList());
            }
            catch { Thread.Sleep(500); }
        }
    }
}
