package ru.peredatchik.app;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import java.io.IOException;
import java.util.UUID;
import ru.peredatchik.core.*;

/** Bluetooth RFCOMM: тот же защищённый протокол поверх последовательного канала. */
@SuppressLint("MissingPermission")
final class Bt {
    static final UUID SERVICE = UUID.fromString("6c3f2a10-5e0b-4f6a-9d6e-7065726564a1");
    private volatile boolean running;
    private BluetoothServerSocket server;

    static BluetoothAdapter adapter() { return BluetoothAdapter.getDefaultAdapter(); }

    void startServer(Node node) {
        BluetoothAdapter a = adapter();
        if (a == null || running) return;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    if (!a.isEnabled()) { Thread.sleep(10_000); continue; }
                    server = a.listenUsingRfcommWithServiceRecord("Peredatchik", SERVICE);
                    while (running) {
                        BluetoothSocket s = server.accept();
                        Link l = new Link(s.getInputStream(), s.getOutputStream(), s, "bt");
                        new Thread(() -> node.handleIncoming(l)).start();
                    }
                } catch (Exception e) {
                    try { Thread.sleep(10_000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "prd-bt");
        t.setDaemon(true); t.start();
    }

    void stop() { running = false; try { if (server != null) server.close(); } catch (IOException ignored) {} }

    static Link open(Peer p) throws IOException {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) throw new IOException("Bluetooth выключен");
        BluetoothDevice d = a.getRemoteDevice(p.btAddress);
        a.cancelDiscovery();
        BluetoothSocket s = d.createRfcommSocketToServiceRecord(SERVICE);
        try { s.connect(); } catch (IOException e) { s.close(); throw new IOException("нет связи по Bluetooth: " + e.getMessage()); }
        return new Link(s.getInputStream(), s.getOutputStream(), s, "bt");
    }
}
