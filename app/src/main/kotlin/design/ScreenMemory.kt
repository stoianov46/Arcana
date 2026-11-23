package com.arcana.brain.app.design

import com.idea.ink.ui.*
import com.idea.ink.input.*
import com.idea.ink.theme.*

import androidx.compose.runtime.Composable
import com.arcana.brain.app.AppContext
import com.idea.ink.ui.rememberKept as rememberKeptIn

/** [com.idea.ink.ui.rememberKept] for the app's own [AppContext.screenMemory]: the screen's state holder for [key], the same object on every visit of the screen. */
@Composable
fun <T : Any> rememberKept(app: AppContext, key: String, init: () -> T): T = rememberKeptIn(app.screenMemory, key, init)
