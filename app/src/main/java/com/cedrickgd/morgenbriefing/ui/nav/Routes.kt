package com.cedrickgd.morgenbriefing.ui.nav

import kotlinx.serialization.Serializable

/**
 * Start destination: the swipeable pages Heute → Einstellungen (docs/DESIGN.md §3).
 * Which page is shown lives in the shell's pager state, not in the route. Articles open in
 * the in-app browser, so nothing is pushed over the pager.
 */
@Serializable
data object MainRoute
