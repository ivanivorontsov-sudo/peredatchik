package ru.peredatchik.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import ru.peredatchik.core.*;

/** Единая точка: ключ, сопряжённые устройства, узел приёма/отправки, уведомления. */
final class Hub implements Node.Listener {
    private static Hub inst;
    static synchronized Hub get(Context c) { if (inst == null) inst = new Hub(c.getApplicationContext()); return inst; }

    final Context ctx;
    final Identity me;
    final FilePeerStore store;
    final Node node;
    final SharedPreferences prefs;
    final Bt bt = new Bt();
    private boolean started;
    private WifiManager.MulticastLock mlock;
    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final List<Runnable> watchers = new CopyOnWriteArrayList<>();
    volatile String lastError;
    private int notifId = 100;

    static final String CH_STATUS = "status", CH_RX = "rx", CH_TX = "tx";

    private Hub(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("prd", Context.MODE_PRIVATE);
        me = FilePeerStore.identity(new File(c.getFilesDir(), "identity.json"), Build.MANUFACTURER + " " + Build.MODEL);
        store = new FilePeerStore(new File(c.getFilesDir(), "peers.json"));
        node = new Node(me, store, new MediaSink(c), this);
        node.bluetooth = Bt::open;
        applyRelay();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Готов к приёму", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_RX, "Принятые файлы", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_TX, "Отправка", NotificationManager.IMPORTANCE_LOW));
    }

    Method defaultMethod() { try { return Method.valueOf(prefs.getString("method", "AUTO")); } catch (Exception e) { return Method.AUTO; } }

    void applyRelay() {
        String r = prefs.getString("relay", "").trim();
        if (r.isEmpty()) { node.relayHost = null; node.stopRelayWaiters(); return; }
        String host = r; int port = 47233;
        int i = r.lastIndexOf(':');
        if (i > 0) { host = r.substring(0, i); try { port = Integer.parseInt(r.substring(i + 1)); } catch (NumberFormatException ignored) {} }
        node.relayHost = host; node.relayPort = port;
        if (started) node.startRelayWaiters();
    }

    synchronized void start() {
        if (started) return;
        started = true;
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            mlock = wm.createMulticastLock("peredatchik"); mlock.setReferenceCounted(false); mlock.acquire();
        } catch (Exception ignored) {}
        exec.execute(() -> {
            try { node.start(Node.DEFAULT_PORT, true); node.discovery.onChange = this::changed; }
            catch (Exception e) { lastError = "Не удалось открыть порт: " + e.getMessage(); }
            node.startRelayWaiters();
            if (Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0) bt.startServer(node);
            changed();
        });
    }

    void changed() { main.post(() -> { for (Runnable r : watchers) r.run(); }); }

    // ---------- отправка ----------
    interface SendUi { void progress(String text, int pct); void done(boolean ok, String msg); }

    void send(Peer p, List<Uri> uris, Method m, String manual, SendUi ui) {
        int id = notifId++;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        Notification.Builder b = new Notification.Builder(ctx, CH_TX).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Отправка на «" + p.name + "»").setOngoing(true).setOnlyAlertOnce(true);
        nm.notify(id, b.setContentText("Подготовка…").setProgress(0, 0, true).build());
        new Thread(() -> {
            try {
                List<FileSource> fs = new ArrayList<>();
                for (Uri u : uris) fs.add(new UriSource(ctx, u));
                if ((m == Method.AUTO || m == Method.WIFI) && node.discovery != null)
                    for (int w = 0; w < 30 && node.discovery.get(p.id) == null; w++) Thread.sleep(100);
                String host = null; int port = 0;
                if (manual != null && !manual.trim().isEmpty()) {
                    String s = manual.trim(); int i = s.lastIndexOf(':');
                    host = i > 0 ? s.substring(0, i) : s; port = i > 0 ? Integer.parseInt(s.substring(i + 1)) : Node.DEFAULT_PORT;
                }
                long[] last = {0};
                String used = node.send(p, fs, m, host, port, (name, i, n, done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 400 && done < total) return;
                    last[0] = now;
                    int pct = total == 0 ? 100 : (int) (done * 100 / total);
                    String txt = (n > 1 ? "Файл " + (i + 1) + " из " + n + ": " : "") + name + " — " + pct + "%";
                    if (ui != null) main.post(() -> ui.progress(txt, pct));
                    nm.notify(id, b.setContentText((n > 1 ? "Файл " + (i + 1) + " из " + n + ": " : "") + name + " — " + pct + "%")
                            .setProgress(100, pct, false).build());
                });
                nm.notify(id, new Notification.Builder(ctx, CH_TX).setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("Отправлено на «" + p.name + "»")
                        .setContentText(fs.size() + " " + files(fs.size()) + " · " + used).setAutoCancel(true).build());
                String msg = "Отправлено: " + fs.size() + " " + files(fs.size()) + " (" + used + ")";
                if (ui != null) main.post(() -> ui.done(true, msg)); else toast(msg);
            } catch (Exception e) {
                nm.notify(id, new Notification.Builder(ctx, CH_TX).setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("Не отправлено на «" + p.name + "»").setStyle(new Notification.BigTextStyle().bigText(e.getMessage()))
                        .setContentText(e.getMessage()).setAutoCancel(true).build());
                if (ui != null) main.post(() -> ui.done(false, e.getMessage())); else toast("Ошибка: " + e.getMessage());
            }
        }, "prd-send").start();
    }

    static String files(int n) { int m = n % 10, h = n % 100; return m == 1 && h != 11 ? "файл" : m >= 2 && m <= 4 && (h < 12 || h > 14) ? "файла" : "файлов"; }

    void toast(String s) { main.post(() -> android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()); }

    // ---------- события узла ----------
    @Override public void onReceived(Peer from, List<String> saved) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.cancel(50);
        Intent open;
        if (saved.size() == 1) {
            Uri u = Uri.parse(saved.get(0));
            open = new Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.getContentResolver().getType(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(notifId++, new Notification.Builder(ctx, CH_RX).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Принято от «" + from.name + "»")
                .setContentText(saved.size() + " " + files(saved.size()) + " · Галерея → «" + MediaSink.FOLDER + "»")
                .setContentIntent(pi).setAutoCancel(true).build());
        prefs.edit().putInt("rxCount", prefs.getInt("rxCount", 0) + saved.size()).apply();
        changed();
    }

    private long lastRx;
    @Override public void onIncomingProgress(Peer from, String name, int i, int n, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastRx < 500) return;
        lastRx = now;
        int pct = total == 0 ? 100 : (int) (done * 100 / total);
        ctx.getSystemService(NotificationManager.class).notify(50, new Notification.Builder(ctx, CH_TX).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Приём от «" + from.name + "»").setContentText(name + " — " + pct + "%")
                .setProgress(100, pct, false).setOnlyAlertOnce(true).build());
    }

    @Override public void onPaired(Peer p) { toast("Сопряжено: «" + p.name + "»"); changed(); }
    @Override public void onError(String msg) { lastError = msg; ctx.getSystemService(NotificationManager.class).cancel(50); changed(); }
}
