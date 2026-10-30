package com.cedrickgd.morgenbriefing.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.cedrickgd.morgenbriefing.ui.glass.PageSpec

/**
 * The two pages of the main pager, in swipe order (docs/DESIGN.md §3):
 * **Heute** → Einstellungen. [index] is the pager page.
 */
enum class MainPage(val label: String, val icon: ImageVector, val link: String) {
    TODAY("Heute", Icons.Rounded.WbSunny, AppLinks.PAGE_TODAY),
    SETTINGS("Einstellungen", Icons.Rounded.Settings, AppLinks.PAGE_SETTINGS),
    ;

    val index: Int get() = ordinal

    companion object {
        /** The page the app opens on. */
        val Start = TODAY

        /** Specs for the glass page indicator, in pager order. */
        val specs: List<PageSpec> = entries.map { PageSpec(it.label, it.icon) }

        fun ofIndex(index: Int): MainPage = entries.getOrElse(index) { Start }

        /** Maps an [AppLinks.EXTRA_OPEN_PAGE] value; unknown or missing values open [Start]. */
        fun fromLink(value: String?): MainPage = entries.firstOrNull { it.link == value } ?: Start

        /**
         * The page a new intent for the running app asks for. Only an intent that carries
         * [AppLinks.EXTRA_OPEN_PAGE] (the notifications) navigates; without it (the launcher
         * icon) null: the app stays where it is.
         */
        fun requestedBy(value: String?): MainPage? = value?.let(::fromLink)
    }
}
