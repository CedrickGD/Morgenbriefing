# KI-Funktionen

Zwei Funktionen laufen direkt aus der App gegen die API eines KI-Anbieters deiner Wahl:

1. **„Frag nach“ – Chat zu Artikeln und zum Briefing**
2. **„⚡ Live“ – brandaktuelle Nachrichten auf Knopfdruck**

Den Key bringst du selbst mit: Er gehört zu deinem eigenen Konto beim Anbieter (bei Claude
unabhängig vom Claude-Abo), wird in *Einstellungen → KI* eingetragen und wie der GitHub-Token mit
dem Android-Keystore verschlüsselt (`TokenCipher`, ein DataStore-Schlüssel pro Anbieter). Ohne Key
sind beide Funktionen sichtbar, erklären sich freundlich und öffnen den Key-Dialog direkt dort,
wo man ist. Das Design mit allen Entscheidungen steht in [AI-PROVIDERS.md](AI-PROVIDERS.md),
die Wire-Formate in [providers/](providers/).

## Anbieter

| Anbieter | Chat „Frag nach“ | Websuche im Chat | ⚡ Live | Android 7 |
|---|---|---|---|---|
| Claude (Anthropic) | ✓ | ✓ | ✓ | ✗ (braucht Android 8) |
| OpenAI | ✓ | ✓ | ✓ | ✓ |
| Google Gemini | ✓ | ✓ mit Abrechnung (Google-Suche) | ✗ (Googles Nutzungsbedingungen) | ✓ |
| xAI Grok | ✓ | ✓ | ✓ | ✓ |
| Perplexity | ✓ | ✓ | ✓ | ✓ |
| OpenRouter | ✓ (auch kostenlos) | ✓ mit Guthaben | ✓ mit Guthaben | ✓ |
| Mistral | ✓ | ✗ | ✗ | ✓ |
| DeepSeek | ✓ | ✗ | ✗ | ✓ |
| Eigener Server (Ollama, LM Studio …) | ✓ | ✗ | ✗ | ✓ |

* **Ein Chat-Anbieter, optional ein eigener Live-Anbieter** („Live über OpenAI“, während der Chat
  z. B. kostenlos über Gemini oder den eigenen Server läuft). Kann der Chat-Anbieter kein Live,
  sagt die App das in einem Satz und bietet „Live-Anbieter wählen“ an.
* Jeder Anbieter hat einen eigenen Key, eine eigene Modellwahl (kuratierte Modelle mit Preis oder
  „Eigenes Modell …“) und eine eigene Key-Prüfung, die so wenig Tokens wie möglich kostet.
* Bestehende Nutzer merken nichts: Der Claude-Key, das Modell und das Limit aus v1.2 werden beim
  ersten Start still in den Anthropic-Platz übernommen (DataStore-Migration, Schema 2).
* **Android 7:** Nur Claude braucht das Anthropic-SDK und damit Android 8. Alle anderen Anbieter
  laufen ab Android 7; ist Claude gewählt, zeigen Chat, Live und Einstellungen „Claude braucht
  Android 8“ und „Anbieter wählen“.

### Google Gemini: kostenlos oder mit Abrechnung

Gemini geht ohne Zahlungsmittel. Im Key-Dialog steht der
kurze Weg (*aistudio.google.com → Get API key → Create API key*, Link auf
[aistudio.google.com/apikey](https://aistudio.google.com/apikey)); Keys mit `AIza…` und die neuen
`AQ.…` nimmt die App beide, ihr Format wird nicht geprüft. **Bekannter Fehler bei Google (Stand
2026-10-10):** viele neue `AQ.…`-Keys lehnt Google auf allen Endpunkten mit 401
`ACCESS_TOKEN_TYPE_UNSUPPORTED` ab, auch wenn AI Studio sie als gültig zeigt; ältere `AIza…`-Keys
laufen weiter. Die App sagt das in zwei Sätzen, nennt beide Auswege (ein älterer `AIza…`-Key oder
OpenRouter kostenlos mit Gemma 4, Googles offenem Modell) und bietet im Key-Dialog und unter der
Fehlerkarte im Chat **„OpenRouter kostenlos einrichten“** an: der OpenRouter-Key-Dialog auf dem Tarif
„Kostenlos“; sobald der Key dort funktioniert, ist OpenRouter der Chat-Anbieter, und „Nochmal senden“
stellt die Frage neu. Pro Key wählst du den **Tarif**:

* **Kostenlos (ohne Abrechnung)** – Standard bei neuen Gemini-Keys. Die App schickt nie die
  Google-Suche mit (kostenlose Keys dürfen sie bei Gemini 3.x nicht nutzen); der Chat antwortet
  aus Artikel und Briefing und sagt ruhig „Ohne Websuche – mit dem kostenlosen Gemini-Tarif nicht
  verfügbar“. Anfragen zählen als 0 $, das Monatslimit bremst sie nie. Pro-Modelle haben keinen
  Gratis-Tarif: Sie stehen als „nur mit Abrechnung“ da und lassen sich nicht wählen. Im
  kostenlosen Tarif darf Google die Anfragen zur Verbesserung seiner Produkte nutzen – das sagt
  der Dialog.
* **Mit Abrechnung (Paid Tier)** – Antworten mit Google-Suche, Preise nach Tabelle; 5.000
  Google-Suchen im Monat sind frei, die App rechnet vorsichtig trotzdem jede mit.
* **Sicherheitsnetz:** Lehnt Gemini die Google-Suche für einen als „Mit Abrechnung“ eingetragenen
  Key ab, beantwortet die App die Frage einmal ohne Suche („Ohne Websuche beantwortet – …“) und
  merkt sich das für die laufende App.
* **Gratiskontingent aufgebraucht** (HTTP 429 mit kostenlosem Key): „Gemini-Gratiskontingent für
  den Moment aufgebraucht – in ein paar Minuten bzw. morgen geht's weiter.“ Wiederholt wird nur,
  wenn Google selbst eine Wartezeit bis 20 s nennt – die App hämmert nie.
* **Search Suggestions:** Unter jeder Antwort mit Google-Suche zeigt die App Googles
  Suchvorschläge unverändert (eigenes HTML/CSS in einer WebView ohne JavaScript, hell/dunkel nach
  dem Look der App, so breit wie die Antwort, auch im Verlauf); ein Tipp öffnet Googles
  Ergebnisseite im Custom Tab. **Kein Live mit Gemini:** Googles Bedingungen verlangen die
  Vorschläge bei jedem Ergebnis und schränken das Speichern ein – Live-Artikel werden aber
  gespeichert und ohne Vorschläge gezeigt.

### OpenRouter: kostenlos mit Gratis-Modellen

Ein OpenRouter-Konto mit Key, aber ohne Zahlungsmittel reicht für die **Gratis-Modelle** (Modelle mit
Preis 0, z. B. Googles Gemma 4). Key-Dialog: *openrouter.ai → Anmelden → Keys → Create Key (kein
Zahlungsmittel nötig)*, Link auf [openrouter.ai/keys](https://openrouter.ai/keys). Pro Key wählst du
den **Tarif**:

* **Kostenlos (Gratis-Modelle)** – Standard bei neuen OpenRouter-Keys. Die Modellwahl zeigt nur die
  Gratis-Modelle, live von OpenRouters öffentlicher Modellliste (`GET /api/v1/models`: nur die
  Gratis-Varianten `…:free` und `openrouter/free` – ein Modell, das nur für eine Aktion 0 kostet,
  kostet nach der Aktion Guthaben –, Preis für Eingabe und Ausgabe 0, nur Text rein und raus,
  Chat-Modelle mit Tool-Unterstützung, nicht abgelaufen), einmal am Tag geladen – auch vom Chat aus im
  Hintergrund, ohne dass eine Frage darauf wartet; offline die zuletzt geladene Liste oder eine kleine
  eingebaute Auswahl. Vorgewählt ist **Gemma 4 31B** (`google/gemma-4-31b-it:free`), wenn OpenRouter
  es anbietet, sonst das erste Modell der Liste; verschwindet ein gewähltes Gratis-Modell (oder läuft
  es ab), nimmt die App den Standard. Keine Websuche (`openrouter:web_search` braucht Guthaben): der
  Chat sagt ruhig „Ohne Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar“; ⚡ Live sagt
  „Live braucht Websuche – mit dem kostenlosen OpenRouter-Tarif nicht verfügbar.“ mit
  „Live-Anbieter wählen“. Anfragen zählen 0 $, das Monatslimit bremst sie nie (meldet OpenRouter doch
  Kosten, zeigt die Nutzung sie). OpenRouters Grenzen: 20 Anfragen pro Minute und **50 pro Tag**
  (1.000 ab 10 $ gekauftem Guthaben). Ist das Tageskontingent weg (HTTP 429 mit
  „free-models-per-day“ oder einem Tageslimit ohne Rest): „Gratis-Kontingent von OpenRouter für heute
  aufgebraucht – morgen geht's weiter.“ (nie automatisch wiederholt; die Karte bietet „Nochmal
  senden“ für nach einem Tarif- oder Anbieterwechsel). Das Minutenlimit und ein ausgelasteter
  Upstream („Provider returned error“) werden kurz abgewartet.
* **Mit Guthaben** – wie bisher: alle Modelle, Websuche, ⚡ Live, Kosten aus `usage.cost`.
* **Key-Prüfung** über `GET /api/v1/key` (kostenlos): sie zeigt, ob das Konto schon Guthaben gekauft
  hat, und die Gratis-Anfragen von heute („✓ … – Gratis-Konto ohne gekauftes Guthaben. Heute noch 37
  von 50 Gratis-Anfragen.“). „Mit Guthaben“ auf einem Konto ohne gekauftes Guthaben wird gespeichert,
  mit dem Hinweis, Guthaben zu kaufen oder „Kostenlos“ zu nehmen.
* **402** (Guthaben aufgebraucht oder im Minus – dann sperrt OpenRouter auch die Gratis-Modelle):
  „Dein Guthaben bei OpenRouter ist aufgebraucht oder im Minus – …“.
* **Bestehende Nutzer:** ein OpenRouter-Key aus v1.3.0/1.3.1 bleibt auf „Mit Guthaben“ (Migration,
  Schema 3); erst ein neu eingerichteter OpenRouter-Key startet auf „Kostenlos“. Wer OpenRouter im
  Live-Picker wählt, während der Key auf „Kostenlos“ steht, landet im Key-Dialog auf „Mit Guthaben“.

## Kosten & Monatslimit

* Preise stehen an einer Stelle im Code (`ai/AiPricing.kt`), pro Anbieter und Modell mit Quelle
  und Stand (AS_OF 2026-10-09; Gemini Flash verdoppelt sich ab 2027-01-01, das ist hinterlegt).
  Nennt der Anbieter die Kosten selbst (OpenRouter `usage.cost`, xAI `cost_in_usd_ticks`,
  Perplexity `cost.total_cost`), gilt dieser Wert. Unbekannte Modelle rechnet die App mit den
  teuersten Preisen des Anbieters und zeigt „Preis teils geschätzt“.
* Die App rechnet jede Anfrage aus der `usage` des Anbieters in Dollar um, summiert pro
  Kalendermonat und zeigt den Stand in den Einstellungen. **Monatslimit** (Standard 5 $, 0 = aus)
  für alle Anbieter zusammen: Ist es erreicht, gehen keine kostenpflichtigen KI-Anfragen mehr
  raus. Der eigene Server und die kostenlosen Tarife von Gemini und OpenRouter zählen als 0 $ und
  laufen immer. Am Monatsersten
  (Europe/Berlin) beginnt die Zählung bei null – auch in einer laufenden App.

## Netz & Sicherheit

* Keys nur in Headern (Gemini `x-goog-api-key`, nie `?key=`), nie geloggt; geloggt werden nur
  Statuscode und Anbieter.
* Unverschlüsseltes `http://` nur zu lokalen Namen (`localhost`, `10.0.2.2`, `*.local`, `*.lan`,
  `*.home.arpa`, `*.fritz.box`, `*.internal`) – für den eigenen Server. Eine IP-Adresse braucht
  `https://`.
* Wiederholt wird nur vor dem ersten gestreamten Ereignis (Netz, 408, 429, 5xx), höchstens zweimal,
  nie bei Key-, Guthaben- oder Kontingent-Fehlern.

## Regeln der Claude API (Anthropic SDK)

* Offizielles Java-SDK `com.anthropic:anthropic-java` über `AnthropicOkHttpClient`;
  `client.messages().createStreaming(params)` für Chat und Live-Recherche.
* **Thinking**: auf Sonnet 5.5 / Opus 5.5 nicht deaktivierbar (`disabled` → 400) – den
  `thinking`-Parameter weglassen; Tiefe über `outputConfig(OutputConfig.builder().effort(...))`:
  Chat `LOW`, Live-Recherche `MEDIUM`.
* **Kein Prefill**, kein erzwungenes `tool_choice` (`any`/`tool` → 400): `auto` + klare
  Anweisung im System-Prompt.
* **Server-Tools**: `WebSearchTool20260209` (maxUses begrenzen: Chat 3, Live 8–12),
  `WebFetchTool20260209` (maxUses 4–6, `max_content_tokens` begrenzen); Haiku 5.5 bekommt die
  Basis-Werkzeuge `web_search_20250305` / `web_fetch_20250910`. Kein separates `code_execution`
  deklarieren. Fehler der Server-Tools kommen als Ergebnis-Block (HTTP 200), nicht als Exception.
* **`stop_reason`** immer prüfen: `pause_turn` → mit der bisherigen Antwort fortsetzen;
  `refusal` → freundliche Meldung (Kategorie aus `stop_details`); `max_tokens` → „Antwort gekürzt“.
* **Prompt-Caching**: System-Prompt + Artikel-/Briefing-Kontext sind stabil und bekommen
  `cacheControl(CacheControlEphemeral)`. Keine Uhrzeit im gecachten Teil (Datum nur als „heute
  ist …“ am Ende der ersten User-Nachricht). Der Live-Prompt und das Schema von
  `publish_articles` sind für Claude byteweise wie in v1.2 (Golden-Tests).
* **Keys mit Identität**: Ein offener (an keinen Workspace gebundener) Key braucht die
  Workspace-ID (`anthropic-workspace-id`, Einstellungen → Key-Dialog → „Erweitert“). Der 400er
  dazu wird erkannt und erklärt; die Key-Prüfung meldet „Workspace-ID fehlt“, statt den Key
  fälschlich als funktionierend zu speichern.
* Fehler: typed SDK-Exceptions gezielt behandeln (401 → „Key abgelehnt“, Guthaben → „Guthaben
  aufgebraucht“, 404 → Modell/Workspace unbekannt, 429/529 → später nochmal).
* **Erst ab Android 8.0 (API 26):** Das SDK (Jackson) nutzt Java-Features, die es unter API 26
  nicht gibt. Darum lädt die App **keine** `com.anthropic.*`-Klasse, solange
  `Build.VERSION.SDK_INT < 26` (nur `ClaudeBackend` berührt das SDK, und nur hinter
  `AiSupport.sdkAvailable`). Alle anderen Anbieter sprechen OkHttp + SSE und laufen überall.

## 1. „Frag nach“ (Chat)

* Einstieg: im Artikel-Leser die Glas-Pill „✨ Frag zu diesem Artikel nach“; auf „Heute“
  „✨ Frag dein Briefing“.
* Kontext: Artikel (Schlagzeile, Lead, Volltext, Quellen, Datum) bzw. das ganze Briefing des
  Tages (Schlagzeilen + Leads). Rolle: hilfsbereiter, sachlicher Nachrichten-Erklärer auf Deutsch
  (du), erklärt Hintergründe, ordnet ein, kennzeichnet Unsicheres. Mit Websuche schaut er nach,
  was seitdem passiert ist, und nennt Quellen; ohne Websuche sagt er offen, dass er dazu nichts
  Aktuelles weiß. Keine Meinungsmache, keine Anlage-/Rechts-/Medizinberatung über
  Allgemeinwissen hinaus.
* UI: Vollbild-Chat; vorgeschlagene Fragen als Chips; Antworten streamen Wort für Wort mit dem
  Namen des Anbieters darüber; einfache Formatierung (Absätze, **fett**, Listen); Quellen als
  antippbare Chips; „Suche im Netz …“-Statuszeile während der Suche; Kopieren/Teilen einer
  Antwort; Verlauf pro Artikel lokal gespeichert (als reiner Text, ein Anbieterwechsel mitten im
  Gespräch geht).
* Wer den Chat verlässt (Zurück), bricht eine laufende Antwort ab – das bisher Gestreamte bleibt
  stehen. Eine Frage ganz am Ende ohne Antwort zeigt „Nochmal fragen“.
* Unter der letzten fehlgeschlagenen Antwort steht „Nochmal versuchen“, wo es von selbst gleich
  wieder gehen kann (Netz, überlastet), und **„Nochmal senden“**, wo erst etwas zu ändern ist (Key,
  Guthaben, Modell, Anbieter): nach der Änderung stellt es dieselbe Frage neu – auch mit einem
  anderen Anbieter. Eine Ablehnung des Modells wird nicht unverändert neu gesendet.

## 2. „⚡ Live“ (brandaktuell)

* In der Chip-Reihe des Feeds ein Chip **„⚡ Live“**. Antippen zeigt **nur brandaktuelle
  Meldungen** (veröffentlicht in den letzten 6 Stunden, neueste zuerst) aus allen vorhandenen
  Quellen (Morgen-Briefing + bisherige Live-Ergebnisse). Was eine Live-Recherche gefunden hat,
  bleibt 6 Stunden nach der Recherche sichtbar. „Jetzt aktualisieren“ startet eine
  **Live-Recherche** (sonst automatisch, wenn die letzte älter als 30 Min. ist und der Nutzer es
  in den Einstellungen erlaubt hat).
* Live-Recherche: der Live-Anbieter sucht mit seiner Websuche für die sichtbaren Themen (bzw. das
  gewählte Thema) die wichtigsten Meldungen **seit dem Stand des Morgen-Briefings**, prüft sie an
  ≥ 2 Quellen und schreibt **eigene vollständige Artikel** (Regeln wie die Routine:
  [brief-format.md](brief-format.md), Schema 2, eigene Worte). Ausgabe über das Client-Tool
  `publish_articles` (striktes JSON-Schema = Schema-2-`NewsItem` + `topicId`; gleicher Name bei
  jedem Anbieter). Die App prüft jeden Aufruf und antwortet „ok“ oder mit einer Fehlerliste, die
  das Modell korrigiert (begrenzte Zahl an Anfragen, eine Erinnerung, wenn nichts veröffentlicht
  wurde).
* Als „geprüft bis jetzt“ zählt nur ein Lauf, der `publish_articles` aufgerufen (auch mit leerer
  Liste) und regulär beendet hat. Abgelehnte, gekürzte oder unfertige Läufe ohne neue Artikel
  hinterlassen nichts und zeigen einen Fehler.
* Fortschritt live anzeigen (Suchbegriffe, gelesene Seiten), abbrechbar. Ergebnisse werden lokal
  gespeichert (`store/live/YYYY-MM-DD-HHmm.json`, mit Anbieter), im Feed mit „⚡ neu · vor 12 Min.“
  markiert und im Leser nach den Artikeln des Briefings erreichbar.

## Vorlesen („Zuhören“)

* `android.speech.tts.TextToSpeech`, Locale.GERMAN; liest Schlagzeile, Lead und Text in
  Abschnitten, schwebender Glas-Mini-Player mit Titel, Pause/Weiter, Stopp; stoppt beim
  Verlassen; Audio-Fokus beachten. Funktioniert ohne Internet und ohne API-Key.

## Entwickler: Mock-Server und Basis-URL

* In Debug-Builds hat *Einstellungen → KI* den Punkt **„Entwickler: Basis-URL“**: Er leitet den
  gewählten Anbieter auf einen Testserver um, z. B. `tools/mock-ai-server.mjs` auf dem Rechner
  (`http://10.0.2.2:8787…` aus dem Emulator). Release-Builds zeigen ihn nicht und ignorieren,
  was gespeichert ist.
* Der Mock spricht alle Wire-Formate, streamt realistisch und kann gezielt Fehler liefern;
  `MockServerContractTest` prüft die echten Engines dagegen. Details: [AI-PROVIDERS.md §14](AI-PROVIDERS.md#14-qa-with-the-mock-server-toolsmock-ai-servermjs).

## Manuelle QA vor dem Release (mit echten Keys des Nutzers)

- [ ] Je Anbieter mit Websuche ein Chat mit Websuche (Quellen-Chips, Kosten plausibel in den
      Einstellungen) und je Anbieter mit Live ein Live-Lauf (Artikel erscheinen, Abbrechen geht).
- [ ] Gemini **kostenlos**: Chat antwortet ohne Websuche mit der ruhigen Notiz, keine Kosten,
      Pro-Modelle gesperrt; bei aufgebrauchtem Kontingent der Gratiskontingent-Text.
- [ ] Gemini-`AQ.…`-Key, den Google ablehnt: Text zum bekannten Google-Fehler, „OpenRouter kostenlos
      einrichten“ öffnet den OpenRouter-Dialog auf „Kostenlos“, danach ist OpenRouter Chat-Anbieter
      und „Nochmal senden“ beantwortet die Frage mit Gemma 4.
- [ ] OpenRouter **kostenlos** (Konto ohne Zahlungsmittel): Key-Prüfung zeigt „Gratis-Konto …
      Heute noch … von 50 Gratis-Anfragen“, die Modellwahl zeigt die Gratis-Modelle mit Gemma 4 31B
      vorgewählt, der Chat antwortet ohne Websuche und ohne Kosten, Live sagt „Live-Anbieter wählen“;
      nach 50 Anfragen am Tag der Gratis-Kontingent-Text mit „Nochmal senden“. Welchen Fehlertext
      OpenRouter dabei wirklich schickt (`free-models-per-day`?) und wie ein ausgelasteter Upstream
      aussieht (`metadata.raw`?), in [providers/openai-compatible.md](providers/openai-compatible.md) §7.10 nachtragen.
      Die Modellwahl zeigt kein Modell ohne `:free` (außer „Zufälliges Gratis-Modell“).
- [ ] Gemini **mit Abrechnung**: Search Suggestions unter der Antwort, hell und dunkel, auch nach
      App-Neustart aus dem Verlauf; ein Chip öffnet Googles Ergebnisseite im Custom Tab.
- [ ] Gemini-Key ohne Abrechnung, aber als „Mit Abrechnung“ eingetragen: Antwort mit „Ohne
      Websuche beantwortet …“ (Sicherheitsnetz) – und welchen Fehler Google dabei wirklich
      liefert, in [providers/gemini.md](providers/gemini.md) §16 nachtragen.
- [ ] Live mit Gemini: ein Satz + „Live-Anbieter wählen“; separater Live-Anbieter funktioniert.
- [ ] Anthropic: offener Identity-Key ohne/mit Workspace-ID (Hinweis, dann Erfolg).
- [ ] Eigener Server über `http://<name>.fritz.box:11434/v1` (Modelle laden, Chat) und eine IP
      mit `http://` (blockiert mit Hinweis).
- [ ] Android-7-Emulator: OpenAI- oder Gemini-Chat läuft, Claude zeigt „braucht Android 8“.
- [ ] Release-Build mit R8 (`assembleRelease`), Konsolen-Links im Key-Dialog öffnen sich
      (OpenAI, Perplexity, DeepSeek einmal im Browser prüfen, AI-PROVIDERS.md §15).
