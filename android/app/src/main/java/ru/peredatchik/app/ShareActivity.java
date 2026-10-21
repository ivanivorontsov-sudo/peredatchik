package ru.peredatchik.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import ru.peredatchik.core.*;

/** «Поделиться → Передатчик»: выбрать ПК и способ связи, отправить. */
public class ShareActivity extends Activity {
    private final List<Uri> uris = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Отправить на ПК");
        Intent in = getIntent();
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (in.getClipData() != null) {
            for (int i = 0; i < in.getClipData().getItemCount(); i++) if (in.getClipData().getItemAt(i).getUri() != null) uris.add(in.getClipData().getItemAt(i).getUri());
        }
        MainActivity.startService(this);
        Hub hub = Hub.get(this);
        LinearLayout root = Ui.col(this);
        ScrollView sv = new ScrollView(this); sv.addView(root); setContentView(sv);
        if (uris.isEmpty()) { root.addView(Ui.text(this, "Нечего отправлять: приложение передало не файл, а текст.", 16, false)); root.addView(Ui.button(this, "Закрыть", v -> finish())); return; }
        List<Peer> peers = hub.store.all();
        if (peers.isEmpty()) {
            root.addView(Ui.text(this, "Сначала сопрягите телефон с компьютером: откройте «Передатчик» и отсканируйте QR-код с ПК.", 16, false));
            root.addView(Ui.button(this, "Открыть «Передатчик»", v -> { startActivity(new Intent(this, MainActivity.class)); finish(); }));
            return;
        }
        root.addView(Ui.text(this, uris.size() + " " + Hub.files(uris.size()) + " → куда:", 16, true));
        RadioGroup rg = new RadioGroup(this);
        String lastId = hub.prefs.getString("lastPeer", peers.get(0).id);
        for (int i = 0; i < peers.size(); i++) {
            RadioButton r = new RadioButton(this); r.setId(1000 + i); r.setText(peers.get(i).name); r.setTextSize(16);
            rg.addView(r);
            if (peers.get(i).id.equals(lastId)) rg.check(r.getId());
        }
        if (rg.getCheckedRadioButtonId() == -1) rg.check(1000);
        root.addView(rg);
        root.addView(Ui.text(this, "Способ связи:", 14, false));
        Spinner sp = Ui.methodSpinner(this, hub.defaultMethod());
        root.addView(sp);
        EditText manual = new EditText(this); manual.setHint("IP:порт компьютера, напр. 192.168.1.10:47232");
        manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manual.setText(hub.prefs.getString("manual", ""));
        root.addView(manual);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { manual.setVisibility(Method.values()[pos] == Method.MANUAL ? View.VISIBLE : View.GONE); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        TextView status = Ui.text(this, "", 14, false);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100); pb.setVisibility(View.GONE);
        Button go = Ui.button(this, "Отправить", null);
        go.setOnClickListener(v -> {
            Peer p = peers.get(rg.getCheckedRadioButtonId() - 1000);
            Method m = Method.values()[sp.getSelectedItemPosition()];
            hub.prefs.edit().putString("lastPeer", p.id).putString("manual", manual.getText().toString()).apply();
            go.setEnabled(false); pb.setVisibility(View.VISIBLE); status.setText("Подключение…");
            hub.send(p, uris, m, m == Method.MANUAL ? manual.getText().toString() : null, new Hub.SendUi() {
                public void progress(String t, int pct) { status.setText(t); pb.setProgress(pct); }
                public void done(boolean ok, String msg) {
                    status.setText(ok ? "✓ " + msg : "✗ " + msg);
                    pb.setVisibility(View.GONE);
                    if (ok) status.postDelayed(ShareActivity.this::finish, 1200); else { go.setEnabled(true); go.setText("Повторить"); }
                }
            });
        });
        root.addView(go); root.addView(pb); root.addView(status);
        root.addView(Ui.button(this, "Свернуть (отправка продолжится)", v -> moveTaskToBack(true)));
    }
}
