using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Peredatchik.Core;

/// Узел «Передатчика» — равноправный приёмник и отправитель (совместим с Node.java).
public sealed class Node : IDisposable
{
    public const int DefaultPort = 47232;
    public readonly Identity Me; public readonly PeerStore Store; private readonly ISink _sink;
    public volatile Pairing Pairing;
    public Func<Peer, Link> Bluetooth;
    public string RelayHost; public int RelayPort = 47233;
    public int ConnectTimeoutMs = 2500;
    public Discovery Discovery;
    private TcpListener _l; private volatile bool _run;
    private readonly ConcurrentDictionary<string, CancellationTokenSource> _relay = new();

    public event Action<Peer, List<string>> Received;
    public event Action<Peer> Paired;
    public event Action<Peer, string, int, int, long, long> IncomingProgress;
    public event Action<string> Error;

    public Node(Identity me, PeerStore store, ISink sink) { Me = me; Store = store; _sink = sink; }
    public int Port => ((IPEndPoint)_l.LocalEndpoint).Port;

    public void Start(int port, bool discover)
    {
        _run = true;
        try { _l = new TcpListener(IPAddress.Any, port); _l.Start(); }
        catch (SocketException) { _l = new TcpListener(IPAddress.Any, 0); _l.Start(); }
        new Thread(() =>
        {
            while (_run)
            {
                try { var c = _l.AcceptTcpClient(); var l = Link.Of(c); new Thread(() => HandleIncoming(l)) { IsBackground = true }.Start(); }
                catch { if (!_run) return; }
            }
        }) { IsBackground = true, Name = "accept" }.Start();
        if (discover) { Discovery = new Discovery(Me, Port); try { Discovery.Start(); } catch (Exception e) { Error?.Invoke("Поиск в сети недоступен: " + e.Message); } }
    }

    public void Dispose() { _run = false; try { _l?.Stop(); } catch { } Discovery?.Dispose(); StopRelayWaiters(); }

    public void HandleIncoming(Link l)
    {
        SecureChannel ch = null;
        try
        {
            ch = SecureChannel.Server(l.In, l.Out, l, Me, Store, Pairing);
            var p = ch.Peer;
            if (l.Description != null && Regex.IsMatch(l.Description, "^[0-9.]+$") && l.Description != "127.0.0.1") { p.LastHost = l.Description; Store.Put(p); }
            if (ch.NewlyPaired) { Paired?.Invoke(p); StartRelayWaiter(p); }
            var saved = Transfer.Receive(ch, _sink, (n, i, c, d, t) => IncomingProgress?.Invoke(p, n, i, c, d, t));
            if (saved.Count > 0) Received?.Invoke(p, saved);
        }
        catch (Exception e) { Error?.Invoke("Приём: " + e.Message); if (Environment.GetEnvironmentVariable("PRD_DEBUG") != null) Console.Error.WriteLine(e); }
        finally { if (ch != null) ch.Dispose(); else l.Dispose(); }
    }

    record Attempt(string Name, Func<Link> Open);

    List<Attempt> Attempts(Peer p, Method m, string host, int port)
    {
        var a = new List<Attempt>();
        var seen = Discovery?.Get(p.Id);
        bool wifi = m is Method.Auto or Method.Wifi;
        if (wifi && seen != null) a.Add(new("Wi-Fi " + seen.Host, () => Link.Tcp(seen.Host, seen.Port, ConnectTimeoutMs)));
        if (wifi && p.LastHost != null && p.LastHost != seen?.Host) a.Add(new("Wi-Fi " + p.LastHost, () => Link.Tcp(p.LastHost, p.LastPort > 0 ? p.LastPort : DefaultPort, ConnectTimeoutMs)));
        if (m is Method.Auto or Method.UsbAdb) a.Add(new("USB (adb forward)", () => Link.Tcp("127.0.0.1", DefaultPort + 10, 800)));
        if (m is Method.Auto or Method.Bluetooth && Bluetooth != null && p.BtAddress != null) a.Add(new("Bluetooth", () => Bluetooth(p)));
        if (m is Method.Auto or Method.Relay && !string.IsNullOrEmpty(RelayHost)) a.Add(new("интернет-ретранслятор", () => Link.Relay(RelayHost, RelayPort, Peer.RelayRoom(Me.Pub, p.Pub), "c", 8000)));
        if (m == Method.Manual && host != null) a.Add(new("вручную " + host, () => Link.Tcp(host, port > 0 ? port : DefaultPort, 5000)));
        return a;
    }

    public SecureChannel Connect(Peer p, Method m, string host, int port, out string used)
    {
        var list = Attempts(p, m, host, port);
        if (list.Count == 0) throw new IOException($"нет доступного способа связи для «{p.Name}» ({MethodInfo.Title(m)})");
        var errs = "";
        foreach (var a in list)
        {
            Link l = null;
            try
            {
                l = a.Open();
                var ch = SecureChannel.Client(l.In, l.Out, l, Me, Store, null, null);
                used = a.Name; return ch;
            }
            catch (ProtocolException) { throw; }
            catch (Exception e) { l?.Dispose(); errs += $"\n• {a.Name}: {e.Message}"; }
        }
        throw new IOException($"«{p.Name}» недоступно:{errs}");
    }

    public string Send(Peer p, List<FileSource> files, Method m, string host, int port, Progress pr)
    {
        var shas = files.Select(Transfer.Sha256Hex).ToList();
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++)
        {
            SecureChannel ch = null;
            try
            {
                ch = Connect(p, m, host, port, out var used);
                Transfer.Send(ch, files, shas, pr);
                return used;
            }
            catch (ProtocolException) { throw; }
            catch (Exception e) when (ch != null) { last = e; Thread.Sleep(1000); }
            finally { ch?.Dispose(); }
        }
        throw last ?? new IOException("не удалось");
    }

    public Peer Pair(Link l, string code, string expectId)
    {
        using var ch = SecureChannel.Client(l.In, l.Out, l, Me, Store, code, expectId);
        var p = ch.Peer;
        var d = l.Description ?? "";
        var mm = Regex.Match(d, @"^([0-9.]+):(\d+)$");
        if (mm.Success && mm.Groups[1].Value != "127.0.0.1") { p.LastHost = mm.Groups[1].Value; p.LastPort = int.Parse(mm.Groups[2].Value); }
        Store.Put(p);
        ch.SendJson(new JsonObject { ["t"] = "bye" });
        Paired?.Invoke(p);
        StartRelayWaiter(p);
        return p;
    }

    public void StartRelayWaiters() { foreach (var p in Store.All()) StartRelayWaiter(p); }

    public void StartRelayWaiter(Peer p)
    {
        if (string.IsNullOrEmpty(RelayHost) || _relay.ContainsKey(p.Id)) return;
        var cts = new CancellationTokenSource();
        _relay[p.Id] = cts;
        new Thread(() =>
        {
            while (_run && !cts.IsCancellationRequested && !string.IsNullOrEmpty(RelayHost))
            {
                try
                {
                    var l = Link.Relay(RelayHost, RelayPort, Peer.RelayRoom(Me.Pub, p.Pub), "s", 0);
                    new Thread(() => HandleIncoming(l)) { IsBackground = true }.Start();
                }
                catch { cts.Token.WaitHandle.WaitOne(15_000); }
            }
            _relay.TryRemove(p.Id, out _);
        }) { IsBackground = true }.Start();
    }

    public void StopRelayWaiters() { foreach (var c in _relay.Values) c.Cancel(); _relay.Clear(); }

    public string PairingUri(string code) => $"prd1|{string.Join(",", Discovery.LocalAddresses())}|{Port}|{code}|{Me.Id}|{Me.Name}";
}
