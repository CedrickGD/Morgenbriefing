# KI-Funktionen (Claude API)

Zwei Funktionen laufen direkt aus der App gegen die Claude Messages API (offizielles
Java-SDK `com.anthropic:anthropic-java`, über `AnthropicOkHttpClient`):

1. **„Frag nach“ – Chat zu Artikeln und zum Briefing**
2. **„⚡ Live“ – brandaktuelle Nachrichten auf Knopfdruck**

Der API-Key gehört dem Nutzer (console.anthropic.com, eigenes Abrechnungskonto, unabhängig
vom Claude-Abo). Er wird in den Einstellungen eingetragen und wie der GitHub-Token mit dem
Android-Keystore verschlüsselt (`TokenCipher`, eigener DataStore-Schlüssel). Ohne Key sind
beide Funktionen sichtbar, erklären sich aber freundlich und verweisen auf die Einstellungen.

## Modelle & Kosten

| Einstellung | Modell-ID | Preis (Input / Output pro Mio. Tokens) | Cache-Read |
|---|---|---|---|
| **Standard** | `claude-sonnet-5-5` | 2 $ / 10 $ | 0,20 $ |
| Wählbar | `claude-opus-5-5` | 4 $ / 20 $ | 0,20 $ |

* Websuche (`web_search_20260209`): **10 $ pro 1.000 Suchen** + Tokens der Ergebnisse.
  Web-Fetch (`web_fetch_20260209`): keine Zusatzkosten, nur Tokens.
* Die App rechnet jede Antwort aus `usage` (input, output, cache_creation, cache_read,
  `server_tool_use.web_search_requests`) mit dieser Preistabelle in Dollar um, summiert pro
  Kalendermonat und zeigt den Stand in den Einstellungen. **Monatslimit** (Standard 5 $,
  einstellbar, 0 = aus): ist es erreicht, werden KI-Anfragen nicht mehr gesendet. Am
  Monatsersten (Europe/Berlin) beginnt die Zählung bei null – auch in einer laufenden App:
  `UsageStore` prüft den Monat mindestens einmal pro Minute.
* Preise stehen an einer Stelle im Code (`ai/AiPricing.kt`) mit Quelle + Datum.

## API-Regeln (aktueller Stand, verbindlich)

* Endpunkt über das SDK: `client.messages().createStreaming(params)` für Chat (Streaming),
  `client.messages().create(...)` bzw. Streaming für Live-Recherche.
* **Thinking**: auf Sonnet 5.5 / Opus 5.5 nicht deaktivierbar (`disabled` → 400) – den
  `thinking`-Parameter weglassen; Tiefe über `outputConfig(OutputConfig.builder().effort(...))`:
  Chat `LOW`, Live-Recherche `MEDIUM`.
* **Kein Prefill**, kein erzwungenes `tool_choice` (`any`/`tool` → 400): `auto` + klare
  Anweisung im System-Prompt.
* **Server-Tools**: `WebSearchTool20260209` (maxUses begrenzen: Chat 3, Live 8–12),
  `WebFetchTool20260209` (maxUses 4–6, `max_content_tokens` begrenzen). Kein separates
  `code_execution` deklarieren. Fehler der Server-Tools kommen als Ergebnis-Block (HTTP 200),
  nicht als Exception.
* **`stop_reason`** immer prüfen: `pause_turn` → Anfrage mit der bisherigen Assistant-Antwort
  fortsetzen; `refusal` → freundliche Meldung (Kategorie aus `stop_details`), kein Absturz;
  `max_tokens` → Hinweis „Antwort gekürzt“.
* **Prompt-Caching**: System-Prompt + Artikel-/Briefing-Kontext sind stabil und bekommen
  `cacheControl(CacheControlEphemeral)` – Folgefragen lesen aus dem Cache. Keine Uhrzeit o. Ä.
  im gecachten Teil (Datum nur als „heute ist …“ am Ende der ersten User-Nachricht).
* Fehler: typed SDK-Exceptions (`RateLimitException`, `AuthenticationException`/401 →
  „Key ungültig“, `PermissionDeniedException`, Netzwerk) gezielt behandeln.
* **Erst ab Android 8.0 (API 26):** Das SDK (Jackson 2.19) nutzt Java-Features, die es unter
  API 26 nicht gibt (getestet: auf API 24 `NoClassDefFoundError: java/lang/BootstrapMethodError`).
  Darum wird **keine** `com.anthropic.*`-Klasse angefasst, solange `Build.VERSION.SDK_INT < 26`
  (Zugriff nur über eine Fassade, die vorher prüft); dort sind die KI-Funktionen ausgeblendet,
  der Rest der App bleibt voll nutzbar. Auf API 36 verifiziert (401 mit Platzhalter-Key).

## 1. „Frag nach“ (Chat)

* Einstieg: im Artikel-Leser ein Glas-Button „✨ Frag nach“; auf „Heute“ ein Eintrag
  „✨ Frag dein Briefing“.
* Kontext: Artikel (Schlagzeile, Lead, Volltext, Quellen, Datum) bzw. das ganze heutige
  Briefing (Schlagzeilen + Leads). Rolle: hilfsbereiter, sachlicher Nachrichten-Erklärer auf
  Deutsch (du), erklärt Hintergründe, ordnet ein, kennzeichnet Unsicheres, nutzt die
  Websuche für Aktuelles und nennt dann Quellen (Titel + Link). Keine Meinungsmache, keine
  Anlage-/Rechts-/Medizinberatung über Allgemeinwissen hinaus.
* UI: Glas-Sheet bzw. Vollbild-Chat; vorgeschlagene Fragen als Chips („Erklär mir den
  Hintergrund“, „Was bedeutet das für mich?“, „Was sagen andere Quellen?“, „Wer ist …?“);
  Antworten streamen Wort für Wort; einfache Formatierung (Absätze, **fett**, Listen); Quellen
  der Websuche als antippbare Chips; „Suche im Netz …“-Statuszeile während Server-Tools laufen;
  Kopieren/Teilen einer Antwort; Verlauf pro Artikel lokal gespeichert.
* Wer den Chat verlässt (Zurück), bricht eine laufende Antwort ab – das bisher Gestreamte bleibt
  stehen. Eine Frage ganz am Ende ohne Antwort (früh gestoppt, App mittendrin geschlossen) zeigt
  „Nochmal fragen“.

## 2. „⚡ Live“ (brandaktuell)

* In der Chip-Reihe des Feeds ein Chip **„⚡ Live“**. Antippen zeigt **nur brandaktuelle
  Meldungen** (veröffentlicht in den letzten 6 Stunden, neueste zuerst) aus allen vorhandenen
  Quellen (Morgen-Briefing + bisherige Live-Ergebnisse). Was eine Live-Recherche gefunden hat,
  bleibt 6 Stunden nach der Recherche sichtbar, auch wenn die Meldung selbst etwas älter ist
  (die Recherche schaut bis zum Morgen-Briefing zurück). Ein Glas-Button „Jetzt aktualisieren“
  startet eine **Live-Recherche** (sonst automatisch, wenn die letzte älter als 30 Min. ist und
  der Nutzer es in den Einstellungen erlaubt hat).
* Live-Recherche: Claude mit Websuche/Web-Fetch sucht für die sichtbaren Themen (bzw. das
  gewählte Thema) die wichtigsten Meldungen **seit dem Stand des Morgen-Briefings**, prüft sie
  an ≥ 2 Quellen und schreibt **eigene vollständige Artikel** (Regeln wie die Routine:
  docs/brief-format.md, Schema 2, eigene Worte, max. ein kurzes Zitat). Ausgabe über ein
  Client-Tool `publish_articles` (strict JSON-Schema = Schema-2-`NewsItem` + `topicId`), das die
  App entgegennimmt und als Tool-Ergebnis „ok“ quittiert.
* Fortschritt live anzeigen (Suchbegriffe/gelesene Seiten aus den Server-Tool-Blöcken),
  abbrechbar. Ergebnisse werden lokal gespeichert (`store/live/YYYY-MM-DD-HHmm.json`), im Feed
  mit „⚡ neu · vor 12 Min.“ markiert und im Leser nach den Artikeln des Briefings erreichbar
  (Wischen). Verlauf und Tagesdetail zeigen nur das Morgen-Briefing.

## Vorlesen („Zuhören“)

* `android.speech.tts.TextToSpeech`, Locale.GERMAN; liest Schlagzeile, Lead und Text in
  Abschnitten (< `getMaxSpeechInputLength()`), schwebender Glas-Mini-Player mit Titel,
  Pause/Weiter, Stopp; stoppt beim Verlassen; Audio-Fokus beachten. Funktioniert ohne Internet
  und ohne API-Key.
