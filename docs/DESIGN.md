# Morgenbriefing – Designsprache „Morgenglas“

Ziel: Die App soll sich anfühlen wie ein ruhiger, lebendiger Morgenhimmel, über dem Inhalte als
**Glas** schweben – von oben bis ganz unten, auf jeder Seite. Kein flacher Hintergrund, keine
„tote“ Liste. Vorbild ist die Glas-Ästhetik von iOS, aber eigenständig, warm und materialgerecht
für Android.

## 1. Bühne: der Himmel ist immer da

* **AmbientSky** liegt einmal hinter der ganzen App (hinter allen drei Seiten), nicht pro Screen.
  Beim Wischen zwischen den Seiten bleibt der Himmel stehen und verschiebt sich nur leicht
  (Parallaxe ~12 % der Seitenbreite) – die Seiten gleiten darüber.
* Aufbau, von hinten nach vorn:
  1. Grundfarbe = `colorScheme.background`.
  2. Vertikaler Verlauf über die **volle Höhe**: oben die Himmelsfarben des aktuellen Wetters und
     der Tageszeit (Dämmerung vor Sonnenaufgang, Tag, Abend, Nacht), nach unten in einen
     tieferen, leicht akzentgetönten Ton – unten ist es nie einfarbig flach.
  3. 3–4 große, weiche Farbflächen („Aurora“, radiale Verläufe, 60–90 % der Breite), die sich
     in 60–120 s langsam bewegen; Farben aus Himmel + `primaryContainer`/`tertiaryContainer`.
  4. Wetterpartikel (Nebelbänder, Regen, Schnee, Sterne, Sonnenschimmer) in der oberen Hälfte.
* Dunkelmodus: tiefes Nachtblau/Indigo, Aurora gedämpft, Sterne. Hellmodus: luftig, pastellig.
* „Animationen entfernen“ → alles statisch.

## 2. Glas

* Umsetzung mit **Haze** (`dev.chrisbanes.haze`): Der Himmel ist `hazeSource`, Glasflächen sind
  `hazeEffect`. Ab Android 12 echte Unschärfe, darunter ein milchiger Fallback-Tint.
* Stufen (Licht / Dunkel):

  | Stufe | Blur | Tint (surface) | Rauschen | Einsatz |
  |---|---|---|---|---|
  | `Thin` | 20 dp | 38 % / 30 % | 0.05 | Chips, Pills, Indikator |
  | `Regular` | 28 dp | 55 % / 45 % | 0.06 | Karten |
  | `Thick` | 36 dp | 72 % / 62 % | 0.07 | Dialoge, Sheets, Kopfleisten |

* **Kante**: 1 dp Verlaufsrand (oben links Weiß 55 %/22 % → unten rechts Weiß 8 %/4 %),
  dazu ein zarter Innen-Glanz oben (2–3 dp, Weiß 18 %/8 %). Kein harter Schatten; höchstens
  ein sehr weicher Umgebungsschatten (8–16 dp, ≤ 10 % Schwarz) für schwebende Elemente.
* Radien: Karten **28 dp**, Kacheln/Bilder 22 dp, Chips 16 dp, Pills voll rund.
* Text auf Glas muss ≥ 4.5:1 Kontrast haben – lieber stärkerer Tint als blasser Text.

## 3. Navigation ohne Leiste

* Drei Seiten nebeneinander in einem **HorizontalPager**: **Verlauf ← Heute → Einstellungen**.
  Start ist „Heute“ (Mitte). Zurück-Geste auf Verlauf/Einstellungen → sanft zu „Heute“.
* Keine Bottom-Bar. Stattdessen ein **schwebender Glas-Indikator** (Pill, 44 dp hoch, mittig,
  12 dp über der Systemleiste): drei kleine Symbole (Verlauf · Sonne · Zahnrad); eine farbige
  „Flüssigkeits“-Kapsel gleitet mit dem Wischen von Symbol zu Symbol (folgt
  `currentPageOffsetFraction`). Antippen springt zur Seite. Er blendet beim Runterscrollen aus
  und beim Hochscrollen/Stillstand wieder ein – er soll sich nie wie eine Leiste anfühlen.
* Haptik: leichter Tick beim Seitenwechsel.
* Unterseiten (Artikel-Leser, Tagesdetail) öffnen sich **über** dem Pager; Predictive Back.
* Innere horizontale Scroller (Top-Story-Karussell, Leser-Pager) haben Vorrang vor dem
  Seitenwischen.

## 4. Seitenkopf

* Kein `LargeTopAppBar` mehr. Jede Seite beginnt direkt unter der Statusleiste (+12 dp) mit
  * einer kleinen Overline (Label, Versalien, Sperrung) – z. B. „DEIN ARCHIV“, „DEIN MORGEN“,
  * dem Seitentitel in Serif, **displaySmall (36 sp)**, linksbündig.
* Beim Scrollen erscheint oben eine schmale **Glas-Kopfleiste** (Statusleiste + 52 dp) mit dem
  Titel klein (titleLarge) – Inhalt gleitet darunter weich weg.

## 5. Typografie & Rhythmus

* Serif (SemiBold) für Seitentitel, Schlagzeilen, große Zahlen (Temperatur, Uhrzeit).
* Sans für alles andere; Fließtext 16 sp / 1.55, Leser 17 sp / 1.6 (× Textgröße).
* Raster 4 dp; Außenabstand 16 dp, zwischen Karten 14–16 dp, in Karten 20 dp.
* Jede Sektion hat eine Overline + Titel; nie zwei gleich aussehende Blöcke direkt
  untereinander ohne Hierarchie.

## 6. Bewegung

* Federn statt linearer Tweens (`spring(dampingRatio 0.8, stiffness MediumLow)`).
* Inhalte erscheinen gestaffelt (40–60 ms Versatz), Zahlen zählen hoch, Karten heben sich beim
  Drücken minimal (Scale 0.98).
* Seiten im Pager: Nachbarseiten leicht verkleinert (0.94) und abgedunkelt, während man wischt.

## 7. Seiten im Detail

**Heute** – Kopf (Datum, Gruß, Serif-Headline) direkt auf dem Himmel; darunter Glas-Karten:
„Vor der Tür“ (große Temperatur), Warnungen (getönte Glas-Banner), Tageskurve, Anziehen
(Kacheln), dann die News: Teaser-Glas, „Heute wichtig“-Karussell (Bildkarten mit großer
Überschrift im Bild), Themen-Sektionen mit Glas-Kopf (Emoji in Themenfarbe) und „+ Mehr“.
Unten ein ruhiger Ausklang (Stand, Quellen, kleines „Bis morgen ☀️“).

**Verlauf** – Kopf „DEIN ARCHIV / Verlauf“. Eine **Zeitleiste**: dünne vertikale Linie links,
pro Tag ein Punkt; Monatsüberschriften als Glas-Pills; jeder Tag eine Glas-Karte mit großer
Tageszahl (Serif), Wochentag, Teaser (2 Zeilen), Mini-Wetter (Emoji + „9° → 21°“) und Anzahl
Artikel; heute hervorgehoben.

**Einstellungen** – Kopf „DEIN MORGEN / Einstellungen“, dann:
1. **Status-Karte**: großes ✓ „Alles bereit für morgen, 05:30“ oder freundliche Hinweise;
   vier Status-Pills (Benachrichtigungen, Exakte Uhrzeit, Akku, News-Verbindung) grün/amber –
   Antippen behebt.
2. **Morgenbriefing**: riesige Uhrzeit in Serif („05:30“, antippen → Zeitwahl), Schalter,
   „Nächstes Briefing: morgen · in 9 Std.“, Test-Benachrichtigung als Glas-Pill.
3. **Darstellung**: drei Vorschau-Kacheln (Mini-Handy mit Himmel + Glaskarte) für
   System/Hell/Dunkel; Akzentfarben als große Farbperlen mit gleitendem Auswahlring + Name.
4. **Themen**: farbige Themen-Kacheln (Emoji, Name) – antippen an/aus, lange drücken zum
   Sortieren.
5. **Orte & Zeitfenster**: zwei Ortskarten mit Live-Mini-Wetter; Zeitfenster-Slider mit
   Sonnensymbolen.
6. **Lesen**: Textgröße mit Live-Vorschau im Leser-Stil.
7. **News-Quelle**: Verbindungs-Pill („Verbunden ✓ · geprüft 18:52“), Token nie anzeigen.
8. **App**: Version, Update-Karte, Links (Open-Meteo, Quellcode).

## 8. Qualitätsregeln

* Getestet auf 1440×3120 (S26 Ultra) und Android 7; hell/dunkel; Gesten- und 3-Tasten-Navigation.
* Nichts liegt unter Statusleiste oder Gestenbalken, außer bewusst (Himmel).
* Touch-Ziele ≥ 48 dp, alles mit Content-Description, Kontrast geprüft.
* 60 fps beim Scrollen: Hintergründe zeichnen nur im Draw-Pass, keine Recomposition pro Frame.
