# Morgenbriefing

**Dein Morgen auf einen Blick:** das Wetter vor der Tür, der Tag an deinem Ort und die
Nachrichten aus deinen Themen – frisch aus den RSS-Feeds der Redaktionen, jederzeit beim Öffnen
der App und morgens als Benachrichtigung. Alles läuft auf deinem Handy: kein Konto, kein Server,
keine Cloud, keine KI, keine Werbung.

Android-App (Kotlin, Jetpack Compose, Material 3), ab **Android 7.0** (API 24).

---

## Was die App kann

| | |
|---|---|
| **Heute** | Oben ein animierter Himmel passend zu Wetter und Tageszeit. Darunter „Vor der Tür“ an deinem Ort (jetzt und die nächsten Stunden, Sonnenauf- und -untergang), die Tageskurve für dein Zeitfenster mit Regenwahrscheinlichkeit, Wetterwarnungen und was du anziehen solltest – jeder Block abschaltbar. Dann die Nachrichten: **„Für dich“** zeigt pro Thema die neuesten Meldungen, **„Neueste“** alles chronologisch, und jedes Thema hat einen eigenen Chip mit **endlos scrollbarer** Liste. |
| **Immer aktuell** | Die App holt die Feeds beim Öffnen, beim Herunterziehen und morgens zur Benachrichtigung. Die letzten Meldungen bleiben gespeichert, auch offline. Standardmäßig siehst du die letzten 24 Stunden; der Zeitraum ist einstellbar. |
| **Artikel lesen** | Ein Tipp öffnet den Artikel **in der App**: Bild, Überschrift und der Text der Seite, ohne Werbung, Cookie-Banner und Navigation (Lesemodus nach Mozillas Readability). „Zuhören“ liest dort den ganzen Artikel vor. Teilen, Link kopieren, im Browser öffnen, Quelle oder Thema ausblenden – alles im ⋮-Menü. Wer lieber im Browser liest, schaltet das unter Feed um. |
| **10 Themen** + eigene | 🤖 KI & Tech · 📈 Wirtschaft & Märkte · 🏛️ Politik · 🌍 Welt · 📍 Region · ⚽ Sport · 🔭 Wissenschaft & Raumfahrt · 🩺 Gesundheit · 🌱 Klima & Umwelt · 🎮 Games & Entertainment. Reihenfolge und Auswahl legst du fest – und du kannst **eigene Themen** anlegen (Name, Emoji, Farbe) und mit beliebigen Feeds füllen. |
| **Quellen** | Rund 60 geprüfte deutschsprachige Feeds (tagesschau, SPIEGEL, ZEIT, FAZ, SZ, heise, Golem, kicker, Spektrum …), jeder einzeln abschaltbar. **Eigene Quelle hinzufügen:** Feed-Adresse oder einfach die Website eintippen – die App findet den Feed. |
| **Region** | Das Thema „Region“ nimmt die öffentlich-rechtlichen Sender deines Bundeslandes (MDR, NDR, WDR, BR24, SWR, hessenschau, rbb24 …). Die Region folgt deinem Ort oder du wählst sie selbst. |
| **Orte** | Ortssuche mit Vorschlägen und Flaggen, **„Mein Standort“** per GPS, Schnellauswahl großer Städte; getrennte Orte für „Vor der Tür“ und „Tagsüber“ plus Zeitfenster. |
| **Einstellungen** | Hell, Dunkel oder wie das System; Akzentfarben (Material You, Morgenrot, Sonne, Ozean, Wald, Lavendel, Beere, Graphit); Meldungen pro Thema; Zeitraum; Bilder an/aus; Wetterblöcke; Uhrzeit der Benachrichtigung; Akku-Optimierung; Updates. |
| **Morgen-Benachrichtigung** | Ein exakter Alarm (Standard 6:30) holt Wetter und Feeds und zeigt eine ausklappbare Benachrichtigung mit Wetter, Kleidungstipp und den Top-Schlagzeilen – eine je Thema. |
| **Updates in der App** | Die App sieht selbst nach, ob es auf GitHub eine neuere Version gibt, lädt sie herunter, prüft die Signatur und startet die Installation. |

Randlos (Edge-to-Edge), sauber mit **Gesten- und 3-Tasten-Navigation**, inklusive Predictive Back.

## So funktioniert's

```
Beim Öffnen / Herunterziehen / morgens (AlarmManager, exakt)
  ├─ Open-Meteo: Wetter live für deine Orte (ohne Schlüssel)
  ├─ RSS/Atom-Feeds der eingeschalteten Quellen, bis zu 6 parallel
  │     → zusammengeführt pro Thema, Duplikate raus, neueste zuerst
  │     → als JSON im App-Speicher (offline da)
  └─ Benachrichtigung (BigText) mit Wetter, Kleidung, Schlagzeilen
```

- **Wetter** kommt live von [Open-Meteo](https://open-meteo.com) (ohne Schlüssel, ohne Konto).
- **Nachrichten** liest die App direkt aus den öffentlichen RSS-/Atom-Feeds der Redaktionen –
  Schlagzeile, Teaser, Bild und Link. Den ganzen Artikel zeigt die App im eigenen Lesemodus: sie
  lädt die Seite der Redaktion und zeigt nur Text und Bilder, ohne Werbung, Skripte und
  Cookie-Banner. Die Liste der eingebauten Quellen steht in
  [`FeedCatalog.kt`](app/src/main/java/com/cedrickgd/morgenbriefing/data/news/FeedCatalog.kt).
- **Fällt eine Quelle aus**, bleiben ihre letzten Meldungen drei Tage sichtbar; die Einstellungen
  zeigen, welche Quelle gerade nicht antwortet.

Mehr dazu: [Architektur](docs/ARCHITECTURE.md) · [Design](docs/DESIGN.md)

---

## Einrichtung

### 1. App installieren

1. Unter **[Releases](https://github.com/CedrickGD/Morgenbriefing/releases)** die neueste
   `Morgenbriefing-vX.Y.Z.apk` herunterladen.
2. Die Datei öffnen. Fragt Android nach „Unbekannte Apps installieren“, für Browser bzw.
   „Eigene Dateien“ **Zulassen** wählen und die Installation bestätigen.
3. Beim ersten Start **Benachrichtigungen erlauben** (ab Android 13 fragt die App danach) und
   **deinen Ort** wählen.

Das ist nur beim ersten Mal nötig: Spätere Versionen findet die App selbst (siehe
[Updates](#updates)). Beim ersten Update fragt Android, ob **Morgenbriefing** Apps installieren
darf – einmal **Zulassen**, danach geht es mit einem Tipp.

> Lieber den neuesten Entwicklungsstand? Jeder Build auf `main` legt unter **Actions → Android →
> Artefakt `morgenbriefing-debug-apk`** eine Debug-Version ab. Sie heißt
> `com.cedrickgd.morgenbriefing.debug` und lässt sich parallel zur normalen App installieren.

### 2. Themen und Quellen anpassen

- **Einstellungen → Themen:** antippen blendet ein Thema ein oder aus, lange drücken sortiert.
  **„Eigenes Thema“** legt ein neues an.
- **Einstellungen → Quellen:** je Thema alle Feeds mit Schalter. **„Eigene Quelle“** nimmt eine
  Feed-Adresse oder die Website einer Redaktion und sucht den Feed selbst. Unter **„Deine
  Region“** wählst du das Bundesland für die Regionalnachrichten.
- **Einstellungen → Feed:** wie viele Meldungen ein Thema in „Für dich“ zeigt, wie weit der Feed
  zurückreicht und ob Bilder geladen werden.

### 3. Samsung: Akku nicht einschränken

Damit One UI die App morgens nicht schlafen legt:

**Einstellungen → Apps → Morgenbriefing → Akku → „Nicht eingeschränkt“**

Die App bietet unter **Einstellungen** zusätzlich „Akku-Optimierung aus“ an (System-Dialog).

### Updates

Neue Versionen veröffentlichst du mit **`scripts/release.ps1`** (PowerShell): Das Skript erhöht
`MB_VERSION_CODE` und `MB_VERSION_NAME` in `gradle.properties`, baut die mit deinem
Release-Schlüssel signierte APK und legt sie als GitHub-Release ab. Aufruf und Optionen stehen im
Kopf des Skripts. Die App prüft daraufhin von selbst (und auf Wunsch in den Einstellungen), ob es
eine neuere Version gibt, und bietet das Update an. Dafür muss das Repo öffentlich sein (die App
liest GitHub Releases ohne Anmeldung). Weil jede Version mit demselben Schlüssel signiert ist,
bleiben alle Einstellungen beim Update erhalten.

---

## Entwicklung

### Voraussetzungen

- **JDK 17** (z. B. Temurin; Android Studio bringt eines mit)
- **Android SDK** mit Plattform **36** und Build-Tools – Android Studio installiert sie; sonst
  `ANDROID_HOME` setzen oder `sdk.dir` in `local.properties` eintragen

### Bauen und testen

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # Unit-Tests (Parser, Feed-Katalog, Wetter, Einstellungen …)
./gradlew installDebug         # aufs angeschlossene Gerät oder den Emulator
./gradlew assembleRelease      # Release (signiert, wenn keystore.properties existiert)
```

Unter Windows ohne Git Bash: `gradlew.bat …`

Die eingebauten Feeds lassen sich von Hand prüfen, z. B. mit `curl -sI <url>`; die Unit-Tests
stellen sicher, dass jede Quelle eine eindeutige Id, eine https-Adresse und ein bekanntes Thema
hat. Der RSS-Parser läuft in den Tests mit derselben kXML-Implementierung wie auf Android.

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
- Eigenes Handy: **Einstellungen → Telefoninfo → Softwareinformationen** → 7× auf
  **Buildnummer** tippen, dann unter **Entwickleroptionen** **USB-Debugging** oder
  **Wireless Debugging** (`adb pair …`, `adb connect …`) einschalten.

### Release

Der bequeme Weg ist **`scripts/release.ps1`** (siehe [Updates](#updates)): Version erhöhen,
signiert bauen, als GitHub-Release veröffentlichen – die App bietet das Update dann selbst an.

Alternativ über CI: Version in `gradle.properties` (`MB_VERSION_CODE`, `MB_VERSION_NAME`) erhöhen,
committen, Tag setzen und pushen:

```bash
git tag v2.0.0
git push origin v2.0.0
```

Der Workflow **Android** testet, baut die signierte APK und veröffentlicht
`Morgenbriefing-v2.0.0.apk` als GitHub-Release – aber nur, wenn die Signatur-Secrets hinterlegt
sind (eine anders signierte APK ließe sich nicht als Update installieren). Tags mit Bindestrich
(`v2.1.0-beta1`) werden als Vorabversion markiert.

**Signaturschlüssel (einmalig):** Updates lassen sich nur über eine Version mit *demselben*
Schlüssel installieren.

```bash
keytool -genkeypair -v -keystore morgenbriefing-release.jks -alias morgenbriefing \
  -keyalg RSA -keysize 4096 -validity 10000
```

Den Keystore samt Passwörtern sicher aufbewahren – ohne ihn gibt es keine Updates mehr. Für
CI-Releases im Repo unter **Settings → Secrets and variables → Actions** anlegen:

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
docs/                Architektur und Designsprache
scripts/             Release-Skript (Version erhöhen, signiert bauen, veröffentlichen)
.github/workflows/   Build und Tests, Releases
```

| Workflow | Wann | Was |
|---|---|---|
| **Android** | Push/PR auf `main`, Tags `v*` | Unit-Tests, Debug-APK; bei Tags signiertes Release |

## Quellen

Die Nachrichten stammen aus den öffentlichen RSS-/Atom-Feeds der jeweiligen Redaktionen. Die App
zeigt nur, was die Feeds liefern (Schlagzeile, Teaser, Vorschaubild) und verlinkt auf den Artikel.
Alle Rechte an den Inhalten liegen bei den Verlagen und Sendern. Wer eine Quelle nicht in der App
sehen möchte, schaltet sie in den Einstellungen ab.

## Datenschutz

- **Wetter:** Open-Meteo bekommt nur die Koordinaten deiner beiden Orte – kein Konto, kein Schlüssel.
- **Nachrichten:** Die App ruft die Feeds direkt bei den Redaktionen ab, wie ein Feedreader; sie
  sendet dabei nur die Feed-Adresse und eine beschreibende User-Agent-Kennung. Bilder lädt sie
  ausschließlich über https direkt vom jeweiligen Verlag.
- **Standort:** „Mein Standort“ fragt den Standort einmalig ab und wandelt ihn mit Androids
  Geocoder in einen Ortsnamen um; gespeichert werden nur Name und Koordinaten des Orts.
- **Updates:** Die App fragt GitHub nach der neuesten Veröffentlichung dieses Repos.
- Es gibt keine Konten, keine Analyse-Dienste, keine Werbung und keine Server des Entwicklers.
