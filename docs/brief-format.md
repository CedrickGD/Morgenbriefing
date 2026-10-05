# Brief-Format (Branch `briefs`)

Dieses Dokument ist der Vertrag zwischen der Cloud-Routine (`routine/`) und der App
(`data/model/BriefModels.kt`, `data/model/Topics.kt`). Der Validator
[`routine/validate-brief.mjs`](../routine/validate-brief.mjs) setzt genau diese Regeln um.

Die Cloud-Routine schreibt jeden Morgen auf den Branch **`briefs`** (verwaister Branch, nur Daten):

```
briefs/2026-10-06.json   # das Briefing des Tages
latest.json              # Kopie des neuesten Briefings
index.json               # Übersicht, neueste zuerst (max. 400 Einträge)
```

Es gibt zwei Schemata; die App liest beide:

| `schema` | Inhalt | seit |
|---|---|---|
| `1` | Schlagzeile, Zusammenfassung, Link – 3–4 Meldungen in drei Rubriken | erste App-Version |
| `2` | **vollständige eigene Artikel** mit Quellen, Top-/Mehr-Stufen, freien Bildern, zehn Rubriken | aktuell |

Neue Briefings sind immer Schema 2. Unbekannte Felder ignoriert die App; `schema` wird nur bei
inkompatiblen Änderungen erhöht.

## Rechtlicher Rahmen

* Wir **kopieren keine Artikel und keine Pressefotos.** Jeder Text ist ein eigener Artikel der
  Routine, in eigenen Worten aus mehreren Quellen geschrieben (nur Fakten), mit höchstens einem
  kurzen, zugeordneten Zitat (unter 15 Wörtern).
* Bilder stammen ausschließlich von **Wikimedia Commons** unter freier Lizenz (CC0, gemeinfrei,
  CC BY, CC BY-SA) und werden mit Urheber, Lizenz und Link zur Dateiseite angezeigt. Gibt es kein
  passendes freies Bild, bleibt `image` leer und die App zeichnet ein eigenes Titelbild.

## Briefing (`briefs/YYYY-MM-DD.json`, `latest.json`) – Schema 2

```json
{
  "schema": 2,
  "date": "2026-10-06",
  "generatedAt": "2026-10-06T04:41:07+02:00",
  "teaser": "Ein Satz, der den Nachrichtentag zusammenfasst.",
  "sections": [
    {
      "id": "region",
      "title": "Leipzig & Region",
      "emoji": "📍",
      "items": [
        {
          "id": "region-covenant-marschflugkoerper-leipzig",
          "tier": "top",
          "headline": "US-Firma baut in Leipzig Marschflugkörper",
          "summary": "Der Rüstungshersteller Covenant hat im Leipziger Norden einen Standort eröffnet. Ab 2027 sollen dort Marschflugkörper entstehen.",
          "body": "Das US-Rüstungsunternehmen Covenant hat am Montag …\n\n## 1000 Stück im ersten Jahr\n\nAnthem ist eine Langstreckenwaffe …\n\n- erster Punkt\n- zweiter Punkt",
          "source": "t-online (dpa)",
          "url": "https://www.t-online.de/…",
          "sources": [
            { "name": "t-online (dpa)", "url": "https://www.t-online.de/…" },
            { "name": "Leipziger Zeitung", "url": "https://www.l-iz.de/…" }
          ],
          "publishedAt": "2026-10-05T17:34:00+02:00",
          "importance": 7,
          "readingMinutes": 2,
          "image": {
            "url": "https://upload.wikimedia.org/wikipedia/commons/thumb/…/1280px-….jpg",
            "credit": "Name wie auf Commons",
            "license": "CC BY-SA 4.0",
            "sourceUrl": "https://commons.wikimedia.org/wiki/File:….jpg",
            "alt": "Kurze deutsche Bildbeschreibung"
          }
        }
      ]
    }
  ]
}
```

Ein vollständiges, echtes Beispiel ist das Demo-Briefing der App:
[`app/src/main/assets/sample-brief.json`](../app/src/main/assets/sample-brief.json).

### Briefing

| Feld | Pflicht | Regel |
|---|---|---|
| `schema` | ja | `2` |
| `date` | ja | Berliner Datum des Morgens, `YYYY-MM-DD`, echtes Kalenderdatum |
| `generatedAt` | ja | ISO-8601 **mit** Offset; muss auf `date` fallen |
| `teaser` | ja | Text, höchstens 220 Zeichen (leer nur als Hinweis erlaubt) |
| `sections` | ja | mindestens eine Rubrik mit Artikeln; Reihenfolge wie im Katalog |

### Rubriken (`sections[]`) und Katalog

| `id` | `title` | `emoji` |
|---|---|---|
| `ki` | KI & Tech | 🤖 |
| `wirtschaft` | Wirtschaft & Märkte | 📈 |
| `politik` | Politik | 🏛️ |
| `welt` | Welt | 🌍 |
| `region` | Leipzig & Region | 📍 |
| `sport` | Sport | ⚽ |
| `wissenschaft` | Wissenschaft & Raumfahrt | 🔭 |
| `gesundheit` | Gesundheit | 🩺 |
| `klima` | Klima & Umwelt | 🌱 |
| `entertainment` | Games & Entertainment | 🎮 |

* `id` ist eindeutig im Briefing. Ids außerhalb des Katalogs sind erlaubt (Hinweis); die App
  zeigt sie neutral an. Abweichende `title`/`emoji` bei Katalog-Ids sind ein Hinweis.
* Rubriken ohne Artikel werden **weggelassen** (eine leere Rubrik ist ein Fehler).
* `items`: zuerst **1–3** Artikel mit `tier: "top"` (immer sichtbar), danach **0–5** mit
  `tier: "more"` (hinter „+ Mehr“), jeweils wichtigste zuerst.
* Der Leser kann Rubriken in der App sortieren und ausblenden; die Reihenfolge im Briefing ist
  nur der Ausgangspunkt.

### Artikel (`items[]`)

| Feld | Pflicht | Regel |
|---|---|---|
| `id` | ja | Slug aus Kleinbuchstaben, Ziffern, Bindestrichen (`region-covenant-leipzig`), höchstens 80 Zeichen, **eindeutig im ganzen Briefing**; Konvention: `<rubrik>-<slug>` |
| `tier` | ja | `"top"` oder `"more"` |
| `headline` | ja | höchstens 120 Zeichen; mehr als 10 Wörter ist ein Hinweis |
| `summary` | ja | Vorspann, 1–2 Sätze (mehr ist ein Hinweis), höchstens 300 Zeichen; steht auf Karten und in der Benachrichtigung |
| `body` | ja | der vollständige Artikel, siehe unten; `top` 250–700 Wörter, `more` 120–400 Wörter (Ziel der Routine: 300–550 bzw. 150–300) |
| `source` | ja | Name der Hauptquelle, z. B. `Handelsblatt`, `ZEIT (dpa)` |
| `url` | ja | https-Adresse der Hauptquelle; muss eine der `sources` sein; im Briefing nur einmal |
| `sources` | ja | alle verwendeten Quellen als `{ "name", "url" }` (https); `top` mindestens **2** verschiedene, `more` mindestens **1** |
| `publishedAt` | ja* | ISO-8601 mit Offset; höchstens 2 h nach `generatedAt`. *Pflicht bei `--max-age-hours`: dann höchstens so viele Stunden vor `generatedAt` (Routine: 36) |
| `importance` | ja | ganze Zahl 1–10: Relevanz für jemanden in Sachsen/Deutschland an diesem Morgen |
| `readingMinutes` | ja | ganze Zahl ≥ 1 = Wörter des `body` / 200, gerundet (weicht sie um mehr als 1 ab: Hinweis) |
| `image` | nein | `null`/fehlend oder ein Bild-Objekt, siehe unten |

#### `body` – Textformat

* Reiner Text. Blöcke werden durch eine **Leerzeile** getrennt (`\n\n`).
* Ein Block ist ein Absatz, eine Zwischenüberschrift `## Titel` (eine Zeile, eigener Block)
  oder eine Aufzählung aus Zeilen, die mit `- ` beginnen.
* Verboten (Fehler): HTML, Markdown-Links und -Bilder, URLs (`https://…`, `www.…`), andere
  Überschriften als `## `, wörtliche Zitate ab 15 Wörtern.
* Hinweise: Zeilenumbrüche mitten im Absatz, `*`/`+`/`•` als Aufzählungszeichen, `**Fett**`,
  HTML-Entitäten, mehr als ein längeres Zitat, Absätze über 200 Wörter, weniger als 3 (top)
  bzw. 2 (more) Absätze.

#### `image` – nur freie Commons-Bilder

| Feld | Pflicht | Regel |
|---|---|---|
| `url` | ja | beginnt mit `https://upload.wikimedia.org/` (Vorschaubild, ≈1280 px breit) |
| `license` | ja | `CC0`, `Public domain`, `PD-…`, `CC BY x.y` oder `CC BY-SA x.y`, optional mit Länderkürzel (`CC BY-SA 3.0 de`), Groß-/Kleinschreibung egal. NC-/ND-Lizenzen, GFDL allein usw. sind nicht erlaubt |
| `credit` | ja, außer bei CC0/gemeinfrei | Urheber als reiner Text wie auf Commons, ohne HTML, höchstens 200 Zeichen |
| `sourceUrl` | ja | Dateiseite auf `https://commons.wikimedia.org/…` |
| `alt` | empfohlen | kurze deutsche Bildbeschreibung (fehlt sie: Hinweis) |

Kandidaten liefert `node routine/find-image.mjs "<Suchbegriffe>"`; ob ein Bild wirklich passt,
entscheidet die Redaktion (Ort, Gebäude, Person im offiziellen Porträt, Gegenstand, eindeutige
Symbolszene – nie Logos von Medien). Dasselbe Bild zweimal im Briefing ist ein Hinweis.

## Rubrik-Datei der Routine (`/tmp/sections/<id>.json`)

Jeder Rubrik-Agent schreibt genau eine Rubrik `{ "id", "title", "emoji", "items" }` mit den
Artikel-Regeln von Schema 2. Prüfen mit

```bash
node routine/validate-brief.mjs --section /tmp/sections/ki.json --max-age-hours 36
```

Ohne `generatedAt` gilt die Frische-Regel relativ zur aktuellen Uhrzeit (oder `--now <ISO>`).
Passt der Dateiname nicht zur `id`, gibt es einen Hinweis. Zusammengesetzt wird mit

```bash
node routine/assemble-brief.mjs --sections /tmp/sections --teaser "…" --out /tmp/brief.json --max-age-hours 36
```

Das Skript ordnet die Rubriken nach dem Katalog, übernimmt Titel und Emoji aus dem Katalog, lässt
leere Rubriken aus und schreibt nur ein gültiges Briefing.

## Schema 1 (ältere Briefings)

```json
{
  "schema": 1,
  "date": "2026-10-05",
  "generatedAt": "2026-10-05T05:07:12+02:00",
  "teaser": "…",
  "sections": [
    { "id": "ki", "title": "KI & Tech", "emoji": "🤖",
      "items": [ { "headline": "…", "summary": "…", "source": "Handelsblatt",
                   "url": "https://…", "publishedAt": "2026-10-05T14:55:00+02:00", "importance": 7 } ] }
  ]
}
```

Regeln: Felder wie oben, aber ohne `id`, `tier`, `body`, `sources`, `readingMinutes`, `image`;
`summary` höchstens 450 Zeichen; 3–4 Meldungen pro Rubrik und die Rubriken `ki`, `wirtschaft`,
`politik` sind Hinweise, keine Fehler.

## `index.json`

```json
{ "updatedAt": "2026-10-06T04:41:07+02:00",
  "days": [ { "date": "2026-10-06", "teaser": "…", "itemCount": 48 } ] }
```

`days` ist nach Datum absteigend sortiert, jedes Datum nur einmal, höchstens 400 Einträge.
`itemCount` zählt **alle** Artikel des Tages (`top` und `more`). `updatedAt` bewegt sich nie
rückwärts.

## Werkzeuge

| Befehl | Zweck |
|---|---|
| `node routine/validate-brief.mjs <datei…> [--max-age-hours 36]` | Briefings (Schema 1/2) und `index.json` prüfen |
| `node routine/validate-brief.mjs --section <datei…> [--max-age-hours 36] [--now <ISO>]` | einzelne Rubrik-Dateien prüfen |
| `node routine/find-image.mjs "<suchbegriffe>" [--limit 5]` | freie Commons-Bilder suchen (JSON) |
| `node routine/assemble-brief.mjs --sections <ordner> --teaser "…" --out <datei>` | Rubriken zu einem Briefing zusammensetzen |
| `node routine/assemble-brief.mjs --overview <briefing oder ordner> [--topic <id>]` | Überschriften anzeigen (z. B. vom Vortag) |
| `node routine/publish-brief.mjs --brief <datei> --data-dir <checkout> [--merge]` | in den Checkout von `briefs` schreiben; `--merge` fügt Rubriken nach `id` in ein vorhandenes Tagesbriefing ein, statt es zu ersetzen |

Alle Skripte: Node ≥ 18, keine Abhängigkeiten, Exit-Code 0 = ok, 1 = Fehler, 2 = falscher Aufruf,
deutsche Meldungen. Mit `--merge` gilt die Frische-Regel nur für das neu hinzukommende Briefing;
Rubriken aus früheren Läufen desselben Tages werden nur strukturell geprüft.
