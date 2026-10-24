package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import com.arcana.brain.app.design.*

/** The screen for choosing attachments, the same wherever a field carries them. [where] says what the files are for. */
@Composable
fun AttachBrowser(attachments: Attachments, where: String = "your next message") {
    val browser = attachments.browser ?: return
    val actions = browser.actions()
    Stack {
        ScreenHeader(
            "Attach files",
            if (attachments.forModel) "Pictures (PNG, JPEG, WebP), PDFs and text files (JSON, CSV, code, logs…) for $where" else "Any file, to copy into your vault $where",
        )
        VGap()
        Panel("Choose files") { FilePicker(browser, rows = outputRows(fixed = 9, actions = actions)) }
        ActionBar(actions)
    }
}
