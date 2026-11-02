# Bilstereo-launcher (v2.0)

Egen startskjerm for Android-bilstereoer (testet-mål: LEHX L6 Pro, 7", 4+64).

## Hva den gjør (v2.0 – tre ting)
- **Bilen**: klokke/dato, stort bilde av bilen, og fart fra GPS som stort tall
- **Kart**: ekte kart (mørke fliser fra CARTO/OpenStreetMap, gratis, ingen nøkkel) rundt der du er,
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
Trykk, vent på nedlasting, godta installasjon. Første gang må du tillate «installer ukjente apper» for Bilstereo.

## Bygge
1. Åpne mappa i Android Studio (Ladybug eller nyere)
2. La Gradle synkronisere
3. Build → Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`

## Installere på bilstereoen
Enten kopier APK-en til en minnepinne og åpne den i enhetens filbehandler,
eller via ADB (slå på Utvikleralternativer → USB-/Wi-Fi-feilsøking først):

    adb connect <stereo-ip>:5555
    adb install -r app-debug.apk

Trykk deretter Hjem-knappen, velg «Bilstereo» og «Alltid».

## Tilpasse
- Fliser og hvilke apper de åpner: `Apps.kt` → `Tiles.defaults`
- Farger: øverst i `MainActivity.kt`
- Finn pakkenavnet til en app på stereoen: `adb shell pm list packages | grep -i radio`
