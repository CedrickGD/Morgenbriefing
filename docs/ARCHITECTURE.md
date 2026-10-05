# Morgenbriefing – Architektur

Persönliche Android-App: jeden Morgen um 5:30 eine Benachrichtigung mit Wetter („Vor der Tür“ in
Bad Düben, Tagesverlauf in Leipzig 10–18 Uhr, was anziehen) und frisch recherchierten News in zehn
Rubriken, dazu ein Verlauf aller bisherigen Tage, ein Artikel-Reader mit Vorlesen, ein KI-Chat
zu den Artikeln und auf Wunsch dasselbe Briefing als E-Mail. Hell/Dunkel + Akzentfarben,
„Morgenglas“-Design ([DESIGN.md](DESIGN.md)).

## Datenfluss

```
04:00  News-Routine (claude.ai, Cloud)            05:20  E-Mail-Routine (claude.ai, nur Gmail)
       ├─ recherchiert 10 Rubriken (Sub-Agenten)         ├─ liest config/email.json (Schalter, Empfänger)
       ├─ schreibt eigene Artikel + Commons-Bilder       ├─ render-email.mjs: latest.json + Wetter live
       └─ briefs/YYYY-MM-DD.json, latest.json,           └─ eine Mail: an 1. Empfänger, BCC an den Rest
          index.json  ──► Branch `briefs` ◄──────────────── config/email-sent.json (Versand vermerkt)
                               ▲      ▲
                               │      └── App schreibt config/email.json (E-Mail-Einstellungen)
05:30  App (AlarmManager)      │
       ├─ Open-Meteo live (Wetter)
       ├─ GitHub contents API: latest.json  ──┘
       ├─ speichert Morgen-Snapshot (Verlauf)
       └─ Benachrichtigung
```

* **Wetter kommt nie aus dem Repo** – die App holt es immer live bei Open-Meteo (kein Key).
  Um 5:30 und beim ersten Öffnen am Vormittag wird ein Snapshot für den Verlauf gespeichert.
* **News** liegen als JSON auf dem Branch `briefs` des privaten Repos `CedrickGD/Morgenbriefing`
  (Format: [brief-format.md](brief-format.md)). Die App liest sie mit einem fein-granularen
  GitHub-Token (*Contents: Read and write*, nur dieses Repo; Schreiben nur für
  `config/email.json`), den der Nutzer einmal in den Einstellungen einträgt. Ohne Token zeigt
  die App das Demo-Briefing aus `assets/sample-brief.json`.
* **Routinen** laufen in der Cloud (claude.ai), der Auftrag liegt im Repo:
  [routine/PROMPT.md](../routine/PROMPT.md) (News, zwei Cron-Starts + Berlin-Zeit-Wächter wegen
  Sommer-/Winterzeit) und [routine/EMAIL_PROMPT.md](../routine/EMAIL_PROMPT.md) (E-Mail, nur
  Gmail-Connector, keine Web-Werkzeuge, Empfänger ausschließlich aus `email-config.mjs`).
* **KI** (Chat „Frag nach“, Live-News): direkt von der App an die Claude-API mit dem eigenen
  API-Key des Nutzers, nur ab Android 8 – Details in [AI.md](AI.md).
* **Updates**: die App prüft GitHub Releases des privaten Repos, lädt die APK, prüft die Signatur
  und übergibt sie dem System-Installer. Releases baut `scripts/release.ps1` lokal und signiert
  sie mit dem Release-Key.

## Module (`app/src/main/java/com/cedrickgd/morgenbriefing`)

| Paket | Inhalt |
|---|---|
| `data/model` | Serialisierbare Modelle: Brief (Schema 2), Rubriken-Katalog `Topics`, Wetter, Einstellungen, `EmailConfig`, Load-Ergebnisse |
| `data/remote` | `OpenMeteoClient`, `GitHubBriefSource` (Briefs lesen), `GitHubConfigStore` (config/email.json lesen/schreiben, SHA-Konflikte) |
| `data/local` | `BriefStore` (JSON-Dateien), `TokenCipher` (AndroidKeyStore AES-GCM für GitHub-Token und API-Key) |
| `data` | `SettingsRepository` (DataStore), `WeatherRepository`, `BriefRepository`, `EmailSettingsRepository` |
| `domain` | `Condition`, `WeatherInsights`, `ClothingAdvisor` (Headline, Kleidung, Warnungen, Notification-Text), `Fmt` |
| `notify` | `AlarmScheduler`, `MorningAlarmReceiver`, `SystemEventsReceiver`, `MorningWorker`, `NewsFollowUpWorker`, `Notifications` |
| `update` | In-App-Updater: `UpdateChecker`, `ApkDownloader`, `ApkVerifier`, `ApkInstaller`, `UpdateWorker` |
| `ai`, `ai/claude` | Chat, Live-News, Kosten/Limit (`UsageStore`), Claude-Anbindung (Anthropic Java SDK, Streaming) |
| `listen` | „Zuhören“: Text-to-Speech der Artikel, Mini-Player, Audio-Fokus |
| `ui/shell` | Wischbarer Pager Verlauf ← Heute → Einstellungen, Glas-Seitenindikator, Chrome-Steuerung |
| `ui/glass` | Morgenglas: `Modifier.glass`, `GlassCard`, durchgehender Himmel (`AmbientSky`), Seitentitel/-leiste |
| `ui/today`, `ui/news` | Heute-Seite, News-Feed (Chips, Rubriken, „Mehr“, Live) |
| `ui/reader` | Artikel-Reader (blättert durch alle Artikel eines Tages) |
| `ui/chat` | „Frag nach“-Chat zum Briefing oder zu einem Artikel |
| `ui/history`, `ui/brief` | Verlauf und Tagesdetail |
| `ui/settings`, `ui/settings/email` | Einstellungen inkl. KI, News-Quelle, Updates und E-Mail-Briefing |
| `ui/theme` | Theme, Akzent-Schemata, Typografie, `Reading` (Serif-Lesestile) |
| `ui/nav` | Typsichere Routen: `MainRoute` (Pager), `DayRoute`, `ArticleRoute`, `ChatRoute` |

Kein DI-Framework: `AppContainer` (ein Objekt pro Prozess) wird über `context.container`
erreicht, KI und Vorlesen über `context.ai` / `context.listen`. ViewModels über
`viewModelFactory { initializer { … } }`.

## Plattform-Regeln

* **minSdk 24 (Android 7.0)**, target/compile 36. `java.time` via Core-Library-Desugaring.
  Alles, was neuer ist, mit `Build.VERSION.SDK_INT`-Checks (KI 26+, Dynamic Color 31+, echte
  Glas-Unschärfe 31+ sonst getönte Fläche, POST_NOTIFICATIONS 33+, exakte Alarme 31+).
* Android 7 kennt die Let's-Encrypt-Wurzel nicht: `network_security_config.xml` bündelt
  ISRG Root X1/X2 zusätzlich zu den System-Zertifikaten.
* **Edge-to-edge** auf allen Versionen, Insets korrekt für **Gesten- und 3-Tasten-Navigation**
  (Samsung One UI kann beides). Keine Leiste unten: zwischen den Seiten wird gewischt, der
  Glas-Indikator sitzt über der Navigationsleiste.
* **Predictive Back**; Reader, Tagesdetail und Chat liegen als Routen über dem Pager.
* Exakter Alarm: `setExactAndAllowWhileIdle`; ohne Berechtigung Fallback `setAndAllowWhileIdle`.
  Neu planen bei Boot, Update, Zeit-/Zeitzonenwechsel und nach jedem Auslösen.
* Samsung-Akku-Optimierung: die Einstellungen führen zu „Nicht eingeschränkt“.

## Design

Siehe [DESIGN.md](DESIGN.md): Morgenglas – ein durchgehender, wetterabhängiger Himmel hinter
allen Seiten, Glasflächen darüber, Serif-Überschriften, redaktioneller Feed (großes Bild, große
Serif-Headline, Lead, Meta-Zeile mit Zuhören/Teilen), ruhige Bewegung, Barrierefreiheit
(Content-Descriptions, Touch-Ziele ≥ 48 dp, Kontrast ≥ 4.5:1, „Animationen entfernen“).
