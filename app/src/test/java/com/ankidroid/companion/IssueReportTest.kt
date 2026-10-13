package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueReportTest {

    private val report = IssueReport(
        device = "Acme Phone 7",
        android = "14 (API 34)",
        appVersion = "0.3.1",
        ankiDroidVersion = "2.20.1",
    )

    @Test
    fun opensTheIssueFormWithTheFieldsFilledIn() {
        assertEquals(
            "https://github.com/owner/AnkiDroid-Companion/issues/new?template=bug_report.yml" +
                "&device=Acme+Phone+7&android=14+%28API+34%29&app-version=0.3.1&ankidroid-version=2.20.1",
            report.url("owner/AnkiDroid-Companion"),
        )
    }

    @Test
    fun encodesCharactersThatWouldBreakTheQuery() {
        val url = report.copy(device = "A&B=C #1").url("owner/repo")!!
        assertEquals("device=A%26B%3DC+%231", url.split("&")[1])
    }

    @Test
    fun noUrlWithoutAValidRepository() {
        assertNull(report.url(""))
        assertNull(report.url("owner"))
        assertNull(report.url("https://github.com/owner/repo"))
    }
}
