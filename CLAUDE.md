# T'OS (bilstereo-launcher) – kontekst for Claude Code

Dette er en egen startskjerm (Android-launcher) for brukerens bilstereo. Les denne fila før du gjør noe.

**Navn: appen heter «T'OS»** (bestemt av brukeren). Bruk det navnet overalt brukeren ser det. Visningsnavnet ligger i `res/values/strings.xml` (`app_name`). Pakkenavnet `no.bil.launcher`, reponavnet og `bilstereo.apk` beholdes med vilje: endres de, kan ikke nye versjoner installeres oppå den gamle, og oppdateringsknappen slutter å virke.
Snakk norsk med brukeren. Han er ikke utvikler – forklar enkelt, ikke bruk fagsjargong unødig.

## Enheten
- LEHX L6 Pro, 7" Android-bilstereo (4 GB RAM / 64 GB, åttekjerners, CarPlay/Android Auto). Levert og i bruk. T'OS er installert via Google Drive.
- Kjører sannsynligvis Android 12–13 på Unisoc (UIS7862/UIS8581) eller Allwinner. Pakkenavn for innebygd radio/BT-telefon/CarPlay er UKJENT inntil enheten kommer – se `Tiles.defaults` i `Apps.kt`, der ligger gjetninger. Be brukeren kjøre `adb shell pm list packages` når den kommer, og juster.
- Bilen: svart Toyota Avensis.

## Hva brukeren vil ha (viktig – dette er fasiten)
- **Rent og enkelt.** Svart/mørkegrått/hvitt. INGEN neonfarger, ingen cyberpunk, ingen «racing»-stil. Dette er prøvd og forkastet.
- Inspirasjon: et dashbord-design med stort bilbilde, ekte kart, mørke myke kort (han sendte et referansebilde av en «Genesis G80»-dashbord-UI: venstre knapperad, klokke øverst, bilkort med speedometer, kart, kalender, musikk).
- Første forsøk på å kopiere referansen ble kritisert som «det verste du har laget». Årsaker han bekreftet: tegneserieaktig bilsilhuett, falskt kart (rutenett med strek), for mange småkort, klemt speedometer, flate gråtoner.
- Løsningen han godkjente: **tre ting** – (1) bilkort med klokke, stort bilbilde og fart som stort tall, (2) ekte kart, (3) én rad nederst: musikk/telefon/apper. Ingen kalender, ingen vær.
- Kalkulator skal være skjult i app-skuffen (`HiddenApps` i `Apps.kt`).

## Nåværende tilstand (v2.3)
- `MainActivity.kt`: Compose-UI med de tre tingene + app-skuff + oppdateringsknapp. Skjermvalg via `Nav.screen` (DASH/APPS/BT/KEYS).
- `Buttons.kt` + `KeyService.kt` + `Screens.kt` (v2.3): fysiske knapper på stereoen (MENU, BACK, NAVI venstre; RADIO, MUSIC, BT høyre).
  Standard: MENU→T'OS forside, BACK→tilbake, NAVI→kart, MUSIC→Spotify, BT→egen Bluetooth-side, RADIO→radio-appen (brukeren har IKKE bestemt RADIO ennå – valgbar på Knapper-siden).
  «Lær»-funksjon: lærer enten tastekoden knappen sender, eller hvilken app stereoen åpner (da omdirigerer tjenesten). Krever at tjenesten «T'OS knapper» er slått på under Tilgjengelighet. Omdirigering hoppes over når AudioManager.mode != NORMAL (samtale).
  UVERIFISERT på enheten: om knappene sender tastekoder eller åpner apper. Be brukeren lære knappene og si hva Knapper-siden viser (kode/pakkenavn).
- `Data.kt`: GPS-fart/posisjon (LocationManager), ekte kartfliser fra Esri World Dark Gray Base (gratis, ingen nøkkel; CARTO krever nøkkel nå), sentrert på posisjonen og dempet med MapDim, mediaknapper via AudioManager.dispatchMediaKeyEvent.
- `Updater.kt`: sjekker `https://github.com/Tormod22/bilstereo-launcher/releases/download/latest/version.json`, laster ned `bilstereo.apk` og starter installasjon via FileProvider. Repoet må være OFFENTLIG for at dette virker.
- `.github/workflows/build.yml`: bygger debug-APK på push til main og publiserer som release med tag `latest` (bilstereo.apk + version.json). Versjon hentes fra `versionCode`/`versionName` i `app/build.gradle.kts` – bump dem ved hver ny versjon.
- `preview/index.html`: nettleserkopi av startskjermen for å teste utseendet (samme farger/mål/kart). Holdes i takt med `MainActivity.kt`. Publiseres til GitHub Pages av `.github/workflows/preview.yml`.
- Bilbilde: legges i `app/src/main/res/drawable/car_png.png` – koden finner den via `getIdentifier("car_png")`. Et bilde er generert i Canva (svart Avensis forfra på svart bakgrunn): https://www.canva.com/M/MAHZyi7AvB4 – brukeren må laste det ned selv og legge det inn.

## Status / gjenstår
1. ✅ Koden kompilerer (`gradle assembleDebug`, AGP 8.5.2 / Kotlin 2.0.20). Én feil rettet i `Updater.kt` (lese version.json).
2. ✅ Repoet `Tormod22/bilstereo-launcher` finnes. Koden må ligge på `main` for at GitHub Actions skal bygge og publisere «latest»-release. Repoet må være offentlig for at oppdateringsknappen skal virke.
3. Legg inn `car_png.png`.
4. Når enheten kommer: verifiser pakkenavn, test GPS/kart, test «åpne Maps i delt skjerm» (FLAG_ACTIVITY_LAUNCH_ADJACENT – kan falle tilbake til fullskjerm).
5. Mulige senere ønsker: sangtittel/cover i musikkortet (krever NotificationListener), ryggekamera-knapp.

## Startskjerm-valget
- Stereoen spurte IKKE om startskjerm ved Hjem-trykk (original launcher låst/valg skjult). v2.1 har knappen «Velg startskjerm» i app-skuffen (ACTION_HOME_SETTINGS → standardapper → innstillinger). Siste utvei: ADB `cmd package set-home-activity no.bil.launcher/.MainActivity`. Ikke deaktiver original launcher uten at vår er bekreftet virkende.

## Installasjon på stereoen (brukeren har ingen minnepinne, har iPhone)
- Foretrukket: last ned APK fra GitHub-release i nettleseren på stereoen, eller via Google Drive.
- Alternativ: ADB over Wi-Fi (`adb connect <ip>:5555`, `adb install -r bilstereo.apk`).
- Senere oppdateringer: knappen i «Apper» i selve launcheren.
