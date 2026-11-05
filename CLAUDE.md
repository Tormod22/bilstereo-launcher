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

## Nåværende tilstand (v5.2)
- `MainActivity.kt`: Compose-UI med de tre tingene + app-skuff + oppdateringsknapp. Skjermvalg via `Nav.screen` (DASH/APPS/BT/KEYS).
- v5.2: brukeren: «Spotify virker nå» + «NAVI-knappen har fortsatt bare den originale funksjonen». Årsak: stereoen åpner
  Google Maps selv; var NAVI lært til Maps, stoppet KeyService på «pkg in targets» (ville gått i ring) → ingenting skjedde.
  `NaviKey.kt`: når kartapp kommer fram uten at T'OS åpnet den (lært NAVI eller ikke) → Waze/Maps-popup (maks 1/min);
  stereoens egen navi-systemapp (navi/nav/gps/导航 i navn) eller lært ikke-kartapp → T'OS sin NAVI (openMaps). Prefs «navi_detect»
  (Innstillinger → Navigasjon). Test NaviKeyTest (3).
- v5.1 (brukeren meldte: «ingen lyd på Spotify, YouTube virket» + «T'OS sier telefonen ikke er tilkoblet, iPhonen sier den er det»):
  `BtLink.kt` – Bluetooth i lag: profiler (A2DP/HEADSET + bil-profilene A2DP_SINK 11, HFP-klient 16, AVRCP 12), parede
  isConnected, ACL-/tilkoblingsmeldinger (registrert fra Hub), stereoens egen BT-app (varsel «Tilkoblet»/mediaøkt).
  Finnes egen BT-app og Android ser ingenting → BtState.VENDOR «Bluetooth styres av stereoen – sjekk telefonen» (aldri
  «ikke tilkoblet»). OBD-brikka regnes ikke som telefonen. `BtAudio` («Bluetooth-lyd fra telefonen», musikksiden/BT-siden/
  kildelista): BT-økt «spill» → mediatjeneste/medieknapp → BT-musikk-tjeneste → «BT-motor» (stereoens BT-musikk bak svart
  teppe, Prefs «bt_engine», bryter i Innstillinger → Musikk). `PlaybackWatch` (MediaListener.kt): app begynner å spille →
  Spotify Connect/annen enhet (playbackType REMOTE) gir melding + linje på musikksiden, FM-radio slått på i T'OS slipper
  kanalen (`HwRadio.yieldTo`, uten lydfokus), «Volumet er av». `pauseOthers` pauser aldri REMOTE-økter (ville stoppet
  telefonen) eller BT-økter når Bluetooth velges; RadioGuard tar ikke lydfokus når annen musikk spiller / brukeren bytter
  kilde (`powerOff(keep=)`). Oppsettrapporten har ny del «Bluetooth og lyd» – BE BRUKEREN SENDE DEN. Tester totalt 117
  (BtLogicTest 13). Alt UVERIFISERT på enheten.
- v3.0 «stor oppdatering» – alle 50 punktene i `docs/IDEER.md` (se Begrensninger der). Nye filer:
  `Prefs.kt` (TosApp, innstillinger m/ Compose-reaktivitet via Prefs.version, sikkerhetskopi JSON, CrashLog),
  `Theme.kt` (palett som getters – dag/natt; Sun-beregning), `Hub.kt` (GPS-singleton, Trip, Parking, SpeedLimit/Overpass,
  NightBrightness, Reminders, Startup, Navigate), `MediaListener.kt` (NotificationListener: NowPlaying, NavInfo, Call; Media-kontroll),
  `Obd.kt` (ELM327 via RFCOMM), `Radio.kt` (nettradio via MediaPlayer), `Pages.kt` (Musikk, Radio, Navigasjon, Kart, Telefon,
  Bil og tur, Innstillinger, Klokke, Hurtigpanel + felles UI-klosser). Lint (lintDebug) er ren. v4.0 (etter gjennomgang av OpenLauncher, MIT – se docs/TREDJEPART.md): ekte FM/AM-radio (`HwRadio` i Radio.kt:
  szchoiceway-MCU eller radio-appens mediaøkt), app-søk finner bil-apper uten startikon + navnesøk (brukerens CarPlay-app
  heter «Car link 2.0», pakkenavn ukjent), egne valg for CarPlay/radio/telefon/kamera-app (Innstillinger → Apper),
  Wi-Fi/Bluetooth-paring/equalizer inne i T'OS (`SettingsExtra.kt`), førstegangsoppsett (Screen.SETUP), fartsjustering.
  Play Protect blokkerer installasjon fra Drive pga. tilgjengelighet + varseltilgang – brukeren må skru av Play Protect midlertidig
  eller bruke ADB. Lokale bygg i denne sesjonen ble signert med den faste nøkkelen (fra scratchpad, ikke repo).
  Tester: `app/src/test/.../LogicTest.kt` (11 JUnit-tester: sol/nattmodus, fartsgrense, radiofrekvens, datoer, kompass,
  batteri) kjøres i build.yml før bygging. Hold dem grønne; legg til test når ren logikk endres.
  CI-status 3. nov: bygg #2 stoppet på «Sett opp fast signatur» = secrets mangler (brukeren må legge dem inn).
  Steget tåler nå mellomrom/linjeskift og sjekker nøkkel+passord med keytool (testet lokalt med ekte verdier).
  GitHub Pages er ikke slått på (testsiden gir 404) – valgfritt.
  v5.0: røyktester med Robolectric 4.13 (`ScreensSmokeTest.kt`: alle 14 Screen i dag/natt, alle innstillingskategorier, hurtigpanel,
  app-velger, tastatur, volum, Notify, samtalebanner, CallScreen) – fant og rettet null-krasj i Wi-Fi-lista. MapLibre kan ikke testes i JVM.
  Ytelse: `CarArt` (bilbilde dekodes én gang på IO), klokke rekomponerer per minutt, `SpeedRow` egen, EQ/HwRadio/opprydding etter første bilde.
  CI: setup-gradle@v5, action-gh-release@v3, upload-artifact@v6, Pages-actions oppdatert, runs-on ubuntu-24.04. Tester totalt 104.
  v4.9: bilbildet er lagt inn: `res/drawable-nodpi/car_png.png` (brukerens Canva-bilde av svart Avensis forfra, beskåret,
  svart bakgrunn gjort gjennomsiktig med myk kant, 1043x755). Releasen 4.8.4 (versionCode 1004) ble publisert 5. nov – secrets virker.
  v4.8: «T'OS er hele brukerflaten» – stereoens originale UI skal ikke dukke opp (4 parallelle grener, flettet og koblet sammen).
  UI-skall (`TosUi.kt`, `Keyboard.kt`): egen statuslinje (TosStatusBar), eget tastatur (alle tekstfelt via `Field`),
  egne meldinger (`Notify`/NoticeHost i stedet for Toast), eget volumvindu (VolumeUi/VolumeBar), stereoens status-/navigasjonslinje
  skjult (Prefs «hide_system_bars», standard på; brytes av/på live i Innstillinger → Skjerm). Volum: én kilde – med knappestyringen på
  viser Root `HwVolume` (shownAt), ellers ContentObserver; alle T'OS-glidere kaller `HwVolume.markOwnChange()` og holder Ui.volume.
  Tjenester: radiomotor (`HwRadio.powerOn/powerOff/openApp`, radio-appen startes bak svart teppe om nødvendig, «radio_engine»),
  volumknappene fanges av KeyService (`HwVolume`, «tos_volume»), `TosOverlay` (volum/meldinger/teppe over andre apper),
  skall-omdirigering med `RedirectLimiter`, stereoens radio-app skjules («hide_stock_radio»), systemapper skjult i app-lista
  («show_system_apps», app-skuffen leser lista på nytt når den endres). Musikksiden har kildevalg (`Media.sources/selectSource`:
  musikkapp, Bluetooth, FM-radio, nettradio). Radio-siden: Start radio/Stopp; «Åpne original radio-app» bare som liten reserve nederst.
  Telefon: `TosInCallService` + `CallActivity` (CallScreen.kt) + `DialerActivity` + `Phone.kt` (kontakter, siste anrop, favoritter);
  PhoneCallBanner i Root; når T'OS ikke er telefon-app har CallBanner Svar/Avvis (`Call.answer/decline`) hvis varselet har knappene.
  Innstillinger: egne sider i T'OS (Wi-Fi, Bluetooth, lyd, skjerm, apper, lagring, tid, om), stille selvoppdatering via
  PackageInstaller-økt + `UpdateReceiver` (første gang kan Android be om «Installer» én gang), ADB-oppsett i `docs/ADB-OPPSETT.md`
  + `docs/tos-oppsett.bat`, `LocalBackup` (Backup.kt, sikkerhetskopi i T'OS-mappa). Nye brytere: «Volum i T'OS-stil» (Lyd),
  «Skjul stereoens statuslinje» (Skjerm), «FM-radio i bakgrunnen (radiomotor)» + «Skjul stereoens radio-app» (Musikk og oppstart).
  UUNNGÅELIG systemvisning (Android krever det): tillatelsesdialoger (posisjon, Bluetooth, telefon, varseltilgang, tilgjengelighet,
  «Vis over andre apper», skrive systeminnstillinger, ukjente kilder), slå på Bluetooth / parekode, avinstaller-bekreftelse,
  første installasjon/oppdatering hvis Android krever bekreftelse, «Velg startskjerm», CarPlay/Car link, ryggekamera, Androids
  egne innstillinger under Om stereoen → Avansert / «Avansert (Android)» per app, og stereoens telefon-/radio-app bare som reserve
  (ingen telefon T'OS kan ringe med / «Åpne original radio-app»). Uten knappestyringen kan stereoen fortsatt vise eget volumpanel.
  Tester totalt 96. Alt UVERIFISERT på enheten.
  v4.7: runde 2 «finn 50 feil/mangler og rett» → 59 rettet (3 agenter/worktrees). UI: 48 dp trykkflater, contentDescription,
  bekreftelse før sletting/nullstilling, kartskala fast per flis, skjermsparer ikke under navigasjon, NaviPopup følger palett.
  Tjenester: lang-trykk/FLAG_CANCELED/ACTION_MULTIPLE, samtale-taster går til telefonen (passToCall), launchedByUs 8 s +
  forgetLaunched, RadioGuard.mayClose, Prefs skriver på hovedtråd, CrashLog flere krasj, utvidet SetupReport.
  Data: OBD tømmer buffer, NO DATA-logikk, PID 59 som reserve for rail, Engine.onConnect/read, usikker RFCOMM-reserve,
  OBD hviler når skjermen er av, Overpass maks hvert 8. s + pause ved 429, oppdateringssjekk med økende ventetid,
  PMTiles-header sjekkes, GpsService krasjer ikke, versjonsnavn fra APK i build.yml. Tester totalt 58.
  ÅPENT: Buttons.inCall = alle lydmodus ≠ NORMAL – hvis CarPlay holder IN_COMMUNICATION slås omdirigering av (sjekk oppsettrapporten).
  v4.6: brukeren HAR en tuningboks: ChipPower CRS, kabel KCR18 (diesel common rail, sitter på rail-trykksensoren,
  ingen app/Bluetooth, styrke via skrue + grønn LED). Tuning-siden: «Min tuningboks» (TuneBox, trinn 0=av/1–10 føres for hånd),
  TuneLog (hver 0–100/80–120 lagres med trinn, beste per trinn), «Motordata» via samme OBD2-brikke som batteriet
  (Engine i CarBattery.kt: PID 0C turtall, 05 kjølevæske, 0B+33 ladetrykk, 23 rail-trykk; bare mens siden er åpen).
  Brukeren har trolig IKKE kjøpt OBD-brikke ennå. Test: TuningLogicTest (3).
  v4.5: «finn 50 feil» → 49 funn rettet (3 agenter i hver sin worktree). Viktigst: GPS ber om FINE+COARSE, GpsService
  (forgrunnstjeneste type location), GPS blir «gammel» etter 3 s, SpeedLimit Found/NoneTagged/Failed + nærmeste vei,
  KeyService omdirigerer bare når appen KOMMER fram (FrontTracker) og T'OS-åpnede apper er «eid» til man forlater dem,
  NetRadio lydfokus gjenbrukes, RadioGuard rører ikke NetRadio, BACK avbryter læring + 15 s tidsgrense i Learn,
  sikker importJson, CallBanner bare innkommende, ringing uten SIM åpner BT-telefonappen, kart maks zoom 16 + forstørring,
  oppdatering i Hub.scope (UpdateState), CI bygger RELEASE (assembleRelease, app-release.apk) med apksigner-sjekk,
  cancel-in-progress false, valgfri secret TOS_KEY_PASSWORD. Tester: LogicTest 11 + UiLogicTest 2 + ServiceLogicTest 10
  + DataLogicTest 11 = 34. Alt UVERIFISERT på enheten.
  v4.4: Updater sjekker nedlastet APK før installasjon (pakkenavn, nyere versionCode, samme signatur som installert T'OS)
  og gir en forståelig melding i stedet for Androids «App ikke installert». Test: signatur_sammenlignes (11 tester).
  v4.3: SafeMode (3 krasj på 3 min → uten MapLibre/bilbilde/oppstartsmusikk), SetupReport («Send oppsettrapport»
  i Innstillinger → Sikkerhetskopi og hjelp: pakker, knappekoder, tillatelser – be brukeren sende denne!), build.yml uten
  setup-android (feilet på «tools»), kartstørrelse via Range 0-0 (GitHub-CDN gir 401 på HEAD). PR #1 er flettet 3. nov;
  «kart»-release finnes (rogaland.pmtiles 178,7 MB).
  v4.2: full gjennomgang (3 agenter, ~60 funn) rettet: NetRadio-krasj, ikon-LRU per pakke + async, pakkesøk på IO,
  knappekode 0/-1, «Ingenting» gir knappen tilbake til stereoen, ingen omdirigering fra apper T'OS selv åpnet
  (AppRepository.launchedByUs), keep_home bare på selve HOME-aktiviteten, BACK på forsiden gjør ingenting,
  NightBrightness i Prefs, Trip i ms, datoer strengt, robust Updater (200/størrelse/installer-tillatelse) og
  kartnedlasting (Range+If-Range, plass, trygg bytting), CI: versionCode = 1000 + GITHUB_RUN_NUMBER, concurrency,
  version.json lastes opp sist, taggen «latest» flyttes. Mye UVERIFISERT på enheten.
  v4.1: brukeren meldte (1) T'OS forsvinner ved «fjern alle faner» → excludeFromRecents + KeyService viser T'OS når original
  startskjerm dukker opp («Hold T'OS som startskjerm», keep_home; com.android.settings m.fl. unntatt), (2) RADIO-knappen åpner
  stereoens radio-app et øyeblikk og radioen fortsetter med skurr → `RadioGuard` i Radio.kt stopper den (mediaøkt, MCU, lydfokus,
  killBackgroundProcesses) og starter forrige musikk igjen. Begge UVERIFISERT på enheten.
  v3.5: Waze-popup (`NaviPopup.kt`). v3.4: spenning på bilbatteriet (`CarBattery.kt`): OBD2-brikke kun for spenning (ATRV) – brukeren godtok dette etter at OBD
  ellers ble fjernet – ellers stereoens egen spenning hvis troverdig (9–16 V). Vises ved «Avensis» i bilkortet, varsel <11,8 V / >15 V.
  v3.3: egen innstillingsapp (`SettingsApp.kt`, to kolonner, kategorier «Stereoen» + «T'OS»; Android-innstillinger skjult
  fra app-lista, nås via Om stereoen → Avansert). OBD2 er FJERNET etter ønske; erstattet av «Tuning og ytelse» (`Tuning.kt`:
  åpner tuningboks-app, f.eks. RaceChip `com.racechip.plus`, + 0–100/80–120 målt med GPS + info om Vegvesen-godkjenning).
  T'OS kan ikke selv omprogrammere motorstyringen – ikke lov noe sånt. Ikoner: ALLE apper får mørke ikoner (`Icons.kt`:
  tema-ikon → ellers automatisk symbol: fjern hovedfarge, eller fargede deler = symbol når ikonet er flerfarget; testet på
  YouTube/Spotify/Chrome/Gmail/Maps/WhatsApp/Netflix). Brukeren kan skjule apper i Innstillinger → Apper.
  v3.2: kart uten nett (`OfflineMap.kt`, MapLibre 11.8.0, PMTiles fra Protomaps, mørk stil, fonter i assets/fonts)
  – fila lages av `.github/workflows/kart.yml` (release-tag «kart», rogaland.pmtiles) og lastes ned i Innstillinger.
  Stilen er testet med ekte data i MapLibre GL JS (ser bra ut), men ikke på enheten. App-ikoner i mørkt tema (`Icons.kt`),
  T'OS-ikonet er nå hvitt/grått på svart. v3.1: sikkerhetslåsen (punkt 43) er fjernet etter brukerens ønske – ikke legg den inn igjen.
- `Buttons.kt` + `KeyService.kt` + `Screens.kt` (v2.3, utvidet i v3.0 med langt trykk og Ratt 1–4): fysiske knapper på stereoen (MENU, BACK, NAVI venstre; RADIO, MUSIC, BT høyre).
  Standard: MENU→T'OS forside, BACK→tilbake, NAVI→kart (+ Waze-popup), MUSIC→Spotify, BT→egen Bluetooth-side, RADIO→T'OS sin Radio-side (RADIO_PAGE; brukeren har IKKE bestemt RADIO endelig – valgbar på Knapper-siden).
  «Lær»-funksjon: lærer enten tastekoden knappen sender, eller hvilken app stereoen åpner (da omdirigerer tjenesten). Krever at tjenesten «T'OS knapper» er slått på under Tilgjengelighet. Omdirigering hoppes over når AudioManager.mode != NORMAL (samtale).
  UVERIFISERT på enheten: om knappene sender tastekoder eller åpner apper. Be brukeren lære knappene og si hva Knapper-siden viser (kode/pakkenavn).
- `Data.kt`: GPS-fart/posisjon (LocationManager), ekte kartfliser fra Esri World Dark Gray Base (gratis, ingen nøkkel; CARTO krever nøkkel nå), sentrert på posisjonen og dempet med MapDim, mediaknapper via AudioManager.dispatchMediaKeyEvent.
- `Updater.kt`: sjekker `https://github.com/Tormod22/bilstereo-launcher/releases/download/latest/version.json`, laster ned `bilstereo.apk` og installerer via PackageInstaller-økt (svar i `UpdateReceiver`; reserve: FileProvider + Androids installasjonsbilde). Repoet må være OFFENTLIG for at dette virker.
- `.github/workflows/build.yml`: bygger signert RELEASE-APK (assembleRelease → bilstereo.apk) på push til main og publiserer som release med tag `latest` (bilstereo.apk først, version.json sist). versionCode = 1000 + GITHUB_RUN_NUMBER settes automatisk; bump bare versionName (og lokal versionCode) i `app/build.gradle.kts`.
- `preview/index.html`: nettleserkopi av startskjermen for å teste utseendet (samme farger/mål/kart). Holdes i takt med `MainActivity.kt`. Publiseres til GitHub Pages av `.github/workflows/preview.yml`.
- Bilbilde: ligger i `app/src/main/res/drawable-nodpi/car_png.png` – koden finner den via `getIdentifier("car_png")`. Et bilde er generert i Canva (svart Avensis forfra på svart bakgrunn): https://www.canva.com/M/MAHZyi7AvB4 – brukeren må laste det ned selv og legge det inn.

## Oppdatering / signering
- Updater forutsetter release «latest» (lages av build.yml på push til main). Første bygg på main (3. nov) feilet i setup-android – rettet i v4.3.
- Signering: build.yml leser secrets TOS_KEYSTORE_BASE64 + TOS_KEYSTORE_PASSWORD (alias «tos») og feiler uten dem.
  Nøkkelen må ALDRI committes (forsøk på det ble stoppet som credential leakage). Brukeren har fått nøkkelen som tekstfil
  (tos-hemmeligheter.txt) og skal ta vare på den. APK-er fra 3.5–4.2 er signert med denne nøkkelen (lokalt i scratchpad).
  APK-er bygget uten nøkkelen (TOS_KEYSTORE ikke satt) får debug-signatur og kan IKKE oppdatere T'OS på stereoen.
- Versjon: GitHub-bygg får versionCode 1000 + byggnummer (alltid høyere enn lokale bygg, som har lave tall).

## Status / gjenstår
1. ✅ Koden kompilerer (`gradle assembleDebug`, AGP 8.5.2 / Kotlin 2.0.20). Én feil rettet i `Updater.kt` (lese version.json).
2. ✅ Repoet `Tormod22/bilstereo-launcher` finnes. Kartfila krever at workflowen «Lag kart uten nett» er kjørt (går automatisk ved første push til main). Koden må ligge på `main` for at GitHub Actions skal bygge og publisere «latest»-release. Repoet må være offentlig for at oppdateringsknappen skal virke.
3. ✅ `car_png.png` lagt inn (v4.9, drawable-nodpi).
4. Når enheten kommer: verifiser pakkenavn, test GPS/kart, test «åpne Maps i delt skjerm» (FLAG_ACTIVITY_LAUNCH_ADJACENT – kan falle tilbake til fullskjerm).
5. Idéliste med 50 funksjoner: `docs/IDEER.md` (★ = foreslått først). Tidligere ønsker: sangtittel/cover i musikkortet (krever NotificationListener), ryggekamera-knapp.

## Startskjerm-valget
- Stereoen spurte IKKE om startskjerm ved Hjem-trykk (original launcher låst/valg skjult). v2.1 har knappen «Velg startskjerm» i app-skuffen (ACTION_HOME_SETTINGS → standardapper → innstillinger). Siste utvei: ADB `cmd package set-home-activity no.bil.launcher/.MainActivity`. Ikke deaktiver original launcher uten at vår er bekreftet virkende.

## Installasjon på stereoen (brukeren har ingen minnepinne, har iPhone)
- Foretrukket: last ned APK fra GitHub-release i nettleseren på stereoen, eller via Google Drive.
- Alternativ: ADB over Wi-Fi (`adb connect <ip>:5555`, `adb install -r bilstereo.apk`).
- Senere oppdateringer: knappen i «Apper» i selve launcheren.
