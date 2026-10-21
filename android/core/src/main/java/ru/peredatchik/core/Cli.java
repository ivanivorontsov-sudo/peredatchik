package ru.peredatchik.core;

import java.io.File;
import java.util.*;

/**
 * Консоль для проверки совместимости с ПК-версией.
 *  serve <state> <port> <code> <inbox>   — принимать (окно сопряжения с кодом)
 *  pair  <state> <host> <port> <code>
 *  send  <state> <peerId|first> <host> <port> files...
 *  relay <port>
 */
public final class Cli {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("relay")) { new RelayServer(Integer.parseInt(a[1])); System.out.println("RELAY READY"); Thread.sleep(Long.MAX_VALUE); }
        File st = new File(a[1]); st.mkdirs();
        Identity me = FilePeerStore.identity(new File(st, "identity.json"), "JVM-" + st.getName());
        FilePeerStore store = new FilePeerStore(new File(st, "peers.json"));
        switch (a[0]) {
            case "serve": {
                Node n = new Node(me, store, new DirSink(new File(a[4])), new Node.Listener() {
                    public void onReceived(Peer f, List<String> s) { System.out.println("RECEIVED " + s.size() + " " + s); }
                    public void onPaired(Peer p) { System.out.println("PAIRED " + p.name + " " + p.id); }
                    public void onError(String m) { System.out.println("ERROR " + m); }
                });
                n.pairing = new Pairing(a[3], 600_000);
                n.start(Integer.parseInt(a[2]), false);
                System.out.println("READY " + me.id + " " + n.port());
                Thread.sleep(Long.MAX_VALUE);
            }
            case "pair": {
                Node n = new Node(me, store, new DirSink(new File(st, "in")), new Node.Listener() {});
                Peer p = n.pair(Link.tcp(a[2], Integer.parseInt(a[3]), 5000), a[4], null);
                System.out.println("PAIRED " + p.name + " " + p.id);
                return;
            }
            case "send": {
                Node n = new Node(me, store, new DirSink(new File(st, "in")), new Node.Listener() {});
                Peer p = a[2].equals("first") ? store.all().get(0) : store.get(a[2]);
                List<FileSource> fs = new ArrayList<>();
                for (int i = 5; i < a.length; i++) fs.add(FileSource.of(new File(a[i])));
                String used = n.send(p, fs, Method.MANUAL, a[3], Integer.parseInt(a[4]), Progress.NONE);
                System.out.println("SENT via " + used);
                return;
            }
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
