# Brief-Format (Branch `briefs`)

Die Cloud-Routine schreibt jeden Morgen auf den Branch **`briefs`** (verwaister Branch, nur Daten):

```
briefs/2026-10-06.json   # das Briefing des Tages
latest.json              # Kopie des neuesten Briefings
index.json               # Übersicht, neueste zuerst (max. 400 Einträge)
```

## `briefs/YYYY-MM-DD.json` / `latest.json`

```json
{
  "schema": 1,
  "date": "2026-10-06",
  "generatedAt": "2026-10-06T05:07:12+02:00",
  "teaser": "Ein Satz, der den Nachrichtentag zusammenfasst.",
  "sections": [
    {
      "id": "ki", "title": "KI & Tech", "emoji": "🤖",
      "items": [
        {
          "headline": "Max. 10 Wörter, eigene Worte",
          "summary": "1–2 knackige Sätze: was passiert ist und warum es relevant ist.",
          "source": "Handelsblatt",
          "url": "https://…",
          "publishedAt": "2026-10-05T14:55:00+02:00",
          "importance": 7
        }
      ]
    },
    { "id": "wirtschaft", "title": "Wirtschaft & Märkte", "emoji": "📈", "items": [] },
    { "id": "politik", "title": "Politik", "emoji": "🏛️", "items": [] }
  ]
}
```

Regeln: `date` ist das Berliner Datum des Morgens; `url` muss `https://` sein; 3–4 Meldungen pro
Rubrik; Meldungen höchstens 36 Stunden alt und einzeln verifiziert. Unbekannte Felder werden von
der App ignoriert; `schema` wird nur bei inkompatiblen Änderungen erhöht.

## `index.json`

```json
{ "updatedAt": "2026-10-06T05:07:12+02:00",
  "days": [ { "date": "2026-10-06", "teaser": "…", "itemCount": 10 } ] }
```

Validierung vor jedem Push: `node routine/validate-brief.mjs <datei>`.
