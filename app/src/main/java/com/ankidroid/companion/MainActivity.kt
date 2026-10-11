package com.ankidroid.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.ankidroid.companion.ui.CurrentCard
import com.ankidroid.companion.ui.DeckItem
import com.ankidroid.companion.ui.MainScreen
import com.ankidroid.companion.ui.MainScreenActions
import com.ankidroid.companion.ui.ScreenState
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val ACTION_WALLPAPER_SETTINGS = "android.settings.WALLPAPER_SETTINGS"

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf<ScreenState>(ScreenState.Loading)
    private val decks = mutableStateListOf<DeckItem>()
    private val selectedDeckIds = mutableStateListOf<Long>()
    private var currentCard by mutableStateOf<CurrentCard>(CurrentCard.NotStarted)
    private var isStarting by mutableStateOf(false)

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
        createNotificationChannel()
        requestMissingPermissions()

        val actions = MainScreenActions(
            onToggleDeck = ::toggleDeck,
            onMoveDeck = ::moveDeck,
            onStartReviewing = ::startReviewing,
            onGrantPermission = ::requestMissingPermissions,
            onRetry = ::refresh,
            onOpenWallpaperSettings = ::openWallpaperSettings,
        )
        setContent {
            AnkiNotificationsTheme {
                MainScreen(
                    state = screenState,
                    decks = decks,
                    selectedDeckIds = selectedDeckIds,
                    currentCard = currentCard,
                    isStarting = isStarting,
                    lockOnlyWallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
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

    private fun createNotificationChannel() {
        val name = "AnkiNotificationChannel"
        val descriptionText = "Channel for anki notifications"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("channel_id", name, importance).apply {
            description = descriptionText
        }

        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (AnkiDroidHelper.isApiAvailable(this@MainActivity) && !isGranted(AddContentApi.READ_WRITE_PERMISSION)) {
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
        if (!AnkiDroidHelper.isApiAvailable(this)) {
            screenState = ScreenState.ApiUnavailable
            return
        }
        if (!isGranted(AddContentApi.READ_WRITE_PERMISSION)) {
            screenState = ScreenState.PermissionMissing
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadDecks() }
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
        val names = anki.api.deckList ?: emptyMap()
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
        if (state.noteID == -1L && state.cardOrd == -1) {
            return CurrentCard.NoneDue
        }
        return CurrentCard.Showing(CardText.clean(state.question), anki.api.getDeckName(state.deckId) ?: "")
    }

    private fun toggleDeck(deckId: Long) {
        if (!selectedDeckIds.remove(deckId)) {
            selectedDeckIds.add(deckId)
        }
        DeckSelection.save(this, selectedDeckIds.toList())
    }

    private fun moveDeck(deckId: Long, offset: Int) {
        val from = selectedDeckIds.indexOf(deckId)
        val to = from + offset
        if (from == -1 || to !in selectedDeckIds.indices) return
        selectedDeckIds.add(to, selectedDeckIds.removeAt(from))
        DeckSelection.save(this, selectedDeckIds.toList())
    }

    private fun startReviewing() {
        isStarting = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CardStateController.showNextCard(applicationContext, silent = false, notifyIfNone = true)
            }
            isStarting = false
            // Start the periodic worker when the first card is assigned.
            startPeriodicWorker()
            refresh()
        }
    }

    private fun startPeriodicWorker() {
        Log.i("BackgroundService", "startBackgroundService called from MainActivity")
        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            PeriodicWorker::class.java,
            8, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WORKER_ANKI",
            ExistingPeriodicWorkPolicy.REPLACE,  // Use REPLACE to ensure only one instance is scheduled
            periodicWorkRequest
        )
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
