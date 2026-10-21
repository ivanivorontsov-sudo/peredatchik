package ru.peredatchik.core;

public interface Progress {
    /** done/total — байты всего пакета; index — номер текущего файла. */
    void update(String name, int index, int count, long done, long total);
    Progress NONE = (n, i, c, d, t) -> {};
}
