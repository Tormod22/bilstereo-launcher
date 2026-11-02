# T'OS (v2.3)

T'OS er navnet på appen. Repoet heter fortsatt `bilstereo-launcher`.

Egen startskjerm for Android-bilstereoer (testet-mål: LEHX L6 Pro, 7", 4+64).

## Hva den gjør (v2.0 – tre ting)
- **Bilen**: klokke/dato, stort bilde av bilen, og fart fra GPS som stort tall
- **Kart**: ekte kart (mørkegrå fliser fra Esri, gratis, ingen nøkkel) rundt der du er,
  med posisjonen din som prikk. Trykk åpner Google Maps/Waze – i delt skjerm der enheten støtter det
- **Én rad nederst**: musikk (forrige / spill-pause / neste styrer det som spiller), Telefon, Apper
- App-skuff (kalkulator o.l. skjult – se `HiddenApps` i `Apps.kt`)
- Svart/grått/hvitt, ingen farger. Landskap, fullskjerm, skjermen holdes på

Appen ber om posisjon første gang. Uten GPS viser farten 0 og kartet «Venter på GPS».

## Bilde av bilen
Legg et bilde av bilen (forfra, mot mørk/svart bakgrunn, PNG med gjennomsiktig eller svart bakgrunn)
i `app/src/main/res/drawable/car_png.png`. Da brukes det automatisk. Uten bilde vises en dempet bil-ikon.

## Oppdatere fra stereoen
Åpne «Apper» → knappen øverst til høyre viser «Oppdater til x.y» når et nytt bygg ligger på GitHub.
Trykk, vent på nedlasting, godta installasjon. Første gang må du tillate «installer ukjente apper» for T'OS.

## Bygge
1. Åpne mappa i Android Studio (Ladybug eller nyere)
2. La Gradle synkronisere
3. Build → Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`

## Installere på bilstereoen
Enten kopier APK-en til en minnepinne og åpne den i enhetens filbehandler,
eller via ADB (slå på Utvikleralternativer → USB-/Wi-Fi-feilsøking først):

    adb connect <stereo-ip>:5555
    adb install -r app-debug.apk

Trykk deretter Hjem-knappen, velg «T'OS» og «Alltid». Spør den ikke, åpne T'OS → Apper → «Velg startskjerm».

## Tilpasse
- Fliser og hvilke apper de åpner: `Apps.kt` → `Tiles.defaults`
- Farger: øverst i `MainActivity.kt`
- Finn pakkenavnet til en app på stereoen: `adb shell pm list packages | grep -i radio`

## Knappene på stereoen (MENU, BACK, NAVI, RADIO, MUSIC, BT)
Apper → «Knapper». Slå på knappestyring (Innstillinger → Tilgjengelighet → «T'OS knapper»).
Trykk «Lær» ved en knapp og trykk så knappen på stereoen. Velg hva knappen skal gjøre i lista.
Android 13: hvis valget er grått, gå til Innstillinger → Apper → T'OS → ⋮ → «Tillat begrensede innstillinger» først.

## Teste utseendet før du installerer
`preview/index.html` er en kopi av startskjermen som kjører i nettleseren (PC eller mobil på tvers).
Åpne fila direkte, eller bruk https://tormod22.github.io/bilstereo-launcher/ (når GitHub Pages er slått på).
Øverst er et testpanel: skjermstørrelse, simulert kjøring med fartsregulator, ekte GPS, og «Velg bilbilde…»
for å se hvordan bilbildet ditt tar seg ut. Endrer du utseendet i `MainActivity.kt`, endre det samme her.
