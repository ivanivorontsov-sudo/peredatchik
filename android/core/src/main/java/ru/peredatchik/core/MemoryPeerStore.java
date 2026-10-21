package ru.peredatchik.core;

import java.util.*;

public class MemoryPeerStore implements PeerStore {
    protected final Map<String, Peer> peers = new LinkedHashMap<>();
    public synchronized Peer get(String id) { return peers.get(id); }
    public synchronized void put(Peer p) { peers.put(p.id, p); saved(); }
    public synchronized List<Peer> all() { return new ArrayList<>(peers.values()); }
    protected void saved() {}
}
