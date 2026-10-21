package ru.peredatchik.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import java.util.*;
import ru.peredatchik.core.*;

public class MainActivity extends Activity {
    private Hub hub;
    private LinearLayout root;
    private Peer sendTo;
    private Method sendMethod;
    private final Runnable refresher = this::render;
    private static final int REQ_PICK = 7;

    static void startService(Context c) {
        Intent i = new Intent(c, PeredatchikService.class);
        try { c.startForegroundService(i); } catch (Exception e) { Hub.get(c).start(); }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = Hub.get(this);
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != 0) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
        startService(this);
        root = Ui.col(this);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFFF2F4F8); sv.addView(root);
        setContentView(sv);
        render();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { hub.bt.startServer(hub.node); }
    @Override protected void onResume() { super.onResume(); hub.watchers.add(refresher); render(); }
    @Override protected void onPause() { super.onPause(); hub.watchers.remove(refresher); }

    private void render() {
        root.removeAllViews();
        LinearLayout me = Ui.col(this); me.setPadding(0, 0, 0, 0);
        me.addView(Ui.text(this, "📱 " + hub.me.name, 18, true));
        List<String> ips = Discovery.localAddresses();
        me.addView(Ui.text(this, "Адрес: " + (ips.isEmpty() ? "нет сети" : String.join(", ", ips)) + "  порт " + hub.node.port()
                + "\nКлюч: " + hub.me.id.substring(0, 8) + "…  ·  принято файлов: " + hub.prefs.getInt("rxCount", 0), 13, false));
        root.addView(Ui.card(this, me));

        root.addView(Ui.button(this, "📷  Сопрячь с ПК по QR-коду", v -> scan()));
        root.addView(Ui.button(this, "🔢  Сопрячь по 6-значному коду", v -> pairByCodeDialog(null)));
        root.addView(Ui.button(this, "🔑  Показать мой код (ПК вводит его)", v -> showMyCode()));

        root.addView(Ui.text(this, "Сопряжённые компьютеры", 16, true));
        List<Peer> peers = hub.store.all();
        if (peers.isEmpty()) root.addView(Ui.text(this, "Пока нет. Запустите «Передатчик» на ПК, нажмите там «Сопрячь телефон» и отсканируйте QR-код.", 14, false));
        for (Peer p : peers) {
            LinearLayout c = Ui.col(this); c.setPadding(0, 0, 0, 0);
            Discovery.Seen s = hub.node.discovery != null ? hub.node.discovery.get(p.id) : null;
            c.addView(Ui.text(this, "💻 " + p.name, 16, true));
            c.addView(Ui.text(this, (s != null ? "🟢 в сети: " + s.host : "⚪ не найден в этой сети" + (p.lastHost != null ? " (был " + p.lastHost + ")" : ""))
                    + (p.btAddress != null ? "  ·  BT " + p.btAddress : ""), 13, false));
            LinearLayout r = Ui.row(this);
            r.addView(Ui.button(this, "Отправить…", v -> pickFiles(p)));
            r.addView(Ui.button(this, "Bluetooth", v -> chooseBt(p)));
            r.addView(Ui.button(this, "Удалить", v -> new AlertDialog.Builder(this).setMessage("Забыть «" + p.name + "»?")
                    .setPositiveButton("Забыть", (d, w) -> { hub.store.remove(p.id); render(); }).setNegativeButton("Отмена", null).show()));
            c.addView(r);
            root.addView(Ui.card(this, c));
        }
        if (hub.node.discovery != null) {
            List<Discovery.Seen> near = new ArrayList<>();
            for (Discovery.Seen s : hub.node.discovery.all()) if (hub.store.get(s.id) == null) near.add(s);
            if (!near.isEmpty()) {
                root.addView(Ui.text(this, "Найдены рядом (не сопряжены)", 16, true));
                for (Discovery.Seen s : near) root.addView(Ui.button(this, "➕ " + s.name + " (" + s.host + ")", v -> pairByCodeDialog(s)));
            }
        }
        root.addView(Ui.text(this, "Настройки", 16, true));
        root.addView(Ui.button(this, "⚙  Имя, способ связи по умолчанию, ретранслятор", v -> settings()));
        root.addView(Ui.button(this, "🖼  Открыть принятые фото", v -> openGallery()));
        TextView help = Ui.text(this, "Как отправить: в Галерее выберите фото или скриншот → «Поделиться» → «Передатчик: на ПК».\n"
                + "Способы связи: Wi-Fi (одна сеть), точка доступа телефона (ПК подключается к ней), USB-модем (кабель + «USB-модем» в настройках телефона), "
                + "USB через ADB, Bluetooth, интернет-ретранслятор, вручную IP:порт. «Авто» перебирает их сам.", 13, false);
        root.addView(help);
        if (hub.lastError != null) root.addView(Ui.text(this, "Последняя ошибка: " + hub.lastError, 12, false));
    }

    // ---------- сопряжение ----------
    private void scan() {
        IntentIntegrator ii = new IntentIntegrator(this);
        ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        ii.setPrompt("Наведите камеру на QR-код в «Передатчике» на ПК");
        ii.setBeepEnabled(false); ii.setOrientationLocked(false);
        ii.initiateScan();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK) {
            if (res == RESULT_OK && data != null && sendTo != null) {
                List<Uri> l = new ArrayList<>();
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) l.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) l.add(data.getData());
                Peer p = sendTo;
                ProgressDialogLite pd = new ProgressDialogLite(this, "Отправка на «" + p.name + "»");
                hub.send(p, l, sendMethod, hub.prefs.getString("manual", ""), pd);
            }
            return;
        }
        IntentResult r = IntentIntegrator.parseActivityResult(req, res, data);
        if (r == null || r.getContents() == null) return;
        String[] f = r.getContents().split("\\|");
        if (f.length < 6 || !f[0].equals("prd1")) { hub.toast("Это не QR-код «Передатчика»"); return; }
        String[] hosts = f[1].split(",");
        int port = Integer.parseInt(f[2]);
        doPair(Arrays.asList(hosts), port, f[3], f[4]);
    }

    private void doPair(List<String> hosts, int port, String code, String expectId) {
        ProgressDialogLite pd = new ProgressDialogLite(this, "Сопряжение…");
        hub.exec.execute(() -> {
            String err = "нет адресов";
            for (String h : hosts) {
                if (h.isEmpty()) continue;
                try {
                    Peer p = hub.node.pair(Link.tcp(h, port, 3000), code, expectId);
                    hub.main.post(() -> { pd.done(true, "Сопряжено с «" + p.name + "»"); render(); });
                    return;
                } catch (ProtocolException e) { err = e.getMessage(); break; }
                catch (Exception e) { err = h + ": " + e.getMessage(); }
            }
            String fe = err;
            hub.main.post(() -> pd.done(false, "Не удалось: " + fe));
        });
    }

    private void pairByCodeDialog(Discovery.Seen target) {
        LinearLayout l = Ui.col(this);
        List<Discovery.Seen> near = new ArrayList<>();
        if (hub.node.discovery != null) near.addAll(hub.node.discovery.all());
        Spinner sp = new Spinner(this);
        List<String> items = new ArrayList<>();
        for (Discovery.Seen s : near) items.add(s.name + " (" + s.host + ")");
        items.add("Ввести IP-адрес вручную");
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        if (target != null) sp.setSelection(near.indexOf(target));
        EditText ip = new EditText(this); ip.setHint("IP:порт, напр. 192.168.43.20:47232");
        EditText code = new EditText(this); code.setHint("6-значный код с экрана ПК"); code.setInputType(InputType.TYPE_CLASS_NUMBER);
        l.addView(Ui.text(this, "Устройство:", 14, false)); l.addView(sp); l.addView(ip); l.addView(code);
        new AlertDialog.Builder(this).setTitle("Сопряжение по коду").setView(l)
                .setPositiveButton("Сопрячь", (d, w) -> {
                    int i = sp.getSelectedItemPosition();
                    if (i < near.size()) doPair(Collections.singletonList(near.get(i).host), near.get(i).port, code.getText().toString(), null);
                    else {
                        String s = ip.getText().toString().trim(); int c = s.lastIndexOf(':');
                        doPair(Collections.singletonList(c > 0 ? s.substring(0, c) : s), c > 0 ? Integer.parseInt(s.substring(c + 1)) : Node.DEFAULT_PORT, code.getText().toString(), null);
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    private void showMyCode() {
        Pairing p = Pairing.random(5 * 60_000);
        hub.node.pairing = p;
        LinearLayout l = Ui.col(this); l.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView code = Ui.text(this, p.code.substring(0, 3) + " " + p.code.substring(3), 40, true); code.setGravity(Gravity.CENTER);
        l.addView(code);
        l.addView(Ui.text(this, "Введите этот код на ПК: «Сопрячь» → «Ввести код телефона».\nАдрес телефона: "
                + String.join(", ", Discovery.localAddresses()) + "  порт " + hub.node.port() + "\nКод действует 5 минут.", 14, false));
        try {
            Bitmap bm = new BarcodeEncoder().encodeBitmap(hub.node.pairingUri(p.code), BarcodeFormat.QR_CODE, 600, 600);
            ImageView iv = new ImageView(this); iv.setImageBitmap(bm); l.addView(iv);
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this).setTitle("Мой код сопряжения").setView(l).setPositiveButton("Готово", null).show();
    }

    // ---------- отправка из приложения ----------
    private void pickFiles(Peer p) {
        Spinner sp = Ui.methodSpinner(this, hub.defaultMethod());
        new AlertDialog.Builder(this).setTitle("Способ связи").setView(sp).setPositiveButton("Выбрать файлы", (d, w) -> {
            sendTo = p; sendMethod = Method.values()[sp.getSelectedItemPosition()];
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK);
        }).setNegativeButton("Отмена", null).show();
    }

    @SuppressLint("MissingPermission")
    private void chooseBt(Peer p) {
        BluetoothAdapter a = Bt.adapter();
        if (a == null) { hub.toast("Bluetooth на этом телефоне нет"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != 0) { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2); return; }
        List<BluetoothDevice> devs = new ArrayList<>(a.getBondedDevices());
        if (devs.isEmpty()) { hub.toast("Сначала сопрягите ПК в настройках Bluetooth телефона"); return; }
        String[] names = new String[devs.size()];
        for (int i = 0; i < names.length; i++) names[i] = devs.get(i).getName() + " (" + devs.get(i).getAddress() + ")";
        new AlertDialog.Builder(this).setTitle("Какой это компьютер в Bluetooth?").setItems(names, (d, w) -> {
            p.btAddress = devs.get(w).getAddress(); hub.store.put(p); render();
        }).show();
    }

    private void settings() {
        LinearLayout l = Ui.col(this);
        EditText name = new EditText(this); name.setText(hub.me.name);
        Spinner sp = Ui.methodSpinner(this, hub.defaultMethod());
        EditText relay = new EditText(this); relay.setHint("сервер:47233 (пусто — не использовать)"); relay.setText(hub.prefs.getString("relay", ""));
        EditText manual = new EditText(this); manual.setHint("IP:порт ПК для способа «Вручную»"); manual.setText(hub.prefs.getString("manual", ""));
        l.addView(Ui.text(this, "Имя телефона:", 14, false)); l.addView(name);
        l.addView(Ui.text(this, "Способ связи по умолчанию:", 14, false)); l.addView(sp);
        l.addView(Ui.text(this, "Ретранслятор для связи через интернет (свой сервер с peredatchik-host relay):", 14, false)); l.addView(relay);
        l.addView(Ui.text(this, "Адрес для ручного подключения:", 14, false)); l.addView(manual);
        ScrollView sv = new ScrollView(this); sv.addView(l);
        new AlertDialog.Builder(this).setTitle("Настройки").setView(sv).setPositiveButton("Сохранить", (d, w) -> {
            hub.me.name = name.getText().toString().trim().isEmpty() ? hub.me.name : name.getText().toString().trim();
            FilePeerStore.saveIdentity(new java.io.File(getFilesDir(), "identity.json"), hub.me);
            hub.prefs.edit().putString("method", Method.values()[sp.getSelectedItemPosition()].name())
                    .putString("relay", relay.getText().toString().trim()).putString("manual", manual.getText().toString().trim()).apply();
            hub.applyRelay(); render();
        }).setNegativeButton("Отмена", null).show();
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivity(i);
        } catch (Exception e) { hub.toast("Откройте Галерею → альбом «Передатчик»"); }
    }

    /** Простое окно прогресса. */
    static final class ProgressDialogLite implements Hub.SendUi {
        final AlertDialog d; final TextView t; final ProgressBar pb;
        ProgressDialogLite(Activity a, String title) {
            LinearLayout l = Ui.col(a);
            pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100); pb.setIndeterminate(true);
            t = Ui.text(a, "Подключение…", 14, false);
            l.addView(pb); l.addView(t);
            d = new AlertDialog.Builder(a).setTitle(title).setView(l).setPositiveButton("Скрыть", null).show();
        }
        public void progress(String text, int pct) { pb.setIndeterminate(false); pb.setProgress(pct); t.setText(text); }
        public void done(boolean ok, String msg) { pb.setVisibility(View.GONE); t.setText((ok ? "✓ " : "✗ ") + msg); }
    }
}
