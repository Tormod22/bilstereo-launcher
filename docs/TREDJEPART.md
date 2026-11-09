# Tredjepartskode i T'OS

## OpenLauncher
Deler av radiostyringen (`HwRadio` i `Radio.kt`: lesing av `SYS_MEDIA_INFO_JSON`, MCU-kommandoer for
szchoiceway-stereoer, tolking av frekvens fra radio-appens mediaøkt) og ideen om å finne bil-apper uten
startikon er hentet fra OpenLauncher: https://github.com/dw2lam/openlauncher

MIT License

Copyright (c) dw2lam and OpenLauncher contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.

## Kartdata
© OpenStreetMap-bidragsytere (ODbL), bygget av Protomaps. Skrift: Noto Sans (SIL Open Font License).

## FYT-protokollen (com.syu.ms) – v5.6
T'OS sin `Fyt.kt` er skrevet fra bunnen av, men protokollen (Binder-beskrivere, transaksjonskoder, modulnumre og
kommando-/meldingskoder) er hentet fra disse åpne prosjektene, som har kartlagt FYT sine egne apper:
- PimpinPumpkin/FytRadio – https://github.com/PimpinPumpkin/FytRadio (radio, lydkilde, FM-enhet)
- Pacjonek/fyt-dialer – https://github.com/Pacjonek/fyt-dialer, Apache-2.0 (BT-telefon: ringe, svare, legge på, telefonens navn)
- vasyl91/FYT-Launcher-Mod – https://github.com/vasyl91/FYT-Launcher-Mod (FinalBt, FinalRadio, FinalSound, FinalMain)
- AxesOfEvil/FYTCanbusMonitor – https://github.com/AxesOfEvil/FYTCanbusMonitor (modulnumrene, canbus-kodene)
- v5.8 rattknappene: cssbruno/cabin – https://github.com/cssbruno/cabin, Unlicense (offentlig eie):
  app/src/main/kotlin/com/cabin/platform/SyuSteeringProtocol.kt, SyuSteeringController.kt, SyuSteeringWire.kt og
  documents/STEERING-MAPPINGS.md (rattmodulen 10: start/stopp læring, gi tallet til en funksjon, lagre, slett, og
  tilbakemeldingene – verifisert der mot Joying com.syu.ms 2.23.0711.1001). FinalMain (FYT-Launcher-Mod): U_PLAYER_CMD 37.
  andreknieriem/open-headunit – https://github.com/andreknieriem/open-headunit, AGPL-3.0: bare kunnskapen om at hoved u=133
  er en knapp (fra com.syu.steer), ingen kode.
- BT-telefonen (`FytPhone.kt`): alle U_/C_/KEY_/PHONE_STATE_-kodene fra FinalBt i FYT-Launcher-Mod
  (app/src/main/java/com/syu/ipc/FinalBt.java), BT-musikkens tid/album fra samme prosjekt
  (com/fyt/car/StockBtSource.java og com/android/launcher66/Launcher.java: u=2 lengde i ms, u=28 posisjon i ms, u=26 album),
  og fyt-dialer (C_DIAL/C_PICKUP/C_HANG, «ringer bare når HFP er tilkoblet»). Formatet på telefonboka (u=22) og parede
  enheter (u=5) finnes ikke i noen åpen kilde – T'OS tolker dem forsiktig og logger dem i kartleggingen.
Ingen kode er kopiert; bare tallene og formatet.
