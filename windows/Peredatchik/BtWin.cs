using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using Peredatchik.Core;

namespace Peredatchik;

/// Bluetooth RFCOMM через Winsock (AF_BTH): подключение ПК -> телефон по UUID службы «Передатчика».
static class BtWin
{
    static readonly Guid Service = Guid.Parse("6c3f2a10-5e0b-4f6a-9d6e-7065726564a1");
    const AddressFamily AF_BTH = (AddressFamily)32;
    const ProtocolType BTHPROTO_RFCOMM = (ProtocolType)3;

    sealed class BthEndPoint : EndPoint
    {
        readonly ulong _addr; readonly Guid _svc;
        public BthEndPoint(ulong a, Guid s) { _addr = a; _svc = s; }
        public override AddressFamily AddressFamily => AF_BTH;
        public override SocketAddress Serialize()
        {
            // SOCKADDR_BTH: USHORT family; BTH_ADDR btAddr(8, с выравниванием); GUID serviceClassId; ULONG port
            var sa = new SocketAddress(AF_BTH, 30);
            var a = BitConverter.GetBytes(_addr); for (int i = 0; i < 8; i++) sa[2 + i] = a[i];
            var g = _svc.ToByteArray(); for (int i = 0; i < 16; i++) sa[10 + i] = g[i];
            for (int i = 0; i < 4; i++) sa[26 + i] = 0;
            return sa;
        }
        public override EndPoint Create(SocketAddress sa) => this;
    }

    public static ulong ParseMac(string mac) => Convert.ToUInt64(mac.Replace(":", "").Replace("-", ""), 16);

    public static Link Open(Peer p)
    {
        if (string.IsNullOrEmpty(p.BtAddress)) throw new IOException("для телефона не выбран Bluetooth-адрес");
        var s = new Socket(AF_BTH, SocketType.Stream, BTHPROTO_RFCOMM);
        try { s.Connect(new BthEndPoint(ParseMac(p.BtAddress), Service)); }
        catch (Exception e) { s.Dispose(); throw new IOException("нет связи по Bluetooth: " + e.Message); }
        var ns = new NetworkStream(s, true);
        return new Link { In = ns, Out = ns, Owner = ns, Description = "bt" };
    }

    // ---- список сопряжённых в Windows Bluetooth-устройств ----
    [StructLayout(LayoutKind.Sequential)]
    struct SEARCH_PARAMS
    {
        public int dwSize; public bool fReturnAuthenticated, fReturnRemembered, fReturnUnknown, fReturnConnected, fIssueInquiry;
        public byte cTimeoutMultiplier; public IntPtr hRadio;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DEVICE_INFO
    {
        public int dwSize; public ulong Address; public uint ulClassofDevice;
        public bool fConnected, fRemembered, fAuthenticated;
        public SYSTEMTIME stLastSeen, stLastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string szName;
    }
    [StructLayout(LayoutKind.Sequential)] struct SYSTEMTIME { public ushort a, b, c, d, e, f, g, h; }
    [DllImport("bthprops.cpl", SetLastError = true)] static extern IntPtr BluetoothFindFirstDevice(ref SEARCH_PARAMS p, ref DEVICE_INFO i);
    [DllImport("bthprops.cpl", SetLastError = true)] static extern bool BluetoothFindNextDevice(IntPtr h, ref DEVICE_INFO i);
    [DllImport("bthprops.cpl", SetLastError = true)] static extern bool BluetoothFindDeviceClose(IntPtr h);

    public static List<(string Name, string Mac)> Paired()
    {
        var l = new List<(string, string)>();
        try
        {
            var p = new SEARCH_PARAMS { dwSize = Marshal.SizeOf<SEARCH_PARAMS>(), fReturnAuthenticated = true, fReturnRemembered = true };
            var i = new DEVICE_INFO { dwSize = Marshal.SizeOf<DEVICE_INFO>() };
            var h = BluetoothFindFirstDevice(ref p, ref i);
            if (h == IntPtr.Zero) return l;
            do
            {
                var mac = string.Join(":", BitConverter.GetBytes(i.Address).Take(6).Reverse().Select(b => b.ToString("X2")));
                l.Add((i.szName, mac));
                i = new DEVICE_INFO { dwSize = Marshal.SizeOf<DEVICE_INFO>() };
            } while (BluetoothFindNextDevice(h, ref i));
            BluetoothFindDeviceClose(h);
        }
        catch { }
        return l;
    }
}
