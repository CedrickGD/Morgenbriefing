package com.cedrickgd.morgenbriefing.ui.shell

/**
 * Intent extras that notifications put on the launch intent so MainActivity can open the right
 * page of the pager (handled in the shell).
 */
object AppLinks {
    const val EXTRA_OPEN_PAGE = "com.cedrickgd.morgenbriefing.OPEN_PAGE"
    const val PAGE_TODAY = "today"
    const val PAGE_SETTINGS = "settings"
}
