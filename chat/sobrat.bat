@echo off
chcp 65001 >nul
cd /d "%~dp0"
copy /b Peredatchik-Setup.zip.part0+Peredatchik-Setup.zip.part1+Peredatchik-Setup.zip.part2 Peredatchik-Setup-1.0.0.zip
echo Готово: Peredatchik-Setup-1.0.0.zip — распакуйте и запустите установщик.
pause
