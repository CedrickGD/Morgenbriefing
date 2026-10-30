# Morgenbriefing – Architektur

Android-App für alle: morgens eine Benachrichtigung mit Wetter („Vor der Tür“ an deinem Ort,
Tagesverlauf für dein Zeitfenster, was anziehen) und den Top-Schlagzeilen, und jederzeit beim
Öffnen die aktuellen Nachrichten aus deinen Themen – direkt aus den RSS-/Atom-Feeds der
Redaktionen, endlos scrollbar pro Thema. Hell/Dunkel + Akzentfarben, „Morgenglas“-Design
([DESIGN.md](DESIGN.md)). Alles läuft auf dem Gerät; es gibt keinen Server, keine Cloud und
keine KI.

## Datenfluss

```
Öffnen · Herunterziehen · Morgenalarm (AlarmManager, exakt, Standard 06:30)
  ├─ WeatherRepository ── OpenMeteoClient ─────────► api.open-meteo.com (Wetter, Geocoding)
  │     └─ WeatherStore: store/weather/latest.json (Offline-Start)
  └─ NewsRepository ───── RssClient (OkHttp + RssParser) ─► Feeds der eingeschalteten Quellen
        ├─ FeedCatalog + Regions + eigene Quellen (Einstellungen) = Quellen je Thema
        ├─ je Thema: Ergebnisse zusammenführen, Duplikate raus, neueste zuerst,
        │   ausgefallene Quellen behalten ihre letzten Meldungen (3 Tage)
        └─ NewsCache: store/news/<thema>.json (Offline-Start)
```

* **Wetter** kommt live von Open-Meteo (kein Key). Die letzte Antwort liegt als Offline-Kopie im
  App-Speicher.
* **Nachrichten** liest `RssClient` mit Androids `XmlPullParser` (kXML; in Unit-Tests dieselbe
  Implementierung über `kxml2`). `RssParser` versteht RSS 2.0, RSS 1.0 (RDF) und Atom, holt
  Schlagzeile, Teaser (HTML → Text via `HtmlText`), Datum (`RssDates`: RFC 1123, ISO, lokal) und
  das Bild (media:content/thumbnail, enclosure, `<img>` im HTML). Artikel-Ids sind ein Hash des
  normalisierten Links (ohne Tracking-Parameter), so bleibt eine Meldung über Aktualisierungen
  hinweg dieselbe.
* **Themen** (`Topics`): zehn eingebaute plus eigene (`AppSettings.customTopics`). Das Thema
  „Region“ bekommt seine Quellen aus dem gewählten `Regions`-Preset (ein Bundesland, öffentlich-
  rechtliche Sender). `Topics.catalogue(settings)` liefert alle Themen in der Nutzerreihenfolge.
* **Quellen** (`FeedCatalog`): rund 60 eingebaute Feeds mit stabilen Ids; `disabledFeeds` schaltet
  einzelne ab, `customFeeds` fügt eigene hinzu (jedem Thema). `RssClient.discover` findet den Feed
  hinter einer Website (`<link rel="alternate" type="application/rss+xml">`, gängige Pfade).
* **Feed-Logik** (`ui/news/NewsModels.kt`): pro sichtbarem Thema eine `SectionUi` mit den ersten
  `sectionSize` Meldungen („Für dich“) und allen Meldungen (eigener Chip, seitenweise
  enthüllt = endlos); „Neueste“ ist alles zusammen chronologisch. Der Zeitraum (`NewsAge`)
  filtert, lässt aber mindestens drei Meldungen pro Thema stehen.
* **Benachrichtigung**: `MorningWorker` holt Wetter und Feeds, `topHeadlines` nimmt je sichtbarem
  Thema die neueste Schlagzeile, `WeatherInsights.notification` baut den Text.
* **Updates**: die App prüft GitHub Releases des (öffentlichen) Repos ohne Token, lädt die APK,
  prüft die Signatur und übergibt sie dem System-Installer.
* **Standort**: `LocationFinder` holt einen groben Fix über `LocationManager` (ohne Play
  Services) und den Ortsnamen über Androids `Geocoder`.

## Module (`app/src/main/java/com/cedrickgd/morgenbriefing`)

| Paket | Inhalt |
|---|---|
| `data/model` | `Topic`, `FeedSource`, `NewsItem`, `TopicNews`, `Topics` (Katalog), Wetter, `AppSettings` |
| `data/news` | `FeedCatalog` + `Regions` (eingebaute Quellen), `RssParser`, `RssDates`, `HtmlText` |
| `data/remote` | `OpenMeteoClient`, `RssClient` (Feeds lesen, Feed hinter einer Website finden) |
| `data/local` | `WeatherStore`, `NewsCache` (JSON-Dateien, atomar geschrieben) |
| `data` | `SettingsRepository` (DataStore), `WeatherRepository`, `NewsRepository` |
| `domain` | `Condition`, `WeatherInsights`, `ClothingAdvisor` (Headline, Kleidung, Warnungen, Notification-Text), `Fmt` |
| `location` | `LocationFinder` („Mein Standort“) |
| `notify` | `AlarmScheduler`, `MorningAlarmReceiver`, `SystemEventsReceiver`, `MorningWorker`, `Notifications` |
| `update` | In-App-Updater: `UpdateChecker`, `ApkDownloader`, `ApkVerifier`, `ApkInstaller`, `UpdateWorker` |
| `listen` | „Zuhören“: Text-to-Speech von Schlagzeile und Teaser, Mini-Player, Audio-Fokus |
| `ui/shell` | Wischbarer Pager Heute → Einstellungen, Glas-Seitenindikator, Chrome-Steuerung |
| `ui/glass` | Morgenglas: `Modifier.glass`, `GlassCard`, durchgehender Himmel (`AmbientSky`), Seitentitel/-leiste |
| `ui/today`, `ui/news` | Heute-Seite, News-Feed (Chips, Rubriken, endlose Listen, Themen-Sheet) |
| `ui/settings` | Einstellungen: Status, Wecker, Themen (mit eigenen), Quellen, Feed, Darstellung, Orte (Picker-Sheet mit Standort), Wetterblöcke, App/Updates |
| `ui/theme` | Theme, Akzent-Schemata, Typografie, `Reading` (Serif-Lesestile) |
| `ui/nav` | `MainRoute` (der Pager); Artikel öffnen im In-App-Browser (Custom Tab) |

Kein DI-Framework: `AppContainer` (ein Objekt pro Prozess) wird über `context.container`
erreicht, Vorlesen über `context.listen`. ViewModels über `viewModelFactory { initializer { … } }`.

## Plattform-Regeln

* **minSdk 24 (Android 7.0)**, target/compile 36. `java.time` via Core-Library-Desugaring.
  Alles, was neuer ist, mit `Build.VERSION.SDK_INT`-Checks (Dynamic Color 31+, echte
  Glas-Unschärfe 31+ sonst getönte Fläche, POST_NOTIFICATIONS 33+, exakte Alarme 31+,
  `getCurrentLocation` 30+).
* Android 7 kennt die Let's-Encrypt-Wurzel nicht: `network_security_config.xml` bündelt
  ISRG Root X1/X2 zusätzlich zu den System-Zertifikaten. Klartext-`http://` ist aus; Feeds und
  Bilder gehen nur über https.
* **Edge-to-edge** auf allen Versionen, Insets korrekt für **Gesten- und 3-Tasten-Navigation**.
  Keine Leiste unten: zwischen den Seiten wird gewischt, der Glas-Indikator sitzt über der
  Navigationsleiste.
* **Predictive Back**: Zurück auf „Einstellungen“ gleitet zu „Heute“.
* Exakter Alarm: `setExactAndAllowWhileIdle`; ohne Berechtigung Fallback `setAndAllowWhileIdle`.
  Neu planen bei Boot, Update, Zeit-/Zeitzonenwechsel und nach jedem Auslösen.
* Samsung-Akku-Optimierung: die Einstellungen führen zu „Nicht eingeschränkt“.
* Nichts Geheimes auf dem Gerät: Einstellungen und Caches nehmen an Android-Backups teil.

## Design

Siehe [DESIGN.md](DESIGN.md): Morgenglas – ein durchgehender, wetterabhängiger Himmel hinter
allen Seiten, Glasflächen darüber, Serif-Überschriften, redaktioneller Feed (großes Bild, große
Serif-Headline, Lead, Meta-Zeile mit Zuhören/Teilen), ruhige Bewegung, Barrierefreiheit
(Content-Descriptions, Touch-Ziele ≥ 48 dp, Kontrast ≥ 4.5:1, „Animationen entfernen“).
