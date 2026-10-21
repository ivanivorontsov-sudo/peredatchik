using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace Peredatchik.Core;

public sealed class Link : IDisposable
{
    public Stream In, Out; public string Description; public IDisposable Owner;
    public void Dispose() { try { Owner?.Dispose(); } catch { } }

    public static Link Tcp(string host, int port, int timeoutMs)
    {
        var c = new TcpClient { NoDelay = true };
        try
        {
            if (!c.ConnectAsync(host, port).Wait(timeoutMs)) throw new IOException("нет ответа");
        }
        catch (AggregateException e) { c.Dispose(); throw new IOException(e.InnerException?.Message ?? e.Message); }
        catch { c.Dispose(); throw; }
        c.ReceiveTimeout = 60_000;
        var s = c.GetStream();
        return new Link { In = s, Out = s, Owner = c, Description = host + ":" + port };
    }

    public static Link Of(TcpClient c)
    {
        c.NoDelay = true; c.ReceiveTimeout = 60_000;
        var s = c.GetStream();
        return new Link { In = s, Out = s, Owner = c, Description = ((IPEndPoint)c.Client.RemoteEndPoint).Address.MapToIPv4().ToString() };
    }

    public static Link Relay(string host, int port, string room, string role, int waitMs)
    {
        var l = Tcp(host, port, 8000);
        try
        {
            var b = Encoding.ASCII.GetBytes($"PRDRELAY1 {room} {role}\n");
            l.Out.Write(b); l.Out.Flush();
            ((TcpClient)l.Owner).ReceiveTimeout = waitMs;
            var line = ReadLine(l.In);
            if (line != "OK") throw new IOException("ретранслятор: " + line);
            ((TcpClient)l.Owner).ReceiveTimeout = 60_000;
            l.Description = "ретранслятор " + host;
            return l;
        }
        catch { l.Dispose(); throw; }
    }

    internal static string ReadLine(Stream s)
    {
        var sb = new StringBuilder();
        while (true)
        {
            int b = s.ReadByte();
            if (b < 0) throw new EndOfStreamException("соединение закрыто");
            if (b == '\n') return sb.ToString().Trim();
            if (sb.Length > 200) throw new IOException("слишком длинная строка");
            sb.Append((char)b);
        }
    }
}

public sealed class Discovery : IDisposable
{
    public const int Port = 47231;
    public sealed record Seen(string Id, string Name, string Host, int Port, DateTime Time) { public bool Fresh => DateTime.UtcNow - Time < TimeSpan.FromSeconds(10); }

    private readonly Identity _me; private readonly int _tcpPort, _udpPort;
    private readonly ConcurrentDictionary<string, Seen> _seen = new();
    private UdpClient _udp; private volatile bool _run;
    public event Action Changed;

    public Discovery(Identity me, int tcpPort, int udpPort = Port) { _me = me; _tcpPort = tcpPort; _udpPort = udpPort; }
    public Seen Get(string id) => _seen.TryGetValue(id, out var s) && s.Fresh ? s : null;
    public List<Seen> All() => _seen.Values.Where(s => s.Fresh).ToList();

    public void Start()
    {
        if (_run) return;
        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.EnableBroadcast = true;
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, _udpPort));
        _run = true;
        new Thread(Rx) { IsBackground = true, Name = "disc-rx" }.Start();
        new Thread(Tx) { IsBackground = true, Name = "disc-tx" }.Start();
    }

    public void Dispose() { _run = false; _udp?.Dispose(); }

    public static List<IPAddress> Broadcasts()
    {
        var l = new List<IPAddress>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var u in ni.GetIPProperties().UnicastAddresses)
            {
                if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                var a = u.Address.GetAddressBytes(); var m = u.IPv4Mask.GetAddressBytes();
                for (int i = 0; i < 4; i++) a[i] = (byte)(a[i] | ~m[i]);
                l.Add(new IPAddress(a));
            }
        }
        l.Add(IPAddress.Broadcast);
        return l;
    }

    public static List<string> LocalAddresses()
    {
        var l = new List<string>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var u in ni.GetIPProperties().UnicastAddresses)
                if (u.Address.AddressFamily == AddressFamily.InterNetwork && !u.Address.ToString().StartsWith("169.254")) l.Add(u.Address.ToString());
        }
        return l;
    }

    void Tx()
    {
        while (_run)
        {
            var p = J.Bytes(new JsonObject { ["app"] = "peredatchik", ["v"] = 1, ["id"] = _me.Id, ["name"] = _me.Name, ["port"] = _tcpPort });
            foreach (var a in Broadcasts()) try { _udp.Send(p, p.Length, new IPEndPoint(a, _udpPort)); } catch { }
            Thread.Sleep(2000);
        }
    }

    void Rx()
    {
        while (_run)
        {
            try
            {
                var ep = new IPEndPoint(IPAddress.Any, 0);
                var b = _udp.Receive(ref ep);
                var m = J.Parse(b, 0);
                if (J.S(m, "app") != "peredatchik") continue;
                var id = J.S(m, "id");
                if (id == null || id == _me.Id) continue;
                bool isNew = !_seen.ContainsKey(id);
                _seen[id] = new Seen(id, J.S(m, "name"), ep.Address.MapToIPv4().ToString(), (int)J.L(m, "port"), DateTime.UtcNow);
                if (isNew) Changed?.Invoke();
            }
            catch { if (!_run) return; }
        }
    }
}

/// Ретранслятор для размещения на сервере (VPS): видит только зашифрованные байты.
public sealed class RelayServer : IDisposable
{
    private readonly TcpListener _l;
    private readonly ConcurrentDictionary<string, TcpClient> _wait = new();
    private volatile bool _run = true;
    public int Port => ((IPEndPoint)_l.LocalEndpoint).Port;

    public RelayServer(int port)
    {
        _l = new TcpListener(IPAddress.Any, port); _l.Start();
        new Thread(() => { while (_run) { try { var c = _l.AcceptTcpClient(); new Thread(() => Handle(c)) { IsBackground = true }.Start(); } catch { if (!_run) return; } } }) { IsBackground = true }.Start();
    }

    void Handle(TcpClient c)
    {
        try
        {
            c.ReceiveTimeout = 10_000;
            var p = Link.ReadLine(c.GetStream()).Split(' ');
            c.ReceiveTimeout = 0;
            if (p.Length != 3 || p[0] != "PRDRELAY1" || !System.Text.RegularExpressions.Regex.IsMatch(p[1], "^[0-9a-f]{32}$")) { c.Dispose(); return; }
            if (p[2] == "s") { if (_wait.TryGetValue(p[1], out var old)) old.Dispose(); _wait[p[1]] = c; }
            else
            {
                if (!_wait.TryRemove(p[1], out var peer)) { var e = Encoding.UTF8.GetBytes("ERR получатель не в сети\n"); c.GetStream().Write(e); c.Dispose(); return; }
                var ok = "OK\n"u8.ToArray();
                peer.GetStream().Write(ok); c.GetStream().Write(ok);
                Pipe(c, peer); Pipe(peer, c);
            }
        }
        catch { c.Dispose(); }
    }

    static void Pipe(TcpClient a, TcpClient b) => new Thread(() =>
    {
        try { a.GetStream().CopyTo(b.GetStream()); } catch { }
        a.Dispose(); b.Dispose();
    }) { IsBackground = true }.Start();

    public void Dispose() { _run = false; _l.Stop(); }
}
