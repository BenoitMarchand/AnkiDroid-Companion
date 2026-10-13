package com.ankidroid.companion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ankidroid.companion.R

private const val ACTION_WALLPAPER_SETTINGS = "android.settings.WALLPAPER_SETTINGS"

/** Setting the card wallpaper on the lock screen, shared by the main screen and Settings. */
object WallpaperSetup {
    /** Android 14+ can set a live wallpaper on the lock screen only. */
    val lockOnly: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun openSettings(context: Context) {
        // Only the system wallpaper app can apply a live wallpaper to the lock screen alone, so the user picks it
        // there. ACTION_CHANGE_LIVE_WALLPAPER would apply it to both screens.
        try {
            // Public action, but its constant is hidden from the SDK. Opens "Wallpaper & style" on Pixel.
            context.startActivity(Intent(ACTION_WALLPAPER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), context.getString(R.string.set_lock_wallpaper))
            )
        }
    }
}

/** The steps to set the card wallpaper on the lock screen, then a button to the wallpaper settings. */
@Composable
fun WallpaperInstructionsDialog(lockOnlyWallpaper: Boolean, onDismiss: () -> Unit, onOpenWallpaperSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_lock_wallpaper)) },
        text = {
            Text(
                stringResource(
                    if (lockOnlyWallpaper) R.string.set_lock_wallpaper_instructions
                    else R.string.set_lock_wallpaper_instructions_both_screens
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenWallpaperSettings()
            }) { Text(stringResource(R.string.open_wallpaper_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
