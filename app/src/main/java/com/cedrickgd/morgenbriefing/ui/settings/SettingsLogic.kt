package com.cedrickgd.morgenbriefing.ui.settings

import android.os.Build
import androidx.compose.runtime.Immutable
import com.cedrickgd.morgenbriefing.data.model.AccentId
import com.cedrickgd.morgenbriefing.data.model.BriefErrorKind
import com.cedrickgd.morgenbriefing.data.model.BriefSourceException
import com.cedrickgd.morgenbriefing.data.model.Place
import com.cedrickgd.morgenbriefing.domain.Fmt
import java.io.IOException
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The day window ("Zeitfenster") slider: 6–22 Uhr in full hours, at least 2 h wide. */
internal object DayWindow {
    const val MIN_HOUR = 6
    const val MAX_HOUR = 22
    const val MIN_SPAN = 2

    /** Number of steps between the ends for a Material slider with one stop per hour. */
    const val SLIDER_STEPS = MAX_HOUR - MIN_HOUR - 1

    /**
     * Applies bounds and the minimum span while dragging. When the span gets too small, the
     * thumb the user did not move stays put and the moving one is held back.
     */
    fun constrain(previousStart: Int, previousEnd: Int, start: Int, end: Int): Pair<Int, Int> {
        var newStart = start.coerceIn(MIN_HOUR, MAX_HOUR - MIN_SPAN)
        var newEnd = end.coerceIn(MIN_HOUR + MIN_SPAN, MAX_HOUR)
        if (newEnd - newStart < MIN_SPAN) {
            if (newStart != previousStart) newStart = newEnd - MIN_SPAN else newEnd = newStart + MIN_SPAN
        }
        return newStart to newEnd
    }

    /** Makes any stored pair valid (used before persisting). */
    fun normalize(start: Int, end: Int): Pair<Int, Int> = constrain(start, end, start, end)

    /** "10–18 Uhr" */
    fun label(start: Int, end: Int): String = "$start–$end Uhr"
}

/** "05:30" */
internal fun clockLabel(hour: Int, minute: Int): String =
    String.format(Locale.GERMANY, "%02d:%02d", hour, minute)

/** "Nächstes Briefing: morgen um 05:30" for the next alarm [next] as seen from [now]. */
internal fun nextBriefingLabel(now: ZonedDateTime, next: ZonedDateTime): String {
    val day = when (ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())) {
        0L -> "heute"
        1L -> "morgen"
        2L -> "übermorgen"
        else -> "am ${Fmt.shortDate(next.toLocalDate())}"
    }
    return "Nächstes Briefing: $day um ${clockLabel(next.hour, next.minute)}"
}

/** Accents offered on this device: Material You only where it exists (API 31+). */
internal fun availableAccents(sdkInt: Int = Build.VERSION.SDK_INT): List<AccentId> =
    AccentId.entries.filter { it != AccentId.DYNAMIC || sdkInt >= Build.VERSION_CODES.S }

/** The accent the theme really uses: DYNAMIC falls back to MORGENROT below API 31. */
internal fun effectiveAccent(accent: AccentId, sdkInt: Int = Build.VERSION.SDK_INT): AccentId =
    if (accent == AccentId.DYNAMIC && sdkInt < Build.VERSION_CODES.S) AccentId.MORGENROT else accent

/** "Bad Düben · Sachsen, Deutschland" */
internal fun Place.displayLabel(): String =
    if (region.isBlank()) name else "$name · $region"

/** Which place the picker edits. */
enum class PlaceTarget(val title: String) {
    HOME("Ort für „Vor der Tür“"),
    DAY("Ort für „Tagsüber“"),
}

/** Place search inside the picker dialog. */
@Immutable
data class PlaceSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Place> = emptyList(),
    val failed: Boolean = false,
    /** Query the [results] belong to; distinguishes "nothing found" from "not searched yet". */
    val searchedFor: String? = null,
) {
    val queryTooShort: Boolean get() = query.trim().length < PLACE_QUERY_MIN_LENGTH
}

internal const val PLACE_QUERY_MIN_LENGTH = 2

/** Result of "Speichern & testen" in the token dialog. */
@Immutable
sealed interface TokenCheck {
    data object Idle : TokenCheck
    data object Checking : TokenCheck
    data object Success : TokenCheck
    data object SaveFailed : TokenCheck
    data class Failed(val kind: BriefErrorKind) : TokenCheck
}

/** German feedback line for a [TokenCheck]; null while idle. Pure, unit-tested. */
internal fun tokenCheckMessage(check: TokenCheck): String? = when (check) {
    TokenCheck.Idle -> null
    TokenCheck.Checking -> "Prüfe die Verbindung zu GitHub …"
    TokenCheck.Success -> "✓ Verbindung klappt – ab morgen kommen deine News."
    TokenCheck.SaveFailed -> "✗ Der Token konnte nicht gespeichert werden. Versuch's nochmal."
    is TokenCheck.Failed -> when (check.kind) {
        BriefErrorKind.UNAUTHORIZED, BriefErrorKind.NO_TOKEN -> "✗ Token ungültig oder abgelaufen"
        BriefErrorKind.NOT_FOUND -> "✗ Repo nicht gefunden – hat der Token Zugriff auf Morgenbriefing?"
        BriefErrorKind.NETWORK -> "✗ Keine Verbindung"
        BriefErrorKind.PARSE, BriefErrorKind.UNKNOWN -> "✗ GitHub hat unerwartet geantwortet – versuch's gleich nochmal."
    }
}

/** Maps anything thrown by the access check to a [BriefErrorKind]. */
internal fun Throwable.toBriefErrorKind(): BriefErrorKind = when (this) {
    is BriefSourceException -> kind
    is IOException -> BriefErrorKind.NETWORK
    else -> BriefErrorKind.UNKNOWN
}
