#!/usr/bin/env bash
# Совместимость: Java-ядро (как в Android) <-> .NET-ядро (как в Windows), в обе стороны.
set -euo pipefail
. /tmp/prdcp 2>/dev/null || true
export PATH=$HOME/.dotnet:$PATH
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JCP=/tmp/prdc
NET="dotnet $ROOT/windows/Peredatchik.Host/bin/Release/net8.0/peredatchik-host.dll"
W=$(mktemp -d); trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf $W' EXIT
head -c 3000000 /dev/urandom > "$W/фото.jpg"; echo "привет" > "$W/заметка.txt"
fail=0; ok(){ echo "OK   $1"; }; bad(){ echo "FAIL $1"; fail=1; }

# 1) ПК (.NET) принимает, телефон (Java) сопрягается и отправляет
$NET serve $W/net 47300 246810 $W/net_in > $W/net.log 2>&1 &
sleep 2
java -cp $JCP ru.peredatchik.core.Cli pair $W/jvm 127.0.0.1 47300 246810 | grep -q PAIRED && ok "Java сопрягается с .NET" || bad "Java pair"
java -cp $JCP ru.peredatchik.core.Cli send $W/jvm first 127.0.0.1 47300 "$W/фото.jpg" "$W/заметка.txt" | grep -q SENT && ok "Java -> .NET отправка" || bad "Java send"
sleep 1; cmp "$W/фото.jpg" "$W/net_in/фото.jpg" && cmp "$W/заметка.txt" "$W/net_in/заметка.txt" && ok "файлы совпадают побайтно" || bad "cmp1"

# 2) смена ролей: телефон (Java) принимает, ПК (.NET) отправляет по уже установленному сопряжению
java -cp $JCP ru.peredatchik.core.Cli serve $W/jvm 47301 000000 $W/jvm_in > $W/jvm.log 2>&1 &
sleep 2
$NET send $W/net first 127.0.0.1 47301 "$W/фото.jpg" | grep -q SENT && ok ".NET -> Java отправка (обратная роль)" || bad "net send"
sleep 1; cmp "$W/фото.jpg" "$W/jvm_in/фото.jpg" && ok "файл совпадает" || bad "cmp2"

# 3) новое сопряжение в обратную сторону: .NET сопрягается с Java по коду
java -cp $JCP ru.peredatchik.core.Cli serve $W/jvm2 47302 135790 $W/jvm2_in > $W/jvm2.log 2>&1 &
sleep 2
$NET pair $W/net2 127.0.0.1 47302 135790 | grep -q PAIRED && ok ".NET сопрягается с Java" || bad "net pair"
OUT=$($NET pair $W/net3 127.0.0.1 47302 999999 2>&1 || true); echo "$OUT" | grep -q "не открыто\|неверный" && ok "повторное/неверное сопряжение отклонено" || bad "net bad pair"
cat $W/*.log | grep -i error | grep -v "неверный код\|сопряжение не открыто" && bad "ошибки в логах" || ok "без лишних ошибок в логах"
exit $fail
