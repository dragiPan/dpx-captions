# DPX Captions

Desktop aplikacija za automatsko generisanje i ugrađivanje titlova u kratke video
formate. Transkribuje srpski govor preko Whisper modela, grupiše ga u titlove po
tvojim pravilima, omogućava izmene na timeline-u kao u video editoru, i eksportuje
gotov video sa ugrađenim animiranim titlovima.

Napravljena kao zamena za AutoSubs + DaVinci Resolve radni tok: ne zahteva Resolve,
radi nad već eksportovanim klipom.

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

AutoSubs preset se učitava i snima dugmadima na dnu panela.

### 5. Generisanje i izmene

**Generate** izvlači zvuk, transkribuje ga i pravi titlove.

Timeline (ispod preview-a):

| Radnja | Kako |
|---|---|
| Pomeranje titla | prevuci blok |
| Skraćivanje / produžavanje | prevuci levu ili desnu ivicu |
| Overwrite | prevuci preko susednog bloka — pregazi ga |
| Sečenje na playhead-u | `Ctrl+B` |
| Brisanje | `Del` |
| Play / pauza | `Space` |
| Kopiranje / lepljenje teksta | `Ctrl+C` / `Ctrl+V` |
| Izmena teksta | dupli klik na blok |
| Zoom | `Ctrl` + točkić (drži tačku pod kursorom) |
| Horizontalno skrolovanje | `Alt` + točkić |
| Pomeranje playhead-a | klik na lenjir ili waveform |
| Poništi / ponovi | `Ctrl+Z` / `Ctrl+Y` |

Na granici dva bloka hvata se onaj sa čije strane je kursor.

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

## Projekti

**Save Project...** snima sve — putanju do klipa, stil, opcije i sve izmenjene titlove —
u `.dpxproj` fajl. **Open Project...** vraća sesiju bez ponovnog transkribovanja.

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
