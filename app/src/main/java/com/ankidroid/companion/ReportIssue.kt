package com.ankidroid.companion

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import com.ichi2.anki.api.AddContentApi

/** "Report an issue" in Settings: opens a GitHub issue prefilled with the phone, Android and app versions. */
object ReportIssue {
    /** The repository issues go to (`owner/name`), set at build time; empty when the build couldn't find it. */
    fun repository(context: Context): String = context.getString(R.string.issues_repository)

    fun isAvailable(context: Context): Boolean = currentReport(context).url(repository(context)) != null

    fun open(context: Context) {
        val url = currentReport(context).url(repository(context)) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.report_issue_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun currentReport(context: Context) = IssueReport(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = versionName(context, context.packageName).orEmpty(),
        ankiDroidVersion = AddContentApi.getAnkiDroidPackageName(context)?.let { versionName(context, it) }
            ?: context.getString(R.string.report_issue_ankidroid_missing),
    )

    private fun versionName(context: Context, packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
