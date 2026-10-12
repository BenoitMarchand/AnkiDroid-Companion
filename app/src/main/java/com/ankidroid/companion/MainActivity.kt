package com.ankidroid.companion

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ankidroid.companion.ui.CurrentCard
import com.ankidroid.companion.ui.DeckItem
import com.ankidroid.companion.ui.MainScreen
import com.ankidroid.companion.ui.MainScreenActions
import com.ankidroid.companion.ui.ScreenState
import com.ankidroid.companion.ui.SetupStatus
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_WALLPAPER_SETTINGS = "android.settings.WALLPAPER_SETTINGS"
private const val DECK_CHANGE_DEBOUNCE_MS = 800L

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf<ScreenState>(ScreenState.Loading)
    private val decks = mutableStateListOf<DeckItem>()
    private val selectedDeckIds = mutableStateListOf<Long>()
    private var currentCard by mutableStateOf<CurrentCard>(CurrentCard.NotStarted)
    private var setupStatus by mutableStateOf(SetupStatus())
    private var severalInARow by mutableStateOf(false)

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.createChannel(this)
        if (DeckSelection.load(this).isNotEmpty()) {
            // Reviewing is started: make sure the periodic check uses the current interval.
            PeriodicWorker.schedule(applicationContext)
        }
        requestMissingPermissions()

        val actions = MainScreenActions(
            onToggleDeck = ::toggleDeck,
            onMoveDeck = ::moveDeck,
            onGrantPermission = ::requestMissingPermissions,
            onRetry = ::refresh,
            onOpenWallpaperSettings = ::openWallpaperSettings,
            onOpenWallpaperStyle = { startActivity(Intent(this, WallpaperSettingsActivity::class.java)) },
            onOpenNotificationSettings = ::openNotificationSettings,
            onOpenBatterySettings = ::openBatterySettings,
            onSetSeveralInARow = {
                ReviewSettings.setSeveralInARow(this, it)
                severalInARow = it
            },
        )
        setContent {
            AnkiNotificationsTheme {
                MainScreen(
                    state = screenState,
                    decks = decks,
                    selectedDeckIds = selectedDeckIds,
                    currentCard = currentCard,
                    lockOnlyWallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    setup = setupStatus,
                    severalInARow = severalInARow,
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Due counts and the current card change while reviewing from the notification.
        refresh()
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (AnkiDroidHelper.isApiAvailable(this@MainActivity) && !AnkiDroidHelper(this@MainActivity).isPermissionGranted) {
                add(AddContentApi.READ_WRITE_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        setupStatus = readSetupStatus()
        severalInARow = ReviewSettings.severalInARow(this)
        if (!AnkiDroidHelper.isApiAvailable(this)) {
            screenState = ScreenState.ApiUnavailable
            return
        }
        if (!AnkiDroidHelper(this).isPermissionGranted) {
            screenState = ScreenState.PermissionMissing
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // The card may have been reviewed or changed in AnkiDroid since it was shown.
                CardStateController.syncWithAnkiDroid(applicationContext)
                loadDecks()
            }
            decks.clear()
            decks.addAll(loaded.decks)
            selectedDeckIds.clear()
            selectedDeckIds.addAll(loaded.selectedDeckIds)
            currentCard = loaded.currentCard
            screenState = ScreenState.Ready
        }
    }

    private class LoadedData(val decks: List<DeckItem>, val selectedDeckIds: List<Long>, val currentCard: CurrentCard)

    private fun loadDecks(): LoadedData {
        val anki = AnkiDroidHelper(this)
        val names = anki.deckNames ?: emptyMap()
        val counts = anki.getDeckCounts()
        val decks = names.map { (id, name) ->
            val count = counts[id]
            DeckItem(id, name, newCount = count?.get(2), learnCount = count?.get(0), reviewCount = count?.get(1))
        }.sortedBy { it.name.lowercase() }
        val selected = DeckSelection.load(this).filter { it in names }
        return LoadedData(decks, selected, readCurrentCard(anki))
    }

    private fun readCurrentCard(anki: AnkiDroidHelper): CurrentCard {
        val state = anki.storedState ?: return CurrentCard.NotStarted
        if (!state.hasCard()) {
            return CurrentCard.NoneDue
        }
        val question = CardText.clean(state.question)
        // A card stored by an older version may have nothing left to show (e.g. a sound-only question).
        if (question.isEmpty() || CardText.hasSound(state.question)) {
            return CurrentCard.NotStarted
        }
        return CurrentCard.Showing(question, anki.getDeckName(state.deckId) ?: "")
    }

    private fun toggleDeck(deckId: Long) {
        if (!selectedDeckIds.remove(deckId)) {
            selectedDeckIds.add(deckId)
        }
        onDeckSelectionChanged()
    }

    private fun moveDeck(deckId: Long, offset: Int) {
        val from = selectedDeckIds.indexOf(deckId)
        val to = from + offset
        if (from == -1 || to !in selectedDeckIds.indices) return
        selectedDeckIds.add(to, selectedDeckIds.removeAt(from))
        onDeckSelectionChanged()
    }

    private var deckChangeSync: Job? = null

    /**
     * Saves the selection and, once the user stops changing it, shows the new first card if it differs. Reviewing
     * starts with the first selected deck, and stops when none is left.
     */
    private fun onDeckSelectionChanged() {
        DeckSelection.save(this, selectedDeckIds.toList())
        if (selectedDeckIds.isNotEmpty()) PeriodicWorker.schedule(applicationContext)
        deckChangeSync?.cancel()
        deckChangeSync = lifecycleScope.launch {
            delay(DECK_CHANGE_DEBOUNCE_MS)
            val action = withContext(Dispatchers.IO) { CardStateController.syncWithAnkiDroid(applicationContext) }
            if (action != CardSync.Action.NONE) refresh()
        }
    }

    private fun readSetupStatus(): SetupStatus {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(Notifications.CHANNEL_ID)
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE

        val wallpaperActive = CardWallpaperService.isActive(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return SetupStatus(
            notificationsEnabled = notificationsEnabled,
            wallpaperActive = wallpaperActive,
            batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun openBatterySettings() {
        // The list of apps and their battery optimization; asking directly needs a permission Play restricts.
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openWallpaperSettings() {
        // Only the system wallpaper app can apply a live wallpaper to the lock screen alone, so the user picks it
        // there. ACTION_CHANGE_LIVE_WALLPAPER would apply it to both screens.
        try {
            // Public action, but its constant is hidden from the SDK. Opens "Wallpaper & style" on Pixel.
            startActivity(Intent(ACTION_WALLPAPER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.set_lock_wallpaper)))
        }
    }
}
