package com.ankidroid.companion

import androidx.core.text.HtmlCompat

/** Turns AnkiDroid's card HTML into the plain text shown in the notification and on the lock screen. */
object CardText {
    private val SOUND = Regex("""\[sound:[^\]]*]|\[anki:play:[^\]]*]""")
    private val TYPE_ANSWER = Regex("""\[\[type:[^\]]*]]""")
    private val EXTRA_BLANK_LINES = Regex("\n{3,}")
    private const val OBJECT_REPLACEMENT = '￼'

    /** Whether the HTML plays a sound. Cards whose question has sound are skipped for now. */
    @JvmStatic
    fun hasSound(html: String?): Boolean = html != null && SOUND.containsMatchIn(html)

    @JvmStatic
    fun clean(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        val withoutTags = html.replace(SOUND, "").replace(TYPE_ANSWER, "")
        return HtmlCompat.fromHtml(withoutTags, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace(OBJECT_REPLACEMENT, ' ') // images
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()
    }
}
