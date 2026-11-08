# T'OS (bilstereo-launcher) – kontekst for Claude Code

Dette er en egen startskjerm (Android-launcher) for brukerens bilstereo. Les denne fila før du gjør noe.

**Navn: appen heter «T'OS»** (bestemt av brukeren). Bruk det navnet overalt brukeren ser det. Visningsnavnet ligger i `res/values/strings.xml` (`app_name`). Pakkenavnet `no.bil.launcher`, reponavnet og `bilstereo.apk` beholdes med vilje: endres de, kan ikke nye versjoner installeres oppå den gamle, og oppdateringsknappen slutter å virke.
Snakk norsk med brukeren. Han er ikke utvikler – forklar enkelt, ikke bruk fagsjargong unødig.

## Enheten
- LEHX L6 Pro, 7" Android-bilstereo (4 GB RAM / 64 GB, åttekjerners, CarPlay/Android Auto). Levert og i bruk. T'OS er installert via Google Drive.
- BEKREFTET (kartleggingen 8. nov 2026): **FYT-stereo** (com.syu.*-apper, ro.fyt.*, sys.fyt.platform=6315) på **Unisoc UIS7862
  (ums512_1h10_Natv)**. **Ekte Android-versjon er 10 (SDK 29, QP1A.190711.020, sikkerhetsoppdatering 2020-02)** selv om den sier
  «Android 16» – all kode for API ≥ 30/31 tar reserveveien (sjekk den!). Skjerm 1024×600, 160 dpi, tekstskala 1.25.
  T'OS ER standard startskjerm og standard telefon-app. Andre startskjermer: com.android.launcher9 (ro.fyt.launcher)/4/24/35/36,
  com.android.launcher3 (Quickstep, gir «siste apper»).
- FYT-pakker: com.syu.ms (MainServer – sentralen: MCU, radio, BT, lyd; har åpen `app.ToolkitService` «com.syu.ms.toolkit», se
  `Fyt.kt`), com.syu.ss/us/ps (hjelpetjenester; us = «UI Server», trolig FYT sitt volum-/popupvindu), com.syu.radio (FM/AM, brikke
  tda7708; com.syu.carradio er slått av), com.syu.bt (BT-telefon/-musikk, egen BT-brikke – Android ser bare iPhonen som paret,
  ikke tilkoblet), com.syu.carlink («Car Link 2.0» = CarPlay), com.syu.music, com.syu.video, com.syu.av (AUX), com.syu.eq,
  com.syu.canbus, com.syu.steer (rattknapper, «Control»), com.syu.settings, com.syu.onekeynavi («Navi», det NAVI-knappen åpner),
  com.fyt.hidebutton, com.fyt.screenbutton (flytende knapp), com.txznet.* (Toppal-stemme, har et 1×1-vindu).
- Panel-/rattknapper kommer IKKE som Android-tastekoder (bare VOLUME/POWER/HEADSETHOOK finnes); MCU → com.syu.ms åpner apper.
  T'OS ser dem bare som «en app kom fram».
- FYT sin statuslinje (øverst: hjem til venstre; BT/Wi-Fi/klokke/volum/siste apper/tilbake til høyre) styres av
  Settings.System `show_navigationbar` (1). Andre FYT-nøkler: navigationbar_config=0, home_recent_key_enabled=1, car_mode=1.
- Bilen: svart Toyota Avensis.

## Hva brukeren vil ha (viktig – dette er fasiten)
- **Rent og enkelt.** Svart/mørkegrått/hvitt. INGEN neonfarger, ingen cyberpunk, ingen «racing»-stil. Dette er prøvd og forkastet.
- Inspirasjon: et dashbord-design med stort bilbilde, ekte kart, mørke myke kort (han sendte et referansebilde av en «Genesis G80»-dashbord-UI: venstre knapperad, klokke øverst, bilkort med speedometer, kart, kalender, musikk).
- Første forsøk på å kopiere referansen ble kritisert som «det verste du har laget». Årsaker han bekreftet: tegneserieaktig bilsilhuett, falskt kart (rutenett med strek), for mange småkort, klemt speedometer, flate gråtoner.
- Løsningen han godkjente: **tre ting** – (1) bilkort med klokke, stort bilbilde og fart som stort tall, (2) ekte kart, (3) én rad nederst: musikk/telefon/apper. Ingen kalender, ingen vær.
- Kalkulator skal være skjult i app-skuffen (`HiddenApps` i `Apps.kt`).

## Nåværende tilstand (v5.8)
- v5.8: brukeren: «rattknappene gjør ingenting» (Avensis T27: venstre VOL −, demp, VOL +, ▼, ▲, MODE; høyre DISP – virket med
  originalradioen). Han åpnet FYT «Control» (com.syu.steer): 6 tall øverst som endrer seg ved trykk, ALLE funksjoner «NULL» →
  ÅRSAK (bekreftet av bildet): rattknappene er aldri lært i stereoen. Ekstra årsak (gjetning): etter MUSIC-knappen står FYT-kilden
  på 8 (com.syu.music), så neste/forrige gikk til FYT sin spiller, ikke Spotify. (1) Ny side «Rattknapper» (Screen.STEER, `Steer.kt`
  FytSteer/SteerAdc; fra Knapper-kortet «Lær rattknappene» og Innstillinger → Knapper): T'OS lærer knappene I STEREOEN via
  rattmodulen 10 (protokoll fra github.com/cssbruno/cabin, Unlicense, verifisert der mot Joying com.syu.ms: cmd 2 [1]/[0] start/stopp,
  1 [plass] gi tallet nå til funksjonen, 4 lagre, 3 slett alt; update 1 [plass, tall] lært, 2 [tall] fra rattet (50 = ingen),
  3 [kanal, tall] (de 6 tallene), 4 lærer av/på). Plasser: 0 av/på, 1 MODE, 2 demp, 3 vol+, 4 vol−, 5 forrige, 6 neste,
  7 navigasjon (DISP → kart via NAVI-logikken), 8 svar, 9 legg på, 10 spill/pause. Flis → «Hold inne rattknappen nå …» → tallet
  fanges (nytt, ≠ 50, står 400 ms, stereoen sier «lærer») → cmd 1 → ✓ først når stereoen melder lært → «Lagre i stereoen»
  (lagres også når siden lukkes / etter 1 min). Svarer ikke stereoen (15 s / 10 s): melding + «Åpne stereoens eget oppsett».
  «Slett alle lærte» (Trykk igjen) – protokollen har ikke slett per knapp. Test: flis lyser (grønn kant/prikk) av tallet (hvis
  rattet sender tall utenfor læringen) eller av virkningen (volum opp/ned, demp, kilde byttet, neste/forrige).
  (2) Fyt.kt lytter nå også på hoved u=37 (U_PLAYER_CMD), 42, 60, 133 (knapp, open-headunit), ratt 0–40 og canbus 0–50/1000–1036
  (bare logg; tette koder strupes). Nåverdier de første 2,5 s regnes ikke som trykk. Kartleggingen: «Rattknapper», «Rattknapp-læring
  i stereoen», sys.carlink.type. (3) `Steer`: rattknapp fra FYT (u=37/133) → lært Ratt-rad (Buttons «fyt_<knapp>» = «modul/kode/verdi»,
  Learn.offerFyt) ellers SteerLogic.route: radio/BT-musikk/FYT-spiller/u=37 med kilde 10 → stereoen selv; ellers → Media.next/previous/
  playPause (Spotify); svar/legg på bare under samtale; volum/stemme → stereoen. `Steer.claimSource`: når en Android-musikkapp
  spiller / velges / MUSIC åpner Spotify og FYT-kilden er 2/3/8/9 → C_APP_ID 10 (bryter «fyt_steer», Innstillinger → Musikk →
  Rattknapper). KeyService: com.syu.steer/com.syu.canbus byttes aldri ut (SteerLogic.SETUP_PKGS); Android-medietaster vises som
  «Siste rattknapp». Knapper-siden: kort «Rattknapper» (status, «Siste rattknapp», knapp). Tester: SteerLogicTest (9). Totalt 182.
  UVERIFISERT: at UIS7862-firmwaren bruker samme plasser/koder som Joying (Control her viser flere funksjoner), at lagringen holder
  etter omstart, om rattet sender tall utenfor læringen, om FYT sender u=37/133, at C_APP_ID 10 er trygt. BE BRUKEREN: Rattknapper →
  lær alle 7 → Lagre → prøv knappene (også uten T'OS framme) → si hva som virket; ellers kartlegging med opptak.
- v5.7: kartlegging nr. 2 (med opptak, 8. nov 21:58) BEKREFTET: FYT-tjenesten virker (tilkoblet, alle moduler 0–17, meldinger
  kommer). Hoved u=0 = lydkilde/app-id (10 ved start = Android-app, 1 radio, 8 musikkspiller com.syu.music, 2 BT com.syu.bt).
  BT: u=0/1 tittel/artist, u=6 MAC, u=7 «iPhone», u=9=2 tilkoblet (T'OS viser TILKOBLET). Radio: u=1 frekvens 522, u=2 bånd 0
  (båndtallet stemmer ikke – frekvensen avgjør nå: < 2000 = AM kHz, ≥ 6000 = FM /100 → «87,50 MHz»), u=3 0/1 når radioen
  starter, u=14 = forvalgte plasser (ints [nr] / [65536+nr], tomme navn – IKKE stasjonsnavn; vises bare med frekvens/navn,
  `FytLogic.withPreset`, pills på radio-siden → radioPreset(nr), uverifisert), u=13/26 PS/RT tomme. Lyd(4): u=1=6, u=2 = volum
  (15 → 14 … 3 med hjulet), u=3 = demp.
  (1) VOLUM: hjulet endrer IKKE Androids strømmer (musikk 0/25 hele tiden), bare FYT-volumet; com.syu.ms viser eget vindu
  («Popup Window», app-vindu, pakke com.syu.ms, 298,80 428×62, tekst = tallet, hendelser android.view.View + FrameLayout «13»,
  borte etter 3–5 s). `FytVolume.kt`: T'OS-linje (tett mørkt kort, linje, stort tall, «Lyd av» ved demp) som
  TYPE_ACCESSIBILITY_OVERLAY OVER FYT-vinduet (rect fra getWindows(), ellers standard 298,80 428×62 skalert; +6 dp, min 360×64 dp),
  borte 2 s etter siste endring men aldri før FYT-vinduet er borte (WINDOWS_CHANGED, `KeyService.checkFytPopup`), maks 10 s.
  Gjelder også når T'OS er framme. Maks = 40 / høyeste sett («fyt_vol_seen») / valg «fyt_vol_max» (Innstillinger → Lyd, bare FYT).
  På FYT: volumtastene går til stereoen som før, og Androids VOLUME_CHANGED gir ingen T'OS-linje. com.syu.ms-hendelser regnes
  ikke lenger som «app framme» (før: tosFront ble false, RecentApps/last_app). Bryter tos_volume. T'OS sine egne volumglidere
  styrer fortsatt Androids strømmer (ikke FYT – ÅPENT).
  (2) PANELKNAPPENE (bekreftet i opptaket): NAVI → iGO (com.nng.igo.primong.igoworld, = sys.navi.packagename), RADIO →
  com.syu.radio/.act.ActRadio (+ kilde 1), MUSIC → com.syu.music/com.syu.app.Activity_All (+ 8), BT → com.syu.bt/.act.ActBt (+ 2),
  MENU/HOME → T'OS. `FytKeys` (Buttons.kt): disse er standard-pakker når knappen ikke er lært (verken app/kode, ingen annen
  knapp med samme app) – Buttons.pkg gir dem, Knapper-siden viser «Åpner Radio (FYT)», grønn prikk via keyForPkg.
  Buttons.targets(MUSIC) = bare appen T'OS åpner (`musicTarget`; før lå com.syu.music i Musikk-flisa → ingen omdirigering).
  RADIO på FYT (RADIO/RADIO_PAGE): `HwRadio.adopt` uten radiomotor/RadioGuard (kilden er allerede radio), T'OS radio-side.
  BT: venter 400 ms (FYT kan vise samtalebildet i com.syu.bt), og `Buttons.inCall` teller nå også Fyt.inCall.
  NAVI: `NaviKey.geoApps` (alle som åpner «geo:» – Maps, iGO, Waze, Yandex) = REAL_NAV; popupen tilbyr brukerens kartapp
  (`NaviKey.offerFor`), Kartapp-valget lister alle kartapper (`NaviKey.choices`).
  (3) NEDTREKKSMENYEN bekreftet: SystemUI FrameLayout «Notification shade.» (så «Notification shade. | 21:57 | iPhone | Eye
  Comfort | Data | Airplane mode | Off…»), SystemUI-vinduet vokser 1024×60 → 1024×600 med fokus. isShadeEvent: tittelen avgjør
  før «farlige» ord i hurtigknappene. Lukkes på SDK 29 med collapsePanels/BACK. FANEVISNINGEN bekreftet: com.android.launcher3 /
  com.android.quickstep.RecentsActivity «Quickstep» (matcher). (4) FYT-linja: show_navigationbar kan IKKE skrives
  («You cannot keep your settings in the secure settings») → ADB, eller ny knapp «Åpne FYT HideButton» (com.fyt.hidebutton/.Setting,
  stereoens eget oppsett). (5) Kartleggingen: «→ kilde: Radio/Musikkspiller …» på hoved u=0, FYT-knappestandarder, geo-apper.
  Tester: FytLogicTest2 (11). Tester totalt 173. UVERIFISERT: hvor full linja skal være (ekte maks), at kortet faktisk ligger over
  FYT-vinduet, radioPreset-nummeret, om FYT-musikkspilleren (kilde 8) slipper lyden når Spotify åpnes etter MUSIC.
- v5.6: brukeren kjørte «Kartlegg stereoen» (fila: FYT, UIS7862, ekte Android 10 – se «Enheten»). (1) Opptaket stoppet etter 1,1 s:
  `RecordGuide`-kortet dukket opp der fingeren var (over «Start opptak»), så trykk nr. 2 traff «Stopp nå». Nå: kortet nede til
  VENSTRE, trykk de første 3 s teller ikke, og «Stopp nå» må trykkes to ganger (`RecordStop`, «Trykk igjen for å stoppe»).
  Kartleggingen har nye deler: «FYT-tjenesten» (tilkobling, moduler, alle meldinger fra stereoen, get(0..3)), «FYT-statuslinjen»
  (show_navigationbar + skrivetest) og «Vinduer på skjermen nå» (tittel/pakke, f.eks. com.syu.us sine vinduer). FYT-meldinger
  skrives også inn i opptaket. (2) `Fyt.kt`: rå Binder mot com.syu.ms («com.syu.ipc.IRemoteToolkit/IRemoteModule/IModuleCallback»,
  moduler 0 hoved/1 radio/2 BT/4 lyd; kilder i fila: FytRadio, fyt-dialer, FYT-Launcher-Mod, FYTCanbusMonitor). Alt i try/catch, gjør
  ingenting uten com.syu.ms (Robolectric). Brukes til: radio-siden (frekvens/RDS, søk, trinn ±, FM/AM, Start = C_APP_ID 11+1,
  Stopp = 0 – ingen radio-app/radiomotor; bryter «fyt_radio»), BtLink (FYT sier tilkoblet → telefonen vises som tilkoblet),
  samtale (Svar/Avvis/Legg på + banner for samtaler bare FYT vet om, ring via C_DIAL når Android ikke har telefonkonto),
  BT-musikk (C_APP_ID 3 + spill, neste/forrige; bryter «fyt_bt»). Volum fra lydmodulen leses (Fyt.volume) men vises ikke som
  egen T'OS-volumlinje ennå (FYT tegner sin egen – to linjer ville vært verre). UVERIFISERT på enheten: alt FYT-svar –
  BE BRUKEREN KJØRE KARTLEGGINGEN PÅ NYTT (med opptak) og sende fila; delen «FYT-tjenesten» viser om stereoen svarer.
  (3) Android 10: nedtrekksmenyen lukkes med StatusBarManager.collapsePanels (refleksjon, ny tillatelse EXPAND_STATUS_BAR), ellers
  BACK; «StatusBar»-vinduet som vokser til hele skjermen regnes som menyen; varsel øverst (heads-up, < 2,5 s etter et varsel, uten
  hint) regnes ikke. (4) Innstillinger → Skjerm: «Skjul stereoens statuslinje overalt (FYT)» (prøver show_navigationbar=0; Android
  sier trolig nei → viser ADB-linja; angre med 1). Linja ligger også (valgfri, merket) i ADB-lista/ADB-OPPSETT.md/tos-oppsett.bat.
  (5) Apps.kt: FYT-pakkene først i flisene (radio/telefon/BT-musikk/CarPlay), FYT-skallapper skjult i app-skuffen (launcher4/9/24/
  35/36, guide, market, onekeynavi, settings, av, eq, tv, canbus, traceur, com.fyt.*, com.sprd.*, com.unisoc.*, com.txznet.*).
  NaviKey: com.syu.onekeynavi = STOCK_NAV (test). Tester: FytLogicTest (9) + FytSmokeTest (1). Tester totalt 162.
- v5.5: brukeren: «når jeg drar ned fra toppen kommer det originale UI-et (nedtrekksmeny/hurtiginnstillinger)» + «studer
  hvordan originalsystemet virker i detalj så vi kan erstatte 100 %». (1) «Kartlegg stereoen» (`SystemSurvey.kt`,
  Innstillinger → Sikkerhetskopi og hjelp, under «Send oppsettrapport»): Build-felt, utvalgte systemegenskaper
  (SystemProperties via refleksjon, ro.fyt/syu/lsec/ts …) + hele `getprop`, skjermer, startskjerm/standardapper, lyd
  (strømmer, enheter), inndataenheter med alle tastekoder de har (hasKeys), mediaøkter, varsler, Bluetooth,
  Settings.System/Global/Secure, maskinvarefunksjoner, appbruk siste døgn, alle pakker (oversikt) og systemapper/bil-apper
  i detalj (A/S/R/P + intent-filter-handlinger lest fra manifestet via openXmlResourceParser). Fil:
  Android/data/no.bil.launcher/files/kartlegging-<dato>.txt (3 nyeste beholdes), sendes via FileProvider
  (`external-files-path` i file_paths.xml) når >100 KB, ellers som tekst. «Start opptak (2 min)» (`SurveyRecorder`):
  KeyService blir HELT passiv (knapper/volum/vinduer går til stereoen som original) og skriver ned alle
  WINDOW_STATE/WINDOWS_CHANGED/NOTIFICATION-hendelser, vindusliste (type, lag, tittel, pakke, størrelse), alle KeyEvent
  (kode, skankode, enhet) og volum-meldinger; `RecordGuide` = lite T'OS-kort (nede til venstre fra v5.6) med instruksjoner, nedtelling,
  «Stopp nå». Etterpå: T'OS fram (Innstillinger), fila lages og «Send» åpnes. BE BRUKEREN KJØRE OPPTAKET OG SENDE FILA.
  (2) Nedtrekksmenyen i T'OS-stil (`Shade.kt`, bryter «Nedtrekksmeny i T'OS-stil», `tos_shade`, på, Innstillinger →
  Skjerm): i T'OS åpner sveip ned fra øverste kant (28 dp) hurtigpanelet med en gang (Root). Systemvidt: KeyService
  (`noteShade`/`checkShade`) – hint fra SystemUI-hendelse (tittel «Notification shade»/«Varselpanel»/«Quick settings»,
  klasse NotificationShade/QSPanel), vinduslista avgjør (SystemUI TYPE_SYSTEM-vindu med tittel, eller uten tittel som
  dekker ≥60 % av skjermen; andre appers overlegg teller aldri), `ShadeEdge` (bare når den kommer fram), egen
  RedirectLimiter, ikke under samtale/tillatelsesvindu/opptak → GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE (API 31+, ellers
  BACK) → T'OS framme: Nav.quick; ellers `ShadePanel` = hurtigpanelet som Compose-vindu (TYPE_ACCESSIBILITY_OVERLAY,
  egen LifecycleOwner/SavedStateRegistryOwner) over appen; BACK/annen app/T'OS fram lukker det. Krasjet overlegget sist
  (merke «shade_guard» lagret med commit) → reserve: T'OS fram med panelet (`Buttons.show(quick = true)`, `Nav.EXTRA_QUICK`)
  og appen du var i åpnes igjen når panelet lukkes. Handlingen QUICK (knapp) bruker også overlegget. Hurtigpanelet har nå
  «Varsler» (`TosNotifications` fra MediaListener.activeNotifications, `NoteFilter`: ikke T'OS/musikk/samtale/navigasjon/
  tomme/gruppeoversikt; trykk = contentIntent.send (+ markLaunched), X = cancelNotification, «Fjern alle» med «Trykk igjen»,
  «Ingen varsler», uten varseltilgang en rad som åpner den). key_service.xml: + typeWindowsChanged|typeNotificationStateChanged,
  flagRetrieveInteractiveWindows, canRetrieveWindowContent=true (beskrivelsen er oppdatert). «shade_seen» i oppsettrapporten.
  Tester: ShadeSurveyTest (11) + ShadePanelSmokeTest (2) + SurveySmokeTest (1). Tester totalt 152.
  BEGRENSNINGER (ærlig): SystemUI sin nedtrekksmeny kan ikke fjernes av en vanlig app – T'OS lukker den og viser sin egen,
  så et kort glimt kan synes. Lukkingen (GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) krever Android 12+. UVERIFISERT på
  enheten: tittel/klasse/vindu stereoens meny faktisk sender (se «shade_seen» og opptaket), om innkommende varsel øverst
  (heads-up) kan forveksles, og om Android krever at «T'OS knapper» slås av/på etter oppdateringen (ny evne: vinduer).
- `MainActivity.kt`: Compose-UI med de tre tingene + app-skuff + oppdateringsknapp. Skjermvalg via `Nav.screen` (DASH/APPS/BT/KEYS).
- v5.4: brukeren (med bilde): stereoens «nylige apper»-knapp (to firkanter øverst til høyre i stereoens statuslinje) viste
  Androids Overview (blå bakgrunn, «No recent items», søppelbøtte). `Recents.kt`: T'OS sin egen «Siste apper» (Screen.RECENTS):
  store mørke kort (ikon, navn, «sist brukt 5 min siden»), trykk = åpne, X = «Lukket» (killBackgroundProcesses), «Lukk alle»
  med «Trykk igjen» – beholder T'OS, CarPlay/Car link og musikken som spiller (sier hvilke). Data: UsageStatsManager (12 t,
  krever «Tilgang til appbruk»: Førstegangsoppsett-steg / ADB `appops set … GET_USAGE_STATS allow`), ellers T'OS sin egen liste
  (`RecentApps.record` i KeyService når en app kommer fram + AppRepository.launch, Prefs «recent_apps»). KeyService:
  `RecentsLogic.isStockRecents` (klasse RecentsActivity/Overview, eller tekst «No recent items»/«Overview»/«Oversikt» – bare fra
  startskjerm/SystemUI/launcher-pakker) og KEYCODE_APP_SWITCH → svart teppe + T'OS sin side (RedirectLimiter, ikke under samtale).
  Bryter «Siste apper i T'OS-stil» (tos_recents, på) i Innstillinger → Apper; av = som før (fanelista er bare innom, isPassingWindow).
  Også: langt trykk på «Apper» på forsiden, «Siste» i app-skuffen, knappehandling Action.RECENTS. Test RecentsLogicTest (10) +
  RecentsSmokeTest. Tester totalt 138. BEGRENSNING (ærlig): stereoens fanevisning er SystemUI/Quickstep – T'OS kan ikke tegne den
  om, bare dekke over og bytte den ut; et kort glimt kan fortsatt synes, og stereoens statuslinje i andre apper kan ikke skjules.
  «Lukket» = stoppet i bakgrunnen (apper med egen bakgrunnstjeneste kan starte igjen selv). UVERIFISERT på enheten: hvilket
  klassenavn/tekst stereoens fanevisning sender – be brukeren sende oppsettrapporten hvis den fortsatt dukker opp.
- v5.3: Knapper-siden: `KeyPing` (Buttons.kt) – grønn prikk (eneste farge, brukerens ønske) på raden T'OS kjente igjen
  (tastekode eller app knappen åpnet) i 1,5 s + «MENU (kode 21)»/«Ukjent knapp (kode …)» øverst; vises også for knapper satt
  til «Ingenting». Sjåførforbedringer: fart «–» uten/mistet GPS (`speedText`/`gpsCaption`), større tekst på forsiden, lysere
  grå om natten, `Startup.shouldResumeMusic` (ikke over CarPlay/radio/samtale), tilbake til forsiden etter oppstartsmusikk bare
  hvis musikkappen fortsatt er framme (`KeyService.frontPackage`), forklaring på alle brytere, ScreensSmokeTest2 + UiLogicTest4.
  Tester totalt 127.
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
  UUNNGÅELIG systemvisning (Android krever det): tillatelsesdialoger (posisjon, Bluetooth, telefon, varseltilgang, tilgang til appbruk, tilgjengelighet,
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
