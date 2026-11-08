# Oppsett av T'OS med PC – uten Android-menyene

En vanlig app må be om hver tillatelse gjennom Androids egne menyer (knappestyring, varseltilgang,
«vis over andre apper» osv.). Med en PC kan alt gis **på én gang**, uten at du ser noen av
Android-menyene på stereoen. Det tar ca. 15 minutter første gang.

Du trenger:
- en Windows-PC (bærbar er fint) på **samme Wi-Fi** som stereoen (f.eks. delt nett fra iPhonen),
- stereoen slått på, med T'OS installert.

Du trenger *ikke* kabel.

---

## Steg 1 – Last ned ADB på PC-en

1. Gå til **developer.android.com/tools/releases/platform-tools** på PC-en.
2. Trykk **«Download SDK Platform-Tools for Windows»**, godta vilkårene og last ned.
3. Høyreklikk zip-fila → **«Pakk ut alle»** → velg f.eks. `C:\platform-tools`.

## Steg 2 – Slå på utvikleralternativer på stereoen

Dette er den eneste gangen du må inn i Android-menyene.

1. I T'OS: **Innstillinger → Om stereoen → Android-innstillinger (avansert)** (trykk to ganger).
2. Gå til **Om nettbrettet / Om enheten** (eller «System → Om»).
3. Trykk **7 ganger** på **«Versjonsnummer»** (eller «Build number»). Det står «Du er nå utvikler».
4. Gå tilbake til **System → Utvikleralternativer** og slå på én av disse (stereoene er forskjellige):
   - **«Trådløs feilsøking»** (Android 11 og nyere), eller
   - **«ADB over nettverk»** / **«Nettverks-ADB»**, eller
   - **«USB-feilsøking»** (noen stereoer åpner da ADB på nettet av seg selv).

Stereoens IP-adresse står i T'OS under **Om stereoen → Førstegangsoppsett → Med PC (ADB)**,
eller i Android under Wi-Fi → nettet du er koblet til.

## Steg 3 – Koble PC-en til stereoen

1. Åpne mappa `C:\platform-tools` i Utforsker.
2. Klikk i adresselinjen øverst, skriv `cmd` og trykk Enter. Et svart vindu («Ledetekst») åpnes.
3. Skriv (bytt ut tallene med stereoens adresse):

   **Med «ADB over nettverk»:**
   ```
   adb connect 192.168.1.50:5555
   ```

   **Med «Trådløs feilsøking» (Android 11+):** trykk «Par enhet med paringskode» på stereoen. Den viser
   en adresse med port (f.eks. `192.168.1.50:37123`) og en 6-sifret kode.
   ```
   adb pair 192.168.1.50:37123
   ```
   Skriv koden. Deretter, med adressen og porten som står øverst på siden «Trådløs feilsøking»
   (en annen port):
   ```
   adb connect 192.168.1.50:41234
   ```
4. Stereoen kan spørre **«Vil du tillate USB-feilsøking?»** – kryss av «Tillat alltid» og trykk OK.
5. Sjekk med `adb devices`. Stereoen skal stå som «device».

## Steg 4 – Gi T'OS alle tillatelsene

**Enklest:** last ned [`tos-oppsett.bat`](tos-oppsett.bat) (høyreklikk → «Lagre som») til `C:\platform-tools`
og dobbeltklikk på den. Den spør etter stereoens adresse og kjører alt under.

**For hånd:** skriv eller lim inn linjene under, én og én, i det svarte vinduet.

Knappestyring. Først: se om stereoen har egne tjenester på fra før:
```
adb shell settings get secure enabled_accessibility_services
```
- Står det `null` eller ingenting: skriv
  ```
  adb shell settings put secure enabled_accessibility_services no.bil.launcher/no.bil.launcher.KeyService
  ```
- Står det noe annet (f.eks. `com.stereo/com.stereo.Tjeneste`): ta det med, med kolon imellom, så stereoens
  egne tjenester ikke slås av:
  ```
  adb shell settings put secure enabled_accessibility_services com.stereo/com.stereo.Tjeneste:no.bil.launcher/no.bil.launcher.KeyService
  ```
  (T'OS viser hele den riktige linjen under Førstegangsoppsett → Med PC.)

Resten:
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
```

Hva linjene gjør:

| Linje | Gir T'OS |
|---|---|
| `enabled_accessibility_services`, `accessibility_enabled` | Knappestyring («T'OS knapper»): knappene på stereoen og rattet |
| `allow_listener … MediaListener` | Sangtittel, cover, neste sving og hvem som ringer |
| `SYSTEM_ALERT_WINDOW` | Vise over andre apper (Waze-popupen) |
| `WRITE_SETTINGS` | Lysstyrke, nattdemping, tastelyd og 24-timers klokke |
| `REQUEST_INSTALL_PACKAGES` | Installere oppdateringer av T'OS |
| `ACCESS_RESTRICTED_SETTINGS` | Android 13: fjerner «begrenset innstilling» for apper fra Drive |
| `GET_USAGE_STATS` | «Siste apper» i T'OS viser alle apper du har brukt (ikke bare de T'OS selv har sett) |
| `pm grant …` | Posisjon, Bluetooth, varsel, kontakter, anrop og telefon |
| `network-suggestions-set-user-approved` | Wi-Fi-nett kan legges inn fra T'OS uten spørsmål (Android 11+) |
| `deviceidle whitelist` | Batterisparing stopper ikke T'OS |
| `set-home-activity` | T'OS blir startskjermen |
| `add-role-holder … DIALER` | T'OS blir telefon-appen (samtaler vises i T'OS) |
| `show_navigationbar 0` | **Valgfritt, bare FYT-stereoer.** Skjuler stereoens egen statuslinje (hjem-knapp, klokke, volum, «siste apper») i *alle* apper, ikke bare i T'OS. Angre: `adb shell settings put system show_navigationbar 1` |

**Feilmeldinger er ofte helt greit.** En linje som svarer f.eks. «Unknown permission», «Unknown operation»
eller «not a changeable permission type» gjelder en annen Android-versjon enn stereoen har, og kan overses.

## Steg 5 – Sjekk i T'OS

Åpne **Innstillinger → Om stereoen → Førstegangsoppsett**. Alt du har gitt, har fått en hake (✓).
Hakene oppdateres av seg selv mens du skriver kommandoene.

Til slutt kan du slå av «Trådløs feilsøking» / «ADB over nettverk» igjen hvis du vil (tillatelsene blir værende).

---

### Valgfritt: rette klokka

Vanlige apper får ikke stille klokka. Går den feil:
```
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 1
```

### Oppdateringer

Etter oppsettet trenger du ikke PC-en mer. Oppdateringsknappen i T'OS laster ned og installerer selv.
Første gang ber Android om ett trykk på «Installer»; etter det går oppdateringene av seg selv (Android 12+).
