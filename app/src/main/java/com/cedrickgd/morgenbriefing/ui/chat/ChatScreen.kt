package com.cedrickgd.morgenbriefing.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Frag nach" (docs/AI.md §1): chat about the article [articleKey] of [date], or about the whole
 * brief of [date] when [articleKey] is null. Full screen over the pager, edge-to-edge.
 */
@Composable
fun ChatScreen(date: String, articleKey: String?, onBack: () -> Unit, modifier: Modifier = Modifier) { TODO("agent ai") }
