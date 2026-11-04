# DPX Captions

Aplikacija za automatsko generisanje i ugrađivanje titlova u kratke video formate.
Transkribuje srpski govor preko Whisper modela, grupiše ga u titlove po tvojim
pravilima, omogućava izmene na timeline-u kao u video editoru, i eksportuje gotov
video sa ugrađenim animiranim titlovima.

Napravljena kao zamena za AutoSubs + DaVinci Resolve radni tok: ne zahteva Resolve,
radi nad već eksportovanim klipom.

Postoje dve verzije koje dele isti izgled titlova, iste AutoSubs preset fajlove i iste
opcije:

| | Gde | Opis |
|---|---|---|
| **Desktop** | Windows (Python + Qt) | ovaj deo README-a, od "Šta radi" nadalje |
| **Android** | telefon (APK) | [vidi odeljak ispod](#android-aplikacija) |

## Android aplikacija

Ista funkcionalnost vezana za titlove, sa interfejsom prilagođenim telefonu i timeline-om
u stilu CapCut-a (fiksni playhead u sredini, sadržaj klizi ispod njega). Nema video
montaže — samo rad sa titlovima.

### Šta radi

- **Transkripcija na telefonu** (whisper.cpp) — bez interneta i bez PC-ja; model se skida
  jednom. Izbor modela kao na desktopu: Large v3 Turbo (preporuka), Large v3, Medium, Small
- **Isto formatiranje titlova**: gustina (reč / standard / više / custom), broj znakova po
  redu, broj redova, velika/mala slova, uklanjanje interpunkcije, cenzura, vokabular/kontekst
- **Stil**: font (uključeni fontovi + uvoz sopstvenog `.ttf`/`.otf`, npr. Franklin Gothic
  Demi), veličina, pozicija (možeš da prevučeš titl direktno na preview-u), boje, outline,
  senka, "pop" izgovorene reči, ulazne animacije
- **AutoSubs preseti** (`.autosubs-preset.json`) — uvoz i izvoz, isti fajlovi kao na desktopu
- **Aspect ratio**: Auto / 9:16 / 16:9 / 1:1 / 4:5
- **Timeline**: waveform, pomeranje i skraćivanje blokova prevlačenjem, prevlačenje preko
  susednog bloka ga pregazi (kao u DaVinci-ju), split, brisanje, dupliranje, spajanje,
  undo/redo, pinch zoom, inercija pri skrolovanju
- Pronađi i zameni, regrupisanje titlova iz transkripta, safe-area vodilice (uključuju se
  dugmetom), automatsko čuvanje projekata
- **Uči iz tvojih ispravki** isto kao desktop (ista logika i isti `corrections.json`): posle
  exporta pamti prepravke, pri sledećem generisanju ih primenjuje sama. Pregled i brisanje
  u *Settings → Learned corrections*, gde možeš i da uvezeš `corrections.json` sa desktopa
  (`%APPDATA%\DPX Captions\corrections.json`) da telefon odmah zna šta je desktop naučio
- **Izmena teksta ne kvari highlight**: reči zadržavaju vreme kad su izgovorene; `|` između
  reči deli karticu (`OVO JE | ZAISTA TAKO`), a **Split** seče tačno na početku reči
- **My presets**: sačuvaj trenutni izgled pod imenom i vrati ga jednim dodirom (Style →
  Presets); isti `.autosubs-preset.json` format kao desktop
- Napredak generisanja i exporta pokazuje procenat, koliko je ostalo i oko koliko sati je gotovo
- **Export** hardverskim koderom; snimi u galeriju (`Movies/DPX Captions`) ili podeli
- U aplikaciju možeš da **podeliš (Share) video direktno iz galerije**

### Instalacija

1. Preuzmi `DPX-Captions.apk` na telefon.
2. Otvori ga; Android će tražiti da dozvoliš **instalaciju iz nepoznatih izvora** za
   aplikaciju kojom si ga otvorio (pregledač ili Files). Dozvoli i potvrdi instalaciju.
3. Pri prvom pokretanju izaberi video, pa u koraku pripreme **preuzmi model** (Turbo je ~574 MB;
   preporučena je Wi-Fi mreža). Preuzimanje se nastavlja ako se prekine.

Zahtevi: Android 10 ili noviji, 64-bitni ARM procesor sa Armv8.2 dot-product instrukcijama
(praktično svaki telefon od ~2018). Large v3 traži oko 2 GB slobodne memorije.

### Kako se koristi

1. **New project** → izaberi video. Ekran pripreme prikazuje trajanje i rezoluciju.
2. Izaberi model i opcije titlova, pa **Generate captions**. Obrada ide u pozadini uz
   obaveštenje sa napretkom, tako da možeš da zaključaš telefon.
3. U editoru:

| Radnja | Kako |
|---|---|
| Pomeranje po vremenu | prevuci timeline levo/desno (playhead je fiksan u sredini) |
| Zoom | pinch, ili dugmad + / − |
| Izbor titla | dodir na blok |
| Pomeranje titla | prevuci izabrani blok |
| Skraćivanje / produžavanje | prevuci belu ručku na ivici izabranog bloka |
| Izmena teksta | izaberi blok → **Edit** |
| Sečenje | postavi playhead unutar bloka → **Split**, ili u **Edit** ukucaj `\|` između reči |
| Novi titl | postavi playhead → **Add** |
| Pozicija titla na ekranu | prevuci titl na preview-u |
| Stil | **Style** (font, veličina, boje, outline, animacije, preseti) |
| Aspect ratio | dugme sa oznakom formata pored plejera |
| Poništi / ponovi | strelice gore desno |

4. **Export** → izaberi format, rezoluciju i kvalitet → **Save to gallery** ili **Share**.

### Pravljenje APK-a iz izvornog koda

Potrebni su JDK 17+ i Android SDK (platforma 35). NDK i CMake Gradle skida sam.

```bash
cd android
./gradlew assembleDebug      # debug APK (arm64 + x86_64 za emulator)
./gradlew assembleRelease    # potpisani arm64 APK
```

Za potpisan release napravi `android/keystore.properties` (nije u repozitorijumu) sa
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. **Čuvaj keystore** — bez istog
ključa nova verzija se ne može instalirati preko stare.

### Ograničenja Android verzije

- Testirano na Android emulatoru; **brzina transkripcije na pravom telefonu zavisi od
  procesora** i nije izmerena. Na emulatoru (bez ARM optimizacija) Small model obradi
  22 sekunde govora za ~55 s.
- Tajming reči dolazi iz DTW poravnanja. U poređenju sa desktop faster-whisper-om na istom
  audiju, početak reči u proseku kasni oko 0.06 s (75% reči je unutar 0.1 s).
- HDR klipovi se prevode u SDR pri exportu. Vertikalni klipovi se kodiraju sa oznakom
  rotacije (ponašanje Media3 kodera; isto kao kod snimaka sa kamere).
- Jedan video po projektu, bez batch obrade.

## Šta radi

- **Srpska transkripcija u latinici** — Whisper ponekad vrati ćirilicu; aplikacija je
  automatski prebacuje u latinicu
- **GPU ubrzanje** — NVIDIA kartica se automatski detektuje i koristi CUDA; na AMD/Intel
  ili bez kartice prelazi na optimizovan CPU režim
- **Animirani titlovi** — reč dobije highlight boju i kratko "poskoči" u trenutku kada
  je izgovorena
- **Kompatibilnost sa AutoSubs presetima** — `.autosubs-preset.json` fajlovi se učitavaju
  direktno
- **Timeline editor** — waveform, prevlačenje, skraćivanje, sečenje i overwrite kao u
  pravom editoru, sa undo/redo
- **Uči iz tvojih ispravki** — pri exportu pamti šta si prepravio u transkriptu
  ("zahtjeva" → "zahteva", "dobit ces" → "dobices") i sledeći put to ispravlja sam
- **Aspect ratio** — Auto / 9:16 / 16:9 / 1:1 / 4:5, sa istim kadriranjem u preview-u i exportu
- **Safe-area vodilice** — pokazuju gde TikTok/Reels/Shorts interfejs prekriva kadar

## Zahtevi

| Komponenta | Napomena |
|---|---|
| Windows 10/11 | razvijano i testirano na Windows 11 |
| Python 3.11+ | |
| ffmpeg sa libass | obavezno za ugrađivanje titlova |
| NVIDIA GPU | opciono — bez njega radi na procesoru |

### ffmpeg

Potreban je "full" build koji uključuje libass (minimalni buildovi ga nemaju):

```powershell
winget install Gyan.FFmpeg
```

Aplikacija pri pokretanju proverava da li pronađeni ffmpeg podržava `ass` filter i
javlja ako ne podržava.

## Instalacija

```powershell
git clone https://github.com/dragiPan/dpx-captions.git
cd dpx-captions
powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1
```

Skripta pravi virtuelno okruženje, instalira zavisnosti, i ako detektuje NVIDIA karticu
dodatno instalira CUDA biblioteke potrebne za GPU transkripciju.

Ručno, ako ti je draže:

```powershell
python -m venv .venv
.venv\Scripts\pip.exe install -e .
```

## Pokretanje

```powershell
.venv\Scripts\python.exe -m dpx_captions.main
```

## Korišćenje

### 1. Učitaj klip

**Choose Video File...** — ispod naziva se prikazuju trajanje, rezolucija i framerate.
Vredi ih pogledati: ako ti je export iz editora pukao ili je u pogrešnoj rezoluciji,
odmah se vidi.

### 2. Podesi model

- **Whisper Large v3** — sporiji, najtačniji
- **Whisper Large v3 Turbo** — znatno brži, neznatno manje tačan

Ispod piše koji režim je detektovan. **Force CPU** forsira procesor i kad postoji
NVIDIA kartica.

### 3. Opcije titlova

| Opcija | Šta radi |
|---|---|
| Vocabulary / Context | nagoveštaj modelu — imena, brendovi, stručni termini |
| Text density | koliko reči ide u jedan titl (Single word / Standard / More / Custom) |
| Max characters / line | za Custom — koliko znakova stane u red |
| Line count | broj redova po titlu |
| Text case | VELIKA SLOVA / mala slova / Naslovna / Normalna |
| Remove punctuation | izbacuje zareze, tačke i sl. |
| Censor words | zamenjuje reči sa liste zvezdicama |

### 4. Stil

Font se bira iz liste instaliranih fontova. **Download caption fonts...** skida
besplatne fontove pogodne za titlove (Barlow Condensed, Anton, Bebas Neue, Fjalla One,
Open Sans) u `fonts/` folder — odatle ih koriste i preview i export.

Ostalo: veličina, pozicija, boja teksta i highlight-a, bold, debljina outline-a,
udaljenost senke, jačina pop animacije, i ulazne animacije (Pop in / Slide up / Fade).

**Stil se prenosi na sledeće klipove**: šta god je podešeno u panelu koristi se i za
svaki sledeći klip, i pamti se odmah (ne tek pri zatvaranju). **Presets** na dnu panela
čuvaju izgled pod imenom — stil i raspored teksta (gustina, znakova po redu, redovi,
velika slova, interpunkcija); vokabular i lista za cenzuru se ne menjaju. *Save as...*
snima trenutni izgled, izbor iz liste ga vraća jednim klikom, *Delete* ga briše. Preseti
su u `%APPDATA%\DPX Captions\presets\` kao obični `.autosubs-preset.json` fajlovi;
*Import* / *Export preset file* rade sa AutoSubs fajlovima bilo gde na disku.

Export izgleda isto kao preview: pri prvom exportu nekim fontom program izmeri kako ga
ffmpeg/libass crta (visina, širina, osnovna linija) i prema tome podesi veličinu, jer Qt i
libass za isto ime fonta ponekad uzmu različite fajlove sa različitim merama.

### 5. Generisanje i izmene

**Generate** izvlači zvuk, transkribuje ga i pravi titlove.

Timeline (ispod preview-a):

| Radnja | Kako |
|---|---|
| Pomeranje titla | prevuci blok |
| Skraćivanje / produžavanje | prevuci levu ili desnu ivicu, ili `Q` / `W` (početak / kraj do playhead-a) |
| Overwrite | prevuci preko susednog bloka — pregazi ga |
| Sečenje na playhead-u | `Ctrl+B` — seče između reči, nova kartica počinje na prvoj svojoj reči |
| Deljenje po rečima | u tekstu kartice ukucaj `\|` između reči: `OVO JE \| ZAISTA TAKO` |
| Spajanje sa sledećom | `Ctrl+M` |
| Nova kartica na playhead-u | `Ctrl+N` |
| Brisanje | `Del` |
| Play / pauza | `Space` |
| Frejm napred / nazad | `→` / `←` (`Shift` = 1 sekunda) |
| Prethodna / sledeća kartica | `↑` / `↓` |
| Početak / kraj klipa | `Home` / `End` |
| Izmena teksta | dupli klik na blok, ili `Enter` / `F2` |
| Kopiranje / lepljenje teksta | `Ctrl+C` / `Ctrl+V` |
| Zoom | `Ctrl` + točkić (drži tačku pod kursorom) |
| Horizontalno skrolovanje | `Alt` + točkić |
| Pomeranje playhead-a | klik na lenjir ili waveform |
| Poništi / ponovi | `Ctrl+Z` / `Ctrl+Y` |
| Find & Replace / Save / Open | `Ctrl+F` / `Ctrl+S` / `Ctrl+O` |
| Generate / Export | `Ctrl+G` / `Ctrl+E` |
| Spisak prečica | `F1` ili dugme `?` |

`Space` (play/pauza) i `Ctrl` prečice rade bilo gde osim dok kucaš tekst. Strelice,
`Q`/`W`, `Enter`/`F2` na timeline-u rade kada je timeline aktivan (klikni na njega). U
tabeli **Subtitles**: klik na red (ili strelice) pomera playhead na tu karticu, `Enter`
otvara izmenu teksta, drugi `Enter` je potvrđuje — odmah zatim `Space` pušta klip. Na
granici dva bloka hvata se onaj sa čije strane je kursor.

Progress ispod dugmeta Generate pokazuje korak koji traje, procenat, koliko je približno
ostalo i oko koliko sati će biti gotovo — i za transkripciju i za export.

**Highlight prati zvuk i posle izmena.** Svaka reč čuva vreme kada je izgovorena:
kad promeniš tekst kartice, reči koje ostaju zadržavaju svoje vreme, ispravljena (ili
spojena/razdvojena) reč dobija vreme reči koju menja, a obrisane nestaju. Reč koju
dopišeš traži se u originalnom transkriptu oko te kartice; tek ako je nema, smešta se
u slobodan prostor između suseda. Kada karticu podeliš (`Ctrl+B` ili `|`), svaka nova
kartica počinje tačno kada se izgovori njena prva reč, pa highlight ne kasni.

U tabeli **Subtitles** se ispravlja tekst; vremena se menjaju isključivo na timeline-u.
**Add Card** ubacuje novi titl na poziciju playhead-a.

**Find & Replace** menja reč kroz sve titlove odjednom — korisno kada model dosledno
pogreši isti termin. Tajming reči ostaje netaknut.

Dugme **Guides** u preview-u uključuje safe-area vodilice (podrazumevano isključene).
Crveno su zone koje prekriva interfejs mreže, žuti okvir je siguran prostor.

### 6. Export

**Export Video with Captions** ugrađuje titlove kroz ffmpeg, sa bitrate-om
prilagođenim izvoru. Ako je izabran aspect ratio različit od izvornog, video se skalira
i dopunjava crnim trakama do tog formata.

Sa NVIDIA karticom video se kodira na grafičkoj (NVENC) — na RTX 4060 klip od 1:27 za oko
8 s umesto 68 s na procesoru, istog kvaliteta u odnosu na original (SSIM 0.9947 prema
0.9950, PSNR 50.7 prema 50.4 dB). Bez NVIDIA kartice, ili kad NVENC ne može (npr. 10-bitni
HDR izvor), export sam prelazi na procesor (libx264).

Whisper model se pri pokretanju aplikacije učita u pozadini (ako je već preuzet) i ostaje u
memoriji, pa *Generate* odmah kreće sa transkripcijom; pored izbora modela tada piše
"model ready".

### 7. Naučene ispravke

Posle uspešnog exporta program uporedi titlove onako kako ih je generisao sa titlovima
kako si ih ti ostavio, i svaku prepravljenu reč ili frazu zapamti kao pravilo:

| Whisper napisao | Ti prepravio |
|---|---|
| zahtjeva | zahteva |
| dobit ćeš | dobićeš (spojene reči) |
| vrijeme | vreme |

Sledeći put, odmah posle transkripcije a pre nego što se titlovi prikažu, program prođe
kroz Whisper-ov tekst i primeni sva pravila — editor se otvara sa već ispravljenim
greškama. Ispod dugmeta Generate piše koliko ispravki je primenjeno, a prozor posle
exporta prikazuje šta je novo naučeno.

- Poređenje ne razlikuje velika i mala slova i ignoriše interpunkciju. Zarez ili tačka
  iz Whisper-ovog teksta ostaju na mestu i posle ispravke.
- **Pravopisne ispravke važe svuda** (jedna reč u sličnu: zahtjeva → zahteva; ili
  spajanje: dobit ćeš → dobićeš). **Izmene koje zavise od rečenice uče se sa karticom**:
  deljenje reči (retko *koji* → retko *ko i*), zamena potpuno drugom rečju, ili izmena
  kratke reči (≤ 3 slova). Pamti se kartica oko izmene — samo reči koje si ostavio, ne one
  koje si obrisao ili premestio — pa se pravilo primenjuje samo kad se ponovi isti izraz.
  Kontekstna pravila se pamte u već ispravljenom obliku ("retko koji" i kad je Whisper
  napisao "redko koji") i primenjuju se posle pravopisnih. U *Learned corrections* kolona
  *Applies* pokazuje "everywhere" ili "only in this phrase".
- Izmena se računa samo ako je na istom mestu u vremenu kao generisani tekst, tako da
  pomeranje, brisanje ili dodavanje kartica ne pravi pogrešna pravila. Obrisane reči
  (npr. poštapalice) se ne pamte kao ispravke.
- Ako vratiš automatsku ispravku na staro, to pravilo slabi i nestaje kad izgubi sve
  "glasove". Ako istu reč prepraviš na dva načina, pobeđuje češća ispravka.
- Ponovljeni export istog projekta ne broji iste ispravke dva puta.
- **Learned corrections** (pored Find & Replace) prikazuje sva pravila i briše pogrešna.

Pravila se čuvaju u `%APPDATA%\DPX Captions\corrections.json`. Za projekte snimljene pre
ove verzije (bez sačuvanog generisanog teksta) učenje se preskače — generiši ponovo.
Android verzija za sada ne uči ispravke.

## Projekti

**Save Project...** snima sve — putanju do klipa, stil, opcije i sve izmenjene titlove —
u `.dpxproj` fajl. **Open Project...** vraća sesiju bez ponovnog transkribovanja.
Projekat čuva i titlove kakvi su bili pri generisanju, tako da učenje ispravki radi i
kada projekat otvoriš kasnije pa tek onda exportuješ.

Poslednja sesija se čuva sama (oko sekunde posle svake izmene i pri zatvaranju) u
`%APPDATA%\DPX Captions\session.dpxproj`. Kada ponovo pokreneš aplikaciju, vraća se isti
klip sa svim titlovima, stilom, aspect ratio-m i pozicijom playhead-a — ako taj klip i
dalje postoji na istom mestu; ako ne postoji, aplikacija kreće prazna. Undo istorija se
ne pamti između pokretanja.

Rad se pamti i **za svaki klip posebno**: kad izabereš drugi klip, prethodni ostaje
sačuvan, a kad se vratiš na klip na kom si već radio, vraćaju se njegovi titlovi. Nov klip
kreće bez titlova, sa trenutnim stilom.

## Podešavanja

Stil, opcije, izabrani model i aspect ratio se pamte između sesija u:

```
%APPDATA%\DPX Captions\settings.json
```

## Pravljenje .exe verzije

Za samostalnu verziju koju prebacuješ na drugi računar bez instaliranja Python-a:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
```

Rezultat je `dist\DPX Captions\` — prekopiraj **ceo folder**, ne samo `.exe`. Na tom
računaru i dalje treba ffmpeg sa libass. Whisper model se skida pri prvom pokretanju,
pa je za prvo pokretanje potreban internet.

## Ograničenja

- GPU transkripcija radi samo na NVIDIA karticama (faster-whisper koristi CTranslate2,
  koji nema ROCm/DirectML podršku)
- `HighlightStyle 1` iz AutoSubs preseta (pozadinski okvir iza reči) nije implementiran —
  koristi se highlight bojom teksta
- Nema undo (`Ctrl+Z`) za izmene na timeline-u
- Obrada je jedan klip u jednom trenutku, bez batch reda

## Licenca

Sva prava zadržana. Fontovi koji se skidaju kroz aplikaciju nisu deo ovog projekta i
imaju svoje licence (SIL Open Font License).
