using Peredatchik.Core;

// Консольная версия: приём/отправка без окна (Linux/Windows), ретранслятор для VPS и самопроверка.
//  serve <state> <port> <code> <inbox>     pair <state> <host> <port> <code>
//  send <state> <peerId|first> <host> <port> files...     relay [port]     selftest
var a = args;
if (a.Length == 0) { Console.WriteLine("peredatchik-host serve|pair|send|relay|selftest"); return 1; }
switch (a[0])
{
    case "relay":
        using (var r = new RelayServer(a.Length > 1 ? int.Parse(a[1]) : 47233)) { Console.WriteLine($"RELAY READY {r.Port}"); Thread.Sleep(Timeout.Infinite); }
        return 0;
    case "selftest": return SelfTest.Run();
}
Directory.CreateDirectory(a[1]);
var me = Identity.LoadOrCreate(Path.Combine(a[1], "identity.json"), "NET-" + Path.GetFileName(a[1].TrimEnd('/')));
var store = new PeerStore(Path.Combine(a[1], "peers.json"));
switch (a[0])
{
    case "serve":
    {
        var n = new Node(me, store, new DirSink(a[4])) { Pairing = new Pairing(a[3], TimeSpan.FromMinutes(10)) };
        n.Received += (p, s) => Console.WriteLine($"RECEIVED {s.Count} [{string.Join(", ", s)}]");
        n.Paired += p => Console.WriteLine($"PAIRED {p.Name} {p.Id}");
        n.Error += m => Console.WriteLine("ERROR " + m);
        n.Start(int.Parse(a[2]), a.Contains("--discover"));
        Console.WriteLine($"READY {me.Id} {n.Port}");
        Thread.Sleep(Timeout.Infinite);
        return 0;
    }
    case "pair":
    {
        var n = new Node(me, store, new DirSink(Path.Combine(a[1], "in")));
        var p = n.Pair(Link.Tcp(a[2], int.Parse(a[3]), 5000), a[4], null);
        Console.WriteLine($"PAIRED {p.Name} {p.Id}");
        return 0;
    }
    case "send":
    {
        var n = new Node(me, store, new DirSink(Path.Combine(a[1], "in")));
        var p = a[2] == "first" ? store.All()[0] : store.Get(a[2]);
        var used = n.Send(p, a.Skip(5).Select(FileSource.Of).ToList(), Method.Manual, a[3], int.Parse(a[4]), null);
        Console.WriteLine("SENT via " + used);
        return 0;
    }
}
return 1;

static class SelfTest
{
    static int fails;
    static void Check(bool c, string what) { Console.WriteLine((c ? "OK   " : "FAIL ") + what); if (!c) fails++; }

    public static int Run()
    {
        var tmp = Directory.CreateTempSubdirectory("prdnet").FullName;
        // HKDF RFC 5869, случай 1
        var ikm = Enumerable.Repeat((byte)0x0b, 22).ToArray();
        var salt = Enumerable.Range(0, 13).Select(i => (byte)i).ToArray();
        var info = Enumerable.Range(0, 10).Select(i => (byte)(0xf0 + i)).ToArray();
        Check(Crypto.Hex(Crypto.Hkdf(ikm, salt, info, 42)) == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", "HKDF RFC5869");
        var i1 = Identity.Create("a"); var i2 = Identity.Create("b");
        Check(Crypto.Eq(Crypto.Ecdh(i1.Key.K, i2.Pub), Crypto.Ecdh(i2.Key.K, i1.Pub)), "ECDH симметричен");
        i1.Save(Path.Combine(tmp, "id.json"));
        Check(Identity.LoadOrCreate(Path.Combine(tmp, "id.json"), "x").Id == i1.Id, "ключ сохраняется");
        Check(Mime.SafeName("../../evil.txt") == "evil.txt" && Mime.SafeName("..") == "file", "безопасные имена");

        var got = new List<string>();
        var pc = new Node(Identity.Create("ПК"), new PeerStore(null), new DirSink(Path.Combine(tmp, "pc"))) { Pairing = new Pairing("123456", TimeSpan.FromMinutes(1)) };
        pc.Received += (_, s) => { lock (got) got.AddRange(s); }; pc.Error += m => Console.WriteLine("  pc: " + m);
        var ph = new Node(Identity.Create("Тел"), new PeerStore(null), new DirSink(Path.Combine(tmp, "ph")));
        pc.Start(0, false); ph.Start(0, false);
        try { ph.Pair(Link.Tcp("127.0.0.1", pc.Port, 2000), "000000", null); Check(false, "неверный код отклонён"); }
        catch (ProtocolException e) { Check(e.Message.Contains("неверный"), "неверный код отклонён"); }
        var peer = ph.Pair(Link.Tcp("127.0.0.1", pc.Port, 2000), "123456", pc.Me.Id);
        Check(peer.Id == pc.Me.Id, "сопряжение по коду");
        var f = Path.Combine(tmp, "снимок.png"); var data = Crypto.Random(2_345_678); File.WriteAllBytes(f, data);
        var f2 = Path.Combine(tmp, "z.txt"); File.WriteAllText(f2, "");
        ph.Send(peer, new() { FileSource.Of(f), FileSource.Of(f2) }, Method.Manual, "127.0.0.1", pc.Port, null);
        for (int k = 0; k < 100 && got.Count < 2; k++) Thread.Sleep(50);
        Check(got.Count == 2 && File.ReadAllBytes(Path.Combine(tmp, "pc", "снимок.png")).SequenceEqual(data), "передача 2 файлов");
        // докачка
        var sha = Transfer.Sha256Hex(FileSource.Of(f));
        var part = Path.Combine(tmp, "pc", ".partial", ph.Me.Id + "-" + sha[..16] + ".part");
        Directory.CreateDirectory(Path.GetDirectoryName(part)); File.WriteAllBytes(part, data[..1_000_000]);
        long first = -1;
        ph.Send(peer, new() { FileSource.Of(f) }, Method.Manual, "127.0.0.1", pc.Port, (n, i, c, d, t) => { if (first < 0) first = d; });
        Check(first == 1_000_000, "докачка с места обрыва");
        // ретранслятор
        using var relay = new RelayServer(0);
        pc.RelayHost = ph.RelayHost = "127.0.0.1"; pc.RelayPort = ph.RelayPort = relay.Port; pc.StartRelayWaiters();
        Thread.Sleep(500);
        var used = ph.Send(peer, new() { FileSource.Of(f2) }, Method.Relay, null, 0, null);
        Check(used.Contains("ретранслятор"), "через ретранслятор");
        pc.Dispose(); ph.Dispose();
        Console.WriteLine(fails == 0 ? "ALL OK" : $"{fails} FAILED");
        return fails == 0 ? 0 : 1;
    }
}
