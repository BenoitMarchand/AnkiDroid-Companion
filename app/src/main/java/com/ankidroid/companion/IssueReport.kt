package com.ankidroid.companion

import java.net.URLEncoder

/**
 * The details prefilled in a new GitHub issue. The URL opens the issue form `.github/ISSUE_TEMPLATE/bug_report.yml`,
 * which GitHub fills from the query parameters named after its field ids.
 */
data class IssueReport(
    val device: String,
    val android: String,
    val appVersion: String,
    val ankiDroidVersion: String,
) {
    /** The new issue URL in [repository] (`owner/name`), or null when the build doesn't know the repository. */
    fun url(repository: String): String? {
        if (!REPOSITORY.matches(repository)) return null
        val fields = listOf(
            "template" to TEMPLATE,
            "device" to device,
            "android" to android,
            "app-version" to appVersion,
            "ankidroid-version" to ankiDroidVersion,
        )
        return "https://github.com/$repository/issues/new?" +
            fields.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    companion object {
        const val TEMPLATE = "bug_report.yml"
        private val REPOSITORY = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")

        // URLEncoder writes spaces as "+", which GitHub reads back as spaces.
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
