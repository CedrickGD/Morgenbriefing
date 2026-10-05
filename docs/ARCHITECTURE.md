# Morgenbriefing – Architektur

Persönliche Android-App: jeden Morgen um 5:30 eine Benachrichtigung mit Wetter („Vor der Tür“ in
Bad Düben, Tagesverlauf in Leipzig 10–18 Uhr, was anziehen) und frisch recherchierten News
(KI & Tech, Wirtschaft & Märkte, Politik). Verlauf aller bisherigen Tage. Hell/Dunkel + Akzentfarben.

## Datenfluss

```
05:00  Cloud-Routine (claude.ai, täglich)                    05:30  App (AlarmManager, exakt)
       ├─ recherchiert + verifiziert News                            ├─ Open-Meteo live (Wetter)
       ├─ schreibt briefs/YYYY-MM-DD.json, latest.json,             ├─ GitHub contents API: latest.json
       │  index.json auf Branch `briefs`                            ├─ speichert Morgen-Snapshot (Verlauf)
       └─ git push  ──────────────► GitHub (privat) ◄───────────────└─ Benachrichtigung (BigText)
```

* **Wetter kommt nie aus dem Repo** – die App holt es immer live bei Open-Meteo (kein Key).
  Um 5:30 und beim ersten Öffnen am Vormittag wird ein Snapshot für den Verlauf gespeichert.
* **News** liegen als JSON auf dem Branch `briefs` des privaten Repos `CedrickGD/Morgenbriefing`.
  Die App liest sie mit einem fein-granularen GitHub-Token (nur *Contents: Read* auf dieses Repo),
  den der Nutzer einmal in den Einstellungen einträgt. Ohne Token zeigt die App das Demo-Briefing
  aus `assets/sample-brief.json`.
* Format: [brief-format.md](brief-format.md).

## Module (`app/src/main/java/com/cedrickgd/morgenbriefing`)

| Paket | Inhalt |
|---|---|
| `data/model` | Serialisierbare Modelle (Brief, WeatherSnapshot, Settings, Load-Ergebnisse) |
| `data/remote` | `OpenMeteoClient`, `GitHubBriefSource` (OkHttp + kotlinx.serialization) |
| `data/local` | `BriefStore` (JSON-Dateien), `TokenCipher` (AndroidKeyStore AES-GCM) |
| `data` | `SettingsRepository` (DataStore), `WeatherRepository`, `BriefRepository` |
| `domain` | `Condition`, `WeatherInsights` (Headline, Kleidung, Warnungen, Notification-Text), `Fmt` |
| `notify` | `AlarmScheduler`, `MorningAlarmReceiver`, `SystemEventsReceiver`, `MorningWorker`, `NewsFollowUpWorker`, `Notifications` |
| `ui/theme` | Theme, Akzent-Schemata, Typografie |
| `ui/components` | Wiederverwendbare Bausteine (Hero, Tageskurve, Kleidung, News …) |
| `ui/…` | Screens: Heute, Verlauf, Tagesdetail, Einstellungen; Navigation |

Kein DI-Framework: `AppContainer` (ein Objekt pro Prozess) wird über `context.container` erreicht.
ViewModels über `viewModelFactory { initializer { … } }`.

## Plattform-Regeln

* **minSdk 24 (Android 7.0)**, target/compile 36. `java.time` via Core-Library-Desugaring.
  Alles, was neuer ist, mit `Build.VERSION.SDK_INT`-Checks (Dynamic Color 31+, Notification-
  Channels 26+, POST_NOTIFICATIONS 33+, exakte Alarme 31+).
* **Edge-to-edge** auf allen Versionen (`enableEdgeToEdge()`), Insets korrekt für
  **Gesten- und 3-Tasten-Navigation** (Samsung One UI kann beides): Scrollinhalte bekommen
  `navigationBars`-Padding bzw. die `NavigationBar` konsumiert die Insets; nichts liegt unter
  der Tastenleiste oder dem Gestenbalken.
* **Predictive Back** (`enableOnBackInvokedCallback`), Navigation Compose übernimmt die Animation.
* Exakter Alarm: `setExactAndAllowWhileIdle`; ohne Berechtigung Fallback `setAndAllowWhileIdle`.
  Neu planen bei Boot, Update, Zeit-/Zeitzonenwechsel und nach jedem Auslösen.
* Samsung-Akku-Optimierung: Einstellungen bieten „Akku-Optimierung aus“ (System-Dialog).

## Design-Richtung

Warm, ruhig, lebendig – nicht „tote E-Mail“.

* Neutrale warme Flächen: hell `#FCFCFB` / Wash `#F9F9F7` / Tinte `#2E2C27`;
  dunkel `#151412` / Flächen `#1E1D1A` / Text `#ECEAE4`. Akzent aus der gewählten Farbe.
* Überschriften Serif (`FontFamily.Serif`, SemiBold), alles andere System-Sans.
* Oben ein animierter Himmel (`WeatherBackdrop`) passend zu Wetter und Tageszeit
  (Morgendämmerung, Nebelschwaden, Regenfäden, Sonnenschein, Sterne), der weich in den
  Hintergrund ausläuft. Bei „Animationen entfernen“ statisch.
* Tageskurve wie eine handgezeichnete Landschaft: eine durchgehende Linie (Temperatur),
  Punkte pro Stunde, dezente Regenbalken, Einzeichnen-Animation.
* Karten mit großen Radien (24 dp), tonal statt Schatten, viel Weißraum, klare Hierarchie.
* Motion: gestaffeltes Einblenden, Zahlen zählen hoch, `animateContentSize`, Pull-to-Refresh.
* Barrierefreiheit: Content-Descriptions, Mindest-Touch-Targets 48 dp, Kontrast ≥ 4.5:1 für Text.
