# T'OS-oppsett med PC – én fil, dobbeltklikk

Med en Windows-PC kan T'OS få alle tillatelsene og «Full kontroll» på én gang, uten Android-menyene
på stereoen. Du trenger ikke installere noe, og du trenger ikke kabel. Det tar 5–10 minutter.

Du trenger:
- en Windows-PC (bærbar er fint),
- at PC-en og stereoen er på **samme Wi-Fi** (f.eks. delt nett fra iPhonen),
- T'OS 5.12 eller nyere på stereoen.

## 1) Last ned fila

På PC-en: last ned **tos-oppsett.bat** her:
https://github.com/Tormod22/bilstereo-launcher/releases/download/latest/tos-oppsett.bat

Den havner i mappa **Nedlastinger**.

## 2) Slå på feilsøking på stereoen

I T'OS: **Innstillinger → Om stereoen → Ta over stereoen**. Der står det hvor du trykker for å slå på
utviklermodus og feilsøking, og hvilken adresse stereoen har.

## 3) Dobbeltklikk fila og følg teksten

Dobbeltklikk **tos-oppsett.bat** i Nedlastinger. Et svart vindu åpnes og forteller deg hva som skjer.

- Sier Windows **«Windows beskyttet PC-en»**: trykk **«Mer info»** og så **«Kjør likevel»**.
  (Windows sier dette om alle filer fra nettet som ikke er fra en stor butikk.)
- Fila laster ned Googles ADB-verktøy selv og finner stereoen på nettet selv.
- Spør stereoen **«Vil du tillate USB-feilsøking?»**: kryss av **«Tillat alltid»** og trykk **OK**.
- Spør den om Google-kontoen: følg teksten (kontosiden åpnes på stereoen, og du legger kontoen inn igjen etterpå).
- Til slutt slår den av feilsøkingen igjen, så ingen andre kan koble seg til stereoen.

Står det **FEIL** i oppsummeringen: send fila **tos-oppsett-logg.txt** (ligger i Nedlastinger) til utvikleren.

**Angre:** dobbeltklikk fila igjen og velg **2**. Full kontroll slås av i T'OS:
**Innstillinger → Om stereoen → Slå av full kontroll** (trykk to ganger).

---

## For spesielt interesserte

Det samme for hånd, med ADB i et kommandovindu (`adb` = `platform-tools\adb.exe` fra
developer.android.com/tools/releases/platform-tools).

### Koble til

```
adb connect 192.168.1.50:5555
adb devices
```
Bytt ut tallene med stereoens adresse (T'OS viser den). Stereoen skal stå som «device».
Står den som «unauthorized»: trykk «Tillat alltid» på stereoen.

### Tillatelsene

Knappestyring – legg T'OS til tjenestene stereoen alt har på (ikke slå av dem). Se først hva som står der:
```
adb shell settings get secure enabled_accessibility_services
```
- `null` eller tomt:
  ```
  adb shell settings put secure enabled_accessibility_services no.bil.launcher/no.bil.launcher.KeyService
  ```
- Noe annet (f.eks. `com.stereo/com.stereo.Tjeneste`): ta det med, med kolon imellom:
  ```
  adb shell settings put secure enabled_accessibility_services com.stereo/com.stereo.Tjeneste:no.bil.launcher/no.bil.launcher.KeyService
  ```

Resten (i denne rekkefølgen – samme som tos-oppsett.bat og «Ta over stereoen» i T'OS):
```
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener no.bil.launcher/no.bil.launcher.MediaListener
adb shell appops set no.bil.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops set no.bil.launcher WRITE_SETTINGS allow
adb shell appops set no.bil.launcher REQUEST_INSTALL_PACKAGES allow
adb shell appops set no.bil.launcher ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set no.bil.launcher GET_USAGE_STATS allow
adb shell pm grant no.bil.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant no.bil.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_CONNECT
adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_SCAN
adb shell pm grant no.bil.launcher android.permission.POST_NOTIFICATIONS
adb shell pm grant no.bil.launcher android.permission.READ_CONTACTS
adb shell pm grant no.bil.launcher android.permission.READ_CALL_LOG
adb shell pm grant no.bil.launcher android.permission.CALL_PHONE
adb shell pm grant no.bil.launcher android.permission.READ_PHONE_STATE
adb shell cmd wifi network-suggestions-set-user-approved no.bil.launcher yes
adb shell dumpsys deviceidle whitelist +no.bil.launcher
adb shell cmd package set-home-activity no.bil.launcher/.MainActivity
adb shell cmd role add-role-holder android.app.role.DIALER no.bil.launcher
adb shell settings put system show_navigationbar 0
adb shell pm grant no.bil.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 1
```

| Linje | Gir T'OS |
|---|---|
| `enabled_accessibility_services`, `accessibility_enabled` | Knappestyring («T'OS knapper»): knappene på stereoen og rattet |
| `allow_listener … MediaListener` | Sangtittel, cover, neste sving og hvem som ringer |
| `SYSTEM_ALERT_WINDOW` | Vise over andre apper (Waze-popupen) |
| `WRITE_SETTINGS` | Lysstyrke, nattdemping, tastelyd og 24-timers klokke |
| `REQUEST_INSTALL_PACKAGES` | Installere oppdateringer av T'OS |
| `ACCESS_RESTRICTED_SETTINGS` | Android 13: fjerner «begrenset innstilling» for apper fra Drive |
| `GET_USAGE_STATS` | «Siste apper» i T'OS viser alle apper du har brukt |
| `pm grant …` | Posisjon, Bluetooth, varsel, kontakter, anrop og telefon |
| `network-suggestions-set-user-approved` | Wi-Fi-nett kan legges inn fra T'OS uten spørsmål (Android 11+) |
| `deviceidle whitelist` | Batterisparing stopper ikke T'OS |
| `set-home-activity` | T'OS blir startskjermen |
| `add-role-holder … DIALER` | T'OS blir telefon-appen |
| `show_navigationbar 0` | Bare FYT-stereoer: skjuler stereoens egen linje øverst i *alle* apper |
| `WRITE_SECURE_SETTINGS` | T'OS slår knappestyringen og varseltilgangen på igjen selv hvis de blir slått av |
| `auto_time`, `auto_time_zone` | Klokka og tidssonen stilles automatisk |

Svar som «Unknown permission», «Unknown operation» eller «not a changeable permission type» gjelder en
annen Android-versjon enn stereoen har, og kan overses.

### Full kontroll (T'OS blir enhetseier)

Android tillater dette bare når ingen konto teller. tos-oppsett.bat (og T'OS selv) prøver først rett på.
Sier Android nei på grunn av Google-kontoen, slås Google-tjenestene av i noen sekunder (så kontoen ikke
teller), T'OS blir enhetseier, og tjenestene slås **alltid** på igjen:
```
adb shell pm disable-user --user 0 com.google.android.gms
adb shell dpm set-device-owner no.bil.launcher/.TosAdmin
adb shell pm enable com.google.android.gms
```
Svaret på den midterste skal være «Success: Device owner set to package …».
- «there are already some accounts on the device»: en konto teller fortsatt. Fjern Google-kontoen på
  stereoen (`adb shell am start -a android.settings.SYNC_SETTINGS` åpner kontosiden), kjør
  `dpm set-device-owner` igjen, og legg kontoen inn igjen etterpå
  (`adb shell am start -a android.settings.ADD_ACCOUNT_SETTINGS`).
- «there are already several users on the device»: stereoen har flere brukere. Da går det ikke uten å
  nullstille stereoen – hopp over dette.
- «Unknown admin»: T'OS er for gammel – oppdater til 5.12 eller nyere.

Husk alltid `adb shell pm enable com.google.android.gms` til slutt, ellers virker ikke Play Butikk.
Ble det avbrutt midt i, prøver T'OS selv å slå dem på igjen når den starter (og knappen «Slå på
Play-tjenester» under **Ta over stereoen**).

Stereoens hovedstartskjerm (launcher9) skjules ikke som standard: FYT-sentralen kan åpne den selv, og da
henter T'OS seg fram igjen som før. Krasjer T'OS flere ganger rett etter hverandre, slår T'OS på stereoens
egen startskjerm igjen av seg selv (nødstopp), så stereoen aldri står uten. «Bruk T'OS igjen» ligger under
**Om stereoen → Full kontroll**.

Med full kontroll stenger T'OS Androids nedtrekksmeny, skjuler de gamle startskjermene, gir seg selv
tillatelser, låser seg som startskjerm og oppdaterer seg uten spørsmål. FYT-tjenestene (radio,
Bluetooth, rattknapper, CarPlay) røres ikke.

### Slå av feilsøking igjen

```
adb shell settings put global adb_enabled 0
```
Tillatelsene T'OS har fått, blir værende.

### Angre

```
adb shell settings put system show_navigationbar 1
adb shell pm enable com.google.android.gms
adb shell dumpsys deviceidle whitelist -no.bil.launcher
```
Full kontroll: I T'OS: **Innstillinger → Om stereoen → Slå av full kontroll** (trykk to ganger).
Det finnes ingen ADB-kommando som fjerner en enhetseier fra en vanlig app – knappen i T'OS er måten å
angre på. Siste utvei hvis T'OS ikke kan åpnes: nullstille stereoen til fabrikkinnstillinger (alt slettes).

### Oppdateringer

Etter oppsettet trenger du ikke PC-en mer. Oppdateringsknappen i T'OS laster ned og installerer selv
(med full kontroll helt uten spørsmål).
