# T'OS – 50 funksjoner som kan legges til

Laget etter å ha sett på hva vanlige startskjermer for bilstereo har (AGAMA, CarWebGuru, FCC Car Launcher,
Carl Launcher, Car Launcher Pro) og hva folk roser og klager på i anmeldelser og forum.
Lista er tilpasset T'OS: rent, svart/grått/hvitt, få ting på skjermen. ★ = foreslått først.

**Status v3.0: alle 50 er bygget inn.** Kompilerer og består lint, men er IKKE testet på stereoen.
Merket ⚠ = avhenger av stereoen/utstyr og kan virke delvis. Se «Begrensninger» nederst.

**Det folk liker:** store knapper som er lette å treffe, sangtittel og cover, fart og OBD-data, automatisk
dag/natt, rattknapper som virker, at alt starter av seg selv når bilen starter.
**Det folk misliker:** treg startskjerm på billige stereoer, for mange småwidgets, innstillinger som er
kompliserte, reklame og betalingsmurer, musikk som slutter å virke (lydfokus), og at Waze ikke kan vises inni startskjermen.

## Musikk
1. ★ Sangtittel, artist og cover i musikkortet (fra Spotify og andre)
2. ★ Spill/pause-knappen viser om noe faktisk spiller
3. ★ Musikken fortsetter av seg selv når bilen starter
4. Fremdriftslinje for sangen
5. Stor, diskret visning av volumet når du vrir på volumhjulet
6. Velge hvilken musikkapp som er standard (Spotify, YouTube Music, USB)
7. Langt trykk på MUSIC-knappen = neste sang
8. Radio: favorittstasjoner med ett trykk (når RADIO-knappen er bestemt)

## Kart og navigasjon
9. ★ «Hjem» og «Jobb»: ett trykk starter navigasjon dit
10. ★ Kartet zoomer ut når farten øker
11. Kartet snur seg etter kjøreretningen
12. Fartsgrensen på veien du kjører på (fra OpenStreetMap)
13. Diskret varsel når du er over fartsgrensen
14. Neste sving fra Google Maps vist på forsiden mens du navigerer
15. Siste steder du har navigert til

## Bilen og turen
16. ★ Ekte bilde av Avensisen (car_png)
17. ★ Velge bilbilde rett fra stereoen, uten ny versjon
18. ★ Turmåler: avstand, tid og snittfart for turen
19. Høyeste fart på turen
20. ★ Husker hvor bilen ble parkert (posisjon når strømmen går)
21. ~~OBD2 via Bluetooth-brikke~~ → erstattet i v3.3 av «Tuning og ytelse» (tuningboks-app + 0–100 med GPS)
22. ~~Varsel om lav batterispenning (OBD)~~ (fjernet sammen med OBD)
23. Kompassretning (N, NØ …) diskret ved farten

## Telefon og Bluetooth
24. ★ Favorittkontakter: ring med ett trykk
25. Hvem som ringer, vist stort på T'OS
26. Siste anrop
27. ★ Åpner CarPlay av seg selv når iPhonen kobles til
28. Telefonens batteri og signal på Bluetooth-siden
29. Stemmestyring: hold en knapp for Siri (via CarPlay)

## Skjerm og lys
30. ★ Automatisk nattmodus som demper skjermen etter solnedgang
31. Enda mørkere nattfarger (dempet hvitt)
32. Stor klokke som skjermsparer når bilen står stille
33. Valg for større tekst og knapper
34. ★ Ryggekamera-knapp, eller automatisk ved revers

## Knapper og styring
35. ★ Rattknappene lært på samme måte som stereoknappene
36. Langt trykk på stereoknappene = egen handling
37. Sveip på forsiden (for eksempel kart i fullskjerm)
38. Delt skjerm med kart og musikk samtidig
39. Åpner siste app igjen når bilen starter
40. Hurtigpanel for lysstyrke, volum og Wi-Fi (sveip ned)

## System og drift
41. ★ Rask og lett: ingen reklame, ingen tunge animasjoner (prinsipp)
42. ★ Viser på forsiden når en ny versjon av T'OS finnes
43. ~~Sikkerhetslås: kompliserte sider er sperret over 10 km/t~~ (fjernet etter ønske i v3.1)
44. Velg selv hvilke apper som er i bunnraden (langt trykk)
45. Sikkerhetskopi av innstillinger og lærte knapper
46. Temperatur og minne på stereoen (varmgang på lange turer)
47. Ikon for internett (Wi-Fi fra iPhonen) på forsiden
48. «Send feilrapport» som gjør det lett å fikse feil
49. Påminnelser: EU-kontroll, service og dekkskift (dato eller km)
50. Automatisk sikkerhetskopi til Google Drive

## Bevisst ikke med
- Vær og kalender (du har sagt nei)
- Mange småwidgets og neonfarger (prøvd og forkastet)
- Waze inni startskjermen (går ikke teknisk, åpnes som egen app i stedet)

## Begrensninger (v3.0)
- 1, 2, 4, 14, 25: krever at «Varseltilgang» for T'OS er slått på (Innstillinger → Musikk og oppstart).
- ⚠ 3, 39: avhenger av om stereoen starter på nytt eller bare sover. Begge tilfeller er dekket (oppstart og skjerm på etter >2 min).
- ⚠ 12, 13: fartsgrense fra OpenStreetMap (Overpass-API) krever internett. Kan mangle/være feil.
- ⚠ 25, 26: hvem som ringer/siste anrop vises bare hvis stereoens BT-app deler det med Android.
- ⚠ 27: CarPlay-autostart virker hvis Android ser iPhonen (USB med Apple-ID 0x05AC, eller Bluetooth-navn med «iPhone»).
- ⚠ 28: batteri vises bare der Android selv styrer Bluetooth. Signalstyrke er ikke tilgjengelig for apper.
- ⚠ 29: Siri kan ikke startes direkte fra Android; åpner Google-assistent eller CarPlay.
- ⚠ 34: kun knapp; automatisk kamera ved rygging gjør stereoen selv.
- ⚠ 38: delt skjerm bare der stereoen støtter det.
- 50: sikkerhetskopi lagres via filvelgeren – velg Google Drive (krever Drive-appen på stereoen).
- Testsiden (preview/) viser forsiden og de viktigste sidene, men ikke alt (f.eks. snur ikke kartet).
