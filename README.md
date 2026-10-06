# Morgenbriefing

**Dein Morgen auf einen Blick:** das Wetter vor der Tür, der Tag in Leipzig und die wichtigsten
Nachrichten aus zehn Rubriken – jeden Morgen um 5:30 Uhr als Benachrichtigung und als
vollständige Artikel in einer eigenen App, ganz ohne Umweg über Webseiten mit Bezahlschranke.

Persönliche Android-App (Kotlin, Jetpack Compose, Material 3). Die News recherchiert und schreibt
jeden Morgen eine Claude-Cloud-Routine frisch, das Wetter kommt immer live.

---

## Was die App kann

| | |
|---|---|
| **Heute** | Oben ein animierter Himmel passend zu Wetter und Tageszeit. Darunter „Vor der Tür“ in Bad Düben (jetzt und die nächsten Stunden, Sonnenauf- und -untergang), die Tageskurve für Leipzig von 10 bis 18 Uhr mit Regenwahrscheinlichkeit, was du anziehen solltest, Wetterwarnungen – und die Nachrichten des Tages: pro Rubrik große Karten mit abgerundeten Ecken, einer **großen Überschrift über dem Bild** und dem Vorspann darunter. **„+ Mehr“** klappt weitere Artikel der Rubrik auf. |
| **Artikel lesen** | Ein Tipp öffnet den **vollständigen Artikel** direkt in der App: eigene Texte der Routine, aus mehreren Quellen recherchiert, mit Zwischenüberschriften, Lesezeit, Quellenliste und Bildnachweis. Schriftgröße einstellbar. |
| **10 Rubriken** | 🤖 KI & Tech · 📈 Wirtschaft & Märkte · 🏛️ Politik · 🌍 Welt · 📍 Leipzig & Region · ⚽ Sport · 🔭 Wissenschaft & Raumfahrt · 🩺 Gesundheit · 🌱 Klima & Umwelt · 🎮 Games & Entertainment – Reihenfolge und Auswahl legst du in den Einstellungen fest. |
| **Verlauf** | Alle bisherigen Tage mit Teaser, dem Wetter vom Morgen und dem kompletten Briefing – auch offline. |
| **Einstellungen** | Hell, Dunkel oder wie das System; Akzentfarben (Material You, Morgenrot, Sonne, Ozean, Wald, Lavendel, Beere, Graphit); Rubriken sortieren und ausblenden; Schriftgröße im Artikel; Uhrzeit der Benachrichtigung; Orte und Zeitfenster; GitHub-Token; Akku-Optimierung; Updates. |
| **Updates in der App** | Die App sieht selbst nach, ob es auf GitHub eine neuere Version gibt, lädt sie herunter und startet die Installation – kein Umweg über den Browser. |
| **5:30-Benachrichtigung** | Ein exakter Alarm holt Live-Wetter und das frische Briefing und zeigt eine ausklappbare Benachrichtigung mit Wetter, Kleidungstipp und den Top-Schlagzeilen. |
| **E-Mail-Briefing** | Auf Wunsch kommt das Briefing um 5:20 Uhr zusätzlich als E-Mail (Wetter, Kleidungstipp, Top-Meldungen mit Bildern und Quellen) – an dich oder an bis zu zehn Adressen. Schalter und Empfänger stellst du in der App ein. |

Läuft ab **Android 7.0** (API 24). Randlos (Edge-to-Edge) und sauber mit **Gesten- und
3-Tasten-Navigation**, wie Samsung One UI beides anbietet, inklusive Predictive Back.

## So funktioniert's

```
04:00  Cloud-Routine (claude.ai, täglich)                    05:30  App (AlarmManager, exakt)
       ├─ 10 Rubrik-Agenten parallel:                                ├─ Open-Meteo live (Wetter)
       │  recherchieren, prüfen (≥ 2 Quellen),                       ├─ GitHub contents API: latest.json
       │  schreiben eigene Artikel, suchen freie Bilder              ├─ speichert Morgen-Snapshot (Verlauf)
       ├─ setzt zusammen, prüft, schreibt briefs/YYYY-MM-DD.json,    └─ Benachrichtigung (BigText)
       │  latest.json, index.json auf Branch `briefs`                       ▲
       └─ git push  ──────────────► GitHub (privat) ◄───────────────────────┘
```

- **Wetter** kommt nie aus dem Repo: Die App fragt es immer live bei [Open-Meteo](https://open-meteo.com) ab
  (ohne Schlüssel, ohne Konto).
- **Nachrichten** schreibt die Cloud-Routine jeden Morgen als JSON auf den Daten-Branch `briefs`
  dieses privaten Repos. Die App liest sie mit einem GitHub-Token, das nur für genau dieses Repo
  gilt. Ohne Token zeigt sie ein Demo-Briefing.
- **Eigene Texte statt Kopien:** Für jede Rubrik recherchiert ein eigener Agent die wichtigsten
  Geschichten der letzten 24 Stunden, öffnet für jede Top-Geschichte mindestens zwei unabhängige
  Quellen und schreibt daraus einen vollständigen Artikel in eigenen Worten (nur Fakten,
  höchstens ein kurzes Zitat). Artikel und Pressefotos werden nie übernommen.
- **Bilder** kommen ausschließlich von [Wikimedia Commons](https://commons.wikimedia.org) unter
  freier Lizenz (CC0, gemeinfrei, CC BY, CC BY-SA) und werden mit Urheber und Lizenz genannt.
  Passt kein freies Bild, zeichnet die App ein eigenes Titelbild in der Farbe der Rubrik.
- Vor jedem Push prüft der Validator [`routine/validate-brief.mjs`](routine/validate-brief.mjs)
  jede Rubrik und das ganze Briefing: Format, Quellen, Lizenzen, Textlänge – und dass nichts
  älter als 36 Stunden ist.

Mehr dazu: [Architektur](docs/ARCHITECTURE.md) · [Datenformat](docs/brief-format.md) ·
[Prompt der Cloud-Routine](routine/PROMPT.md)

---

## Einrichtung

### 1. App installieren

1. Auf dem Handy im Browser bei GitHub einloggen (das Repo ist privat) und unter
   **[Releases](https://github.com/CedrickGD/Morgenbriefing/releases)** die neueste
   `Morgenbriefing-vX.Y.Z.apk` herunterladen.
2. Die Datei öffnen. Fragt Android nach „Unbekannte Apps installieren“, für Browser bzw.
   „Eigene Dateien“ **Zulassen** wählen und die Installation bestätigen.
3. Beim ersten Start **Benachrichtigungen erlauben** (ab Android 13 fragt die App danach).

Das ist nur beim ersten Mal nötig: Spätere Versionen findet die App selbst (siehe
[Updates](#updates)). Beim ersten Update fragt Android, ob **Morgenbriefing** Apps installieren
darf – einmal **Zulassen**, danach geht es mit einem Tipp.

> Lieber den neuesten Entwicklungsstand? Jeder Build auf `main` legt unter **Actions → Android →
> Artefakt `morgenbriefing-debug-apk`** eine Debug-Version ab. Sie heißt
> `com.cedrickgd.morgenbriefing.debug` und lässt sich parallel zur normalen App installieren.

### 2. GitHub-Token erstellen und in der App eintragen

Das Token gilt nur für dieses eine Repo. Die App **liest** damit die Briefings (und neue
App-Versionen) und **schreibt** genau eine Datei: `config/email.json`, wenn du unter
**Einstellungen → E-Mail-Briefing** den Versand ein- oder ausschaltest oder Empfänger änderst.
Dafür braucht das Token **Contents: Read and write**. Mit einem Nur-Lese-Token laufen News und
Updates trotzdem, nur das E-Mail-Briefing meldet dann „Schreibrecht fehlt“.

1. Auf [github.com](https://github.com) oben rechts auf dein Profilbild → **Settings**.
2. Links ganz unten **Developer settings** → **Personal access tokens** → **Fine-grained tokens**
   → **Generate new token**.
3. **Token name:** z. B. `Morgenbriefing-App`. **Resource owner:** `CedrickGD`.
4. **Expiration:** so lang wie möglich (die längste angebotene Laufzeit bzw. „No expiration“,
   falls angeboten). Trag dir das Ablaufdatum in den Kalender.
5. **Repository access:** **Only select repositories** → `Morgenbriefing` auswählen.
6. **Permissions** → **Repository permissions** → **Contents:** **Read and write**.
   („Metadata: Read-only“ setzt GitHub automatisch.) Sonst nichts anhaken. In neueren
   GitHub-Oberflächen geht das über **Add permissions** → **Contents** → **Read and write**.
   Geschrieben wird nur `config/email.json` (E-Mail-Briefing). Ein bestehendes Token kannst du
   unter **Fine-grained tokens** → Token → **Edit** auf **Read and write** hochstufen, ohne es
   neu einzutragen.
7. **Generate token** und das Token (beginnt mit `github_pat_`) kopieren – GitHub zeigt es nur
   einmal an.
8. In der App: **Einstellungen** → Token einfügen → **Verbindung testen**.

Die App speichert das Token verschlüsselt (Android-Keystore). Läuft es ab, meldet die App einen
Zugriffsfehler – dann einfach mit denselben Schritten ein neues erstellen.

### 3. Samsung: Akku nicht einschränken

Damit One UI die App morgens nicht schlafen legt:

**Einstellungen → Apps → Morgenbriefing → Akku → „Nicht eingeschränkt“**

Die App bietet unter **Einstellungen** zusätzlich „Akku-Optimierung aus“ an (System-Dialog).

### 4. Cloud-Routine einrichten

Die Routine startet jeden Morgen um 04:00 Uhr, lässt zehn Rubrik-Agenten parallel recherchieren
und schreiben und pusht das fertige Briefing auf den Branch `briefs` – meist deutlich vor 5 Uhr.
So legst du sie in claude.ai an:

1. Neue Routine (geplante Claude-Code-Aufgabe) erstellen und das Repo
   **`CedrickGD/Morgenbriefing`** verbinden (Standard-Branch `main`).
2. **Prompt:** den kompletten Inhalt von [`routine/PROMPT.md`](routine/PROMPT.md) einfügen.
3. **Zeitplan:** täglich um **02:00 und 03:00 UTC** (Cron `0 2,3 * * *`). So beginnt sie im
   Sommer wie im Winter um 04:00 Uhr Berliner Zeit. Den überzähligen Start erkennt die Routine
   selbst („Übersprungen (zu früh)“); fällt der 04-Uhr-Lauf aus, holt der spätere Start ihn bis
   06:59 Uhr nach. Lässt sich der Zeitplan direkt in Europe/Berlin einstellen, reicht ein
   einziger Start um 04:00.
4. **Werkzeuge:** Websuche, Webseitenabruf und Subagenten (Agent-Werkzeug) erlauben. Ohne
   Subagenten arbeitet die Routine die Rubriken nacheinander und dafür etwas knapper ab.
5. **Pushen auf `briefs` erlauben:** Standardmäßig dürfen Cloud-Routinen oft nur auf
   `claude/…`-Branches pushen. Erlaube in den Einstellungen der Routine bzw. der Umgebung Pushes
   auf beliebige Branches. Sonst landet das Briefing auf `claude/briefs`, und die App sieht es
   nicht – die Routine sagt das dann ausdrücklich.
6. **Testen:** Routine einmal von Hand starten. Außerhalb von 03:00–03:59 Uhr veröffentlicht sie
   sofort (zwischen 05:00 und 06:59 nur, wenn das heutige Briefing noch fehlt). Danach liegt auf
   GitHub im Branch `briefs` ein neues Briefing, und in der App holt es ein Herunterziehen auf
   **Heute**. Zum Schluss meldet die Routine Artikel pro Rubrik, Bilder und das Push-Ergebnis.

Änderst du später `routine/PROMPT.md`, kopiere den neuen Inhalt auch in die Routine.

**Überwachung:** Der Workflow **Briefs prüfen** läuft täglich um 04:45 UTC, validiert die
Daten auf `briefs` und schlägt fehl, wenn das heutige Briefing fehlt oder kaputt ist. GitHub
schickt dir dann die übliche „Run failed“-Mail.

### 5. E-Mail-Briefing (optional)

Eine zweite, kleine Routine verschickt das Briefing um 05:20 Uhr als E-Mail aus deinem
Gmail-Konto. Sie recherchiert nichts: Sie liest `config/email.json` und das heutige
`latest.json` vom Branch `briefs`, baut mit [`routine/render-email.mjs`](routine/render-email.mjs)
die E-Mail (Wetter live von Open-Meteo, dazu pro Rubrik die Top-Meldungen) und schickt sie
einzeln an jede eingetragene Adresse – an nichts anderes. Danach vermerkt sie den Versand in
`config/email-sent.json`, damit der Wiederholungsstart nicht doppelt sendet.

1. Neue Routine erstellen, Repo **`CedrickGD/Morgenbriefing`** verbinden (Branch `main`).
2. **Prompt:** den kompletten Inhalt von [`routine/EMAIL_PROMPT.md`](routine/EMAIL_PROMPT.md).
3. **Zeitplan:** täglich um **03:20 und 04:20 UTC** (Cron `20 3,4 * * *`) – das ist im Sommer wie
   im Winter 05:20 Uhr in Berlin; den überzähligen Start erkennt die Routine selbst.
4. **Werkzeuge:** nur den **Gmail-Connector** verbinden (die Routine nutzt davon nur
   `send_message`). Keine Websuche, kein Webseitenabruf, keine Subagenten.
5. **Netz und Push:** Pushes auf `briefs` erlauben (wie bei der News-Routine). Für den
   Wetterblock braucht die Umgebung Zugriff auf `api.open-meteo.com` – ohne ihn kommt die E-Mail
   ohne Wetter.
6. **Einschalten:** in der App unter **Einstellungen → E-Mail-Briefing** den Schalter setzen und
   die Empfänger eintragen (das Token braucht dafür *Contents: Read and write*, siehe oben).
   Ohne `config/email.json` oder mit ausgeschaltetem Schalter verschickt die Routine nichts.
7. **Testen:** Routine einmal von Hand starten (außerhalb von 04:00–06:59 Uhr sendet sie sofort,
   sobald es ein heutiges Briefing gibt). Sie antwortet mit einer Zeile wie
   `Gesendet: 2026-10-06 an 1 Empfänger`.

Vorschau ohne Versand: `node routine/render-email.mjs --brief app/src/main/assets/sample-brief.json --out-dir routine/out/email`
und `routine/out/email/email.html` im Browser öffnen. Abbestellen geht jederzeit in der App.

### Updates

Neue Versionen veröffentlichst du mit **`scripts/release.ps1`** (PowerShell): Das Skript erhöht
`MB_VERSION_CODE` und `MB_VERSION_NAME` in `gradle.properties`, baut die mit deinem
Release-Schlüssel signierte APK und legt sie als GitHub-Release ab. Aufruf und Optionen stehen im
Kopf des Skripts. Die App prüft daraufhin von selbst (und auf Wunsch in den Einstellungen), ob es
eine neuere Version gibt, und bietet das Update an. Weil jede Version mit demselben Schlüssel
signiert ist, bleiben Einstellungen, Token und Verlauf beim Update erhalten.

---

## Entwicklung

### Voraussetzungen

- **JDK 17** (z. B. Temurin; Android Studio bringt eines mit)
- **Android SDK** mit Plattform **36** und Build-Tools – Android Studio installiert sie; sonst
  `ANDROID_HOME` setzen oder `sdk.dir` in `local.properties` eintragen
- **Node.js ≥ 18** für die Routine-Skripte (CI nutzt 18 und 22)

### Bauen und testen

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # Unit-Tests
./gradlew installDebug         # aufs angeschlossene Gerät oder den Emulator
./gradlew assembleRelease      # Release (signiert, wenn keystore.properties existiert)
```

Unter Windows ohne Git Bash: `gradlew.bat …`

### Routine-Skripte

Alle ohne Abhängigkeiten, Node ≥ 18, `--help` zeigt alle Optionen.

| Skript | Zweck |
|---|---|
| [`validate-brief.mjs`](routine/validate-brief.mjs) | prüft Briefings (Schema 1 und 2), `index.json` und mit `--section` einzelne Rubrik-Dateien |
| [`find-image.mjs`](routine/find-image.mjs) | sucht frei lizenzierte Bilder auf Wikimedia Commons und gibt sie als JSON aus |
| [`assemble-brief.mjs`](routine/assemble-brief.mjs) | setzt die Rubrik-Dateien zu einem Briefing zusammen; `--overview` zeigt Überschriften |
| [`publish-brief.mjs`](routine/publish-brief.mjs) | schreibt ein Briefing in einen Checkout von `briefs` (idempotent; `--merge` fügt Rubriken in ein vorhandenes Tagesbriefing ein) |
| [`email-config.mjs`](routine/email-config.mjs) | prüft die E-Mail-Einstellungen `config/email.json` und gibt Schalter und Empfänger als JSON aus |
| [`render-email.mjs`](routine/render-email.mjs) | baut die E-Mail (HTML, Text, Betreff) aus einem Briefing und Live-Wetter |

```bash
node --test routine/test/*.test.mjs                                              # Tests (ohne Netz)
node routine/validate-brief.mjs app/src/main/assets/sample-brief.json            # Datei prüfen
node routine/find-image.mjs "Semperoper Dresden" --limit 3                        # Bilder suchen
mkdir -p routine/out/data                                                        # Probe-Veröffentlichung
node routine/publish-brief.mjs --brief app/src/main/assets/sample-brief.json --data-dir routine/out/data
```

`routine/out/` ist in `.gitignore`. Das Demo-Briefing `app/src/main/assets/sample-brief.json` ist
ein echtes Schema-2-Briefing (vier Rubriken), das genau nach den Regeln der Routine recherchiert
und geschrieben wurde.

### Emulator und Gerät

- Zwei virtuelle Geräte lohnen sich: ein aktuelles (API 36) und ein altes mit **API 24**
  (Android 7.0), um die Kompatibilität zu prüfen.
- Zwischen **3-Tasten- und Gesten-Navigation** umschalten:
  ```bash
  adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
  adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
  ```
- Dunkelmodus: `adb shell cmd uimode night yes` (bzw. `no`)
- Große Schrift: `adb shell settings put system font_scale 1.3` (zurück mit `1.0`)
- Eigenes Handy (S26 Ultra): **Einstellungen → Telefoninfo → Softwareinformationen** →
  7× auf **Buildnummer** tippen, dann unter **Entwickleroptionen** **USB-Debugging** oder
  **Wireless Debugging** (`adb pair …`, `adb connect …`) einschalten.

### Release

Der bequeme Weg ist **`scripts/release.ps1`** (siehe [Updates](#updates)): Version erhöhen,
signiert bauen, als GitHub-Release veröffentlichen – die App bietet das Update dann selbst an.

Alternativ über CI: Version in `gradle.properties` (`MB_VERSION_CODE`, `MB_VERSION_NAME`) erhöhen,
committen, Tag setzen und pushen:

```bash
git tag v1.1.0
git push origin v1.1.0
```

Der Workflow **Android** testet, baut die signierte APK und veröffentlicht
`Morgenbriefing-v1.1.0.apk` als GitHub-Release – aber nur, wenn die Signatur-Secrets hinterlegt
sind (eine anders signierte APK ließe sich nicht als Update installieren). Tags mit Bindestrich
(`v1.2.0-beta1`) werden als Vorabversion markiert.

**Signaturschlüssel (einmalig):** Updates lassen sich nur über eine Version mit *demselben*
Schlüssel installieren.

```bash
keytool -genkeypair -v -keystore morgenbriefing-release.jks -alias morgenbriefing \
  -keyalg RSA -keysize 4096 -validity 10000
```

Den Keystore samt Passwörtern sicher aufbewahren, z. B. verschlüsselt auf dem NAS oder im
Passwortmanager – ohne ihn gibt es keine Updates mehr. Für CI-Releases im Repo unter
**Settings → Secrets and variables → Actions** anlegen:

| Secret | Inhalt |
|---|---|
| `MB_KEYSTORE_BASE64` | der Keystore als Base64: `base64 -w0 morgenbriefing-release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("morgenbriefing-release.jks"))`) |
| `MB_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `MB_KEYSTORE_ALIAS` | `morgenbriefing` |
| `MB_KEYSTORE_KEY_PASSWORD` | Passwort des Schlüssels |

Lokal signierst du über eine `keystore.properties` im Projektordner (steht in `.gitignore`):

```properties
FILE=morgenbriefing-release.jks
PASSWORD=…
ALIAS=morgenbriefing
KEY_PASSWORD=…
```

### Projektstruktur

```
app/                 Android-App (Kotlin, Jetpack Compose, Material 3)
docs/                Architektur und Datenformat
routine/             Prompt der Cloud-Routine, Validator, Bildsuche, Zusammensetzen, Veröffentlichen, Tests
scripts/             Release-Skript (Version erhöhen, signiert bauen, veröffentlichen)
.github/workflows/   Build und Tests, Releases, Routine-Skripte, Prüfung des Branches briefs
```

Der Branch **`briefs`** enthält nur Daten: `briefs/YYYY-MM-DD.json`, `latest.json`, `index.json`
und die Einstellungen des E-Mail-Briefings unter `config/`.

| Workflow | Wann | Was |
|---|---|---|
| **Android** | Push/PR auf `main`, Tags `v*` | Unit-Tests, Debug-APK; bei Tags signiertes Release |
| **Routine-Skripte** | Änderungen an `routine/`, am Demo-Briefing oder am Datenformat | Tests der Node-Skripte (Node 18 und 22), Prüfung des Demo-Briefings |
| **Briefs prüfen** | täglich 04:45 UTC, manuell | validiert `briefs`, meldet ein fehlendes Briefing |

## Datenschutz

- Wetter: Open-Meteo bekommt nur die Koordinaten deiner beiden Orte – kein Konto, kein Schlüssel.
- Nachrichten: kommen ausschließlich aus deinem privaten Repo, gelesen mit einem Token nur für
  dieses Repo, das verschlüsselt auf dem Gerät liegt. Geschrieben wird damit nur
  `config/email.json` (E-Mail-Briefing).
- Bilder: werden direkt von Wikimedia Commons (`upload.wikimedia.org`) geladen.
- E-Mail: Die Empfänger stehen nur in deinem privaten Repo (`config/email.json`). Die E-Mail geht
  aus deinem eigenen Gmail-Konto einzeln an jede Adresse; Bilder darin lädt das E-Mail-Programm
  von Wikimedia Commons, es gibt keine Zählpixel und keine Tracking-Links.
- Kein Tracking, keine Werbung, keine Analyse-Dienste.
