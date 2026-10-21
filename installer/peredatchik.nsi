Unicode true
ManifestDPIAware true
!include "MUI2.nsh"
Name "Передатчик"
OutFile "..\dist\Peredatchik-Setup-1.0.0.exe"
InstallDir "$LOCALAPPDATA\Programs\Peredatchik"
RequestExecutionLevel user
SetCompressor /SOLID lzma
!define MUI_ICON "..\windows\Peredatchik\app.ico"
!define MUI_UNICON "..\windows\Peredatchik\app.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\Peredatchik.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить «Передатчик»"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"

Section "Передатчик (обязательно)" SecMain
  SectionIn RO
  nsExec::Exec 'taskkill /IM Peredatchik.exe /F'
  SetOutPath "$INSTDIR"
  File "..\dist\win\Peredatchik.exe"
  File "README_RU.txt"
  CreateDirectory "$SMPROGRAMS\Передатчик"
  CreateShortCut "$SMPROGRAMS\Передатчик\Передатчик.lnk" "$INSTDIR\Peredatchik.exe"
  CreateShortCut "$SMPROGRAMS\Передатчик\Удалить Передатчик.lnk" "$INSTDIR\uninstall.exe"
  CreateShortCut "$DESKTOP\Передатчик.lnk" "$INSTDIR\Peredatchik.exe"
  ; «Открыть с помощью», «Отправить на телефон», «Отправить → Телефон», автозапуск
  ExecWait '"$INSTDIR\Peredatchik.exe" --register'
  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "DisplayName" "Передатчик"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "DisplayIcon" "$INSTDIR\Peredatchik.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "DisplayVersion" "1.0.0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "Publisher" "Иван Воронцов"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik" "NoModify" 1
SectionEnd

Section "Разрешить в брандмауэре (спросит права администратора)" SecFw
  ExecShellWait "runas" "cmd.exe" '/c netsh advfirewall firewall delete rule name="Передатчик" & netsh advfirewall firewall add rule name="Передатчик" dir=in action=allow program="$INSTDIR\Peredatchik.exe" enable=yes profile=private,domain & netsh advfirewall firewall add rule name="Передатчик" dir=in action=allow protocol=UDP localport=47231 profile=private,domain & netsh advfirewall firewall add rule name="Передатчик" dir=in action=allow protocol=TCP localport=47232 profile=private,domain' SW_HIDE
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /IM Peredatchik.exe /F'
  ExecWait '"$INSTDIR\Peredatchik.exe" --unregister'
  Delete "$INSTDIR\Peredatchik.exe"
  Delete "$INSTDIR\README_RU.txt"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Передатчик\*.lnk"
  RMDir "$SMPROGRAMS\Передатчик"
  Delete "$DESKTOP\Передатчик.lnk"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Peredatchik"
SectionEnd
