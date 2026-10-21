package ru.peredatchik.core;

import java.util.List;

public interface PeerStore {
    Peer get(String id);
    void put(Peer p);
    List<Peer> all();
}
