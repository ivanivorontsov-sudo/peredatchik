package ru.peredatchik.core;

/** Способы связи. */
public enum Method {
    AUTO("Авто (лучший доступный)"),
    WIFI("Wi-Fi: одна сеть / точка доступа телефона / USB-модем"),
    USB_ADB("USB-кабель через ADB (adb reverse)"),
    BLUETOOTH("Bluetooth"),
    RELAY("Через интернет (ретранслятор)"),
    MANUAL("Вручную: IP-адрес и порт");

    public final String title;
    Method(String t) { title = t; }
}
