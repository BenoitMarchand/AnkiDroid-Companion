package com.ankidroid.companion

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ankidroid.companion.ui.CurrentCard
import com.ankidroid.companion.ui.DeckItem
import com.ankidroid.companion.ui.MainScreen
import com.ankidroid.companion.ui.MainScreenActions
import com.ankidroid.companion.ui.ScreenState
import com.ankidroid.companion.ui.SetupStatus
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen's deck tree, with made-up decks (no AnkiDroid needed). */
@RunWith(AndroidJUnit4::class)
class DeckTreeUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun selectedParentDeck_graysOutItsSubdecks() {
        val decks = listOf(
            DeckItem(1, "Languages", 1, 2, 3),
            DeckItem(2, "Languages::Spanish", 1, 2, 3),
            DeckItem(3, "Music", 0, 0, 0),
        )
        compose.setContent {
            AnkiNotificationsTheme {
                MainScreen(
                    state = ScreenState.Ready,
                    decks = decks,
                    selectedDeckIds = listOf(1L),
                    currentCard = CurrentCard.NotStarted,
                    isStarting = false,
                    lockOnlyWallpaper = true,
                    setup = SetupStatus(),
                    severalInARow = false,
                    actions = MainScreenActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}),
                )
            }
        }

        compose.onNode(hasScrollToKeyAction()).performScrollToKey("tree")
        compose.onAllNodesWithContentDescription(context.getString(R.string.expand)).onFirst().performClick()

        compose.onNode(hasText(context.getString(R.string.included_with_parent, "Languages"))).assertExists()
        // Only the subdeck's checkbox is disabled: it is already reviewed through its parent.
        compose.onAllNodes(isToggleable() and isNotEnabled()).assertCountEquals(1)
    }
}
