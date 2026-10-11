package com.ankidroid.companion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ankidroid.companion.R
import com.ankidroid.companion.ui.theme.LearnCountColor
import com.ankidroid.companion.ui.theme.NewCountColor
import com.ankidroid.companion.ui.theme.ReviewCountColor

sealed interface ScreenState {
    data object Loading : ScreenState
    data object ApiUnavailable : ScreenState
    data object PermissionMissing : ScreenState
    data object Ready : ScreenState
}

sealed interface CurrentCard {
    data object NotStarted : CurrentCard
    data object NoneDue : CurrentCard
    data class Showing(val question: String, val deckName: String) : CurrentCard
}

/** A deck as listed by AnkiDroid. Counts are null when AnkiDroid could not provide them. */
data class DeckItem(
    val id: Long,
    val name: String,
    val newCount: Int?,
    val learnCount: Int?,
    val reviewCount: Int?,
) {
    /** "Languages::Spanish::Verbs" is shown as "Verbs", under "Languages › Spanish". */
    val shortName: String get() = name.substringAfterLast("::")
    val parentPath: String? get() = name.substringBeforeLast("::", "").takeIf { it.isNotEmpty() }?.replace("::", " › ")
}

class MainScreenActions(
    val onToggleDeck: (Long) -> Unit,
    val onMoveDeck: (deckId: Long, offset: Int) -> Unit,
    val onStartReviewing: () -> Unit,
    val onGrantPermission: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenWallpaperSettings: () -> Unit,
    val onOpenWallpaperStyle: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onOpenBatterySettings: () -> Unit,
    val onSetSeveralInARow: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: ScreenState,
    decks: List<DeckItem>,
    selectedDeckIds: List<Long>,
    currentCard: CurrentCard,
    isStarting: Boolean,
    lockOnlyWallpaper: Boolean,
    setup: SetupStatus,
    severalInARow: Boolean,
    actions: MainScreenActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state == ScreenState.Ready) {
                StartReviewingBar(enabled = selectedDeckIds.isNotEmpty(), isStarting = isStarting, onClick = actions.onStartReviewing)
            }
        },
    ) { padding ->
        when (state) {
            ScreenState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ScreenState.ApiUnavailable -> MessageScreen(
                padding = padding,
                title = stringResource(R.string.error_api_unavailable_title),
                message = stringResource(R.string.error_api_unavailable),
                buttonLabel = stringResource(R.string.retry),
                onClick = actions.onRetry,
            )
            ScreenState.PermissionMissing -> MessageScreen(
                padding = padding,
                title = stringResource(R.string.error_permission_title),
                message = stringResource(R.string.error_permission),
                buttonLabel = stringResource(R.string.grant_permission),
                onClick = actions.onGrantPermission,
            )
            ScreenState.Ready -> ReadyContent(padding, decks, selectedDeckIds, currentCard, lockOnlyWallpaper, setup, severalInARow, actions)
        }
    }
}

@Composable
private fun ReadyContent(
    padding: PaddingValues,
    decks: List<DeckItem>,
    selectedDeckIds: List<Long>,
    currentCard: CurrentCard,
    lockOnlyWallpaper: Boolean,
    setup: SetupStatus,
    severalInARow: Boolean,
    actions: MainScreenActions,
) {
    var showWallpaperInstructions by rememberSaveable { mutableStateOf(false) }
    if (showWallpaperInstructions) {
        WallpaperInstructionsDialog(
            lockOnlyWallpaper = lockOnlyWallpaper,
            onDismiss = { showWallpaperInstructions = false },
            onOpenWallpaperSettings = actions.onOpenWallpaperSettings,
        )
    }
    val decksById = decks.associateBy { it.id }
    val selectedDecks = selectedDeckIds.mapNotNull { decksById[it] }
    val tree = remember(decks) { buildDeckTree(decks) }
    // Full names of the unfolded decks. Everything starts folded, like AnkiDroid's deck list.
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val visibleNodes = flattenVisible(tree, expanded)
    val selectedNames = selectedDecks.map { it.name }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!setup.isComplete) {
            item(key = "setup") { SetupCard(setup, onSetWallpaper = { showWallpaperInstructions = true }, actions) }
        }
        item(key = "current") { CurrentCardCard(currentCard, onlyWhileLocked = setup.wallpaperActive) }
        item(key = "lock") {
            LockScreenCard(
                wallpaperActive = setup.wallpaperActive,
                onSetWallpaper = { showWallpaperInstructions = true },
                onCustomize = actions.onOpenWallpaperStyle,
            )
        }
        item(key = "several") { SeveralInARowCard(severalInARow, actions.onSetSeveralInARow) }

        item(key = "order-header") {
            SectionHeader(stringResource(R.string.review_order), stringResource(R.string.review_order_explanation))
        }
        if (selectedDecks.isEmpty()) {
            item(key = "order-empty") {
                Text(
                    stringResource(R.string.review_order_empty),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(selectedDecks, key = { _, deck -> "order-${deck.id}" }) { index, deck ->
            SelectedDeckRow(
                deck = deck,
                position = index,
                isLast = index == selectedDecks.lastIndex,
                onMove = { offset -> actions.onMoveDeck(deck.id, offset) },
                onRemove = { actions.onToggleDeck(deck.id) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "tree-header") { SectionHeader(stringResource(R.string.all_decks), null) }
        if (decks.isEmpty()) {
            item(key = "no-decks") {
                Text(stringResource(R.string.no_decks), Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            item(key = "tree") {
                // One card for the whole tree, with rows inside, as in AnkiDroid's deck list.
                Card(
                    Modifier.fillMaxWidth().animateContentSize(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        visibleNodes.forEach { node ->
                            val deckId = node.deck?.id
                            DeckTreeRow(
                                node = node,
                                isExpanded = node.fullName in expanded,
                                position = if (deckId == null) -1 else selectedDeckIds.indexOf(deckId),
                                includedIn = selectedAncestor(node.fullName, selectedNames),
                                onToggleExpanded = {
                                    expanded = if (node.fullName in expanded) expanded - node.fullName else expanded + node.fullName
                                },
                                onToggleSelected = { if (deckId != null) actions.onToggleDeck(deckId) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "version") { AppVersion() }
    }
}

/** The installed version, from `versionName` in app/build.gradle.kts. */
@Composable
private fun AppVersion() {
    val context = LocalContext.current
    val versionName = remember {
        @Suppress("DEPRECATION") // The flags overload needs API 33.
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    Text(
        stringResource(R.string.app_version, stringResource(R.string.app_name), versionName ?: "?"),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp, end = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CurrentCardCard(currentCard: CurrentCard, onlyWhileLocked: Boolean) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.current_card).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            when (currentCard) {
                is CurrentCard.Showing -> {
                    Text(
                        currentCard.question,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (currentCard.deckName.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.from_deck, currentCard.deckName.replace("::", " › ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    if (onlyWhileLocked) {
                        // The notification is only posted while the phone is locked.
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.current_card_lock_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                CurrentCard.NoneDue -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.all_cards_done),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.all_cards_done_next),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                CurrentCard.NotStarted -> Text(
                    stringResource(R.string.current_card_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun LockScreenCard(wallpaperActive: Boolean, onSetWallpaper: () -> Unit, onCustomize: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lock_screen), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.lock_screen_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.lock_screen_sensitive_tip),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!wallpaperActive) {
                FilledTonalButton(onClick = onSetWallpaper, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_lock_wallpaper), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = onCustomize, modifier = if (wallpaperActive) Modifier.fillMaxWidth() else Modifier) {
                Text(stringResource(R.string.customize_wallpaper))
            }
        }
    }
}

@Composable
private fun WallpaperInstructionsDialog(lockOnlyWallpaper: Boolean, onDismiss: () -> Unit, onOpenWallpaperSettings: () -> Unit) {
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

/** "Review several cards in a row": off by default, one card each time the phone is unlocked. */
@Composable
private fun SeveralInARowCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable { onChange(!enabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.several_in_a_row), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (enabled) R.string.several_in_a_row_on else R.string.several_in_a_row_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

/** What still needs to be set up; the checklist only shows the items that aren't done. */
data class SetupStatus(
    val notificationsEnabled: Boolean = true,
    val wallpaperActive: Boolean = true,
    val batteryUnrestricted: Boolean = true,
) {
    val isComplete get() = notificationsEnabled && wallpaperActive && batteryUnrestricted
}

@Composable
private fun SetupCard(setup: SetupStatus, onSetWallpaper: () -> Unit, actions: MainScreenActions) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.setup_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (!setup.notificationsEnabled) {
                SetupItem(R.string.setup_notifications, R.string.setup_notifications_detail, actions.onOpenNotificationSettings)
            }
            if (!setup.wallpaperActive) {
                SetupItem(R.string.setup_wallpaper, R.string.setup_wallpaper_detail, onSetWallpaper)
            }
            if (!setup.batteryUnrestricted) {
                SetupItem(R.string.setup_battery, R.string.setup_battery_detail, actions.onOpenBatterySettings)
            }
        }
    }
}

@Composable
private fun SetupItem(title: Int, detail: Int, onFix: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                stringResource(detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
            )
        }
        TextButton(onClick = onFix) { Text(stringResource(R.string.setup_fix)) }
    }
}

@Composable
private fun SelectedDeckRow(
    deck: DeckItem,
    position: Int,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PositionBadge(position)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                deck.parentPath?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(deck.shortName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DeckCounts(deck)
            }
            IconButton(onClick = { onMove(-1) }, enabled = position > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
            }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_deck))
            }
        }
    }
}

@Composable
private fun DeckTreeRow(
    node: DeckNode,
    isExpanded: Boolean,
    position: Int,
    includedIn: String?,
    onToggleExpanded: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    val deck = node.deck
    val hasChildren = node.children.isNotEmpty()
    // Already reviewed through a selected parent. A deck selected on its own stays active so it can be removed.
    val covered = includedIn != null && position < 0
    val selectable = deck != null && !covered
    val chevronRotation by animateFloatAsState(if (isExpanded) 90f else 0f, label = "chevron")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable || hasChildren) { if (selectable) onToggleSelected() else onToggleExpanded() }
            .padding(start = (node.depth.coerceAtMost(MAX_INDENT_LEVELS) * 20).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(if (isExpanded) R.string.collapse else R.string.expand),
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp).alpha(if (covered) DISABLED_ALPHA else 1f)) {
            Text(
                node.shortName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (node.depth == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (covered) {
                Text(
                    stringResource(R.string.included_with_parent, includedIn!!.substringAfterLast("::")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (deck != null) {
                DeckCounts(deck)
            }
        }
        if (position >= 0) {
            PositionBadge(position)
        }
        if (deck != null) {
            Checkbox(checked = position >= 0 || covered, onCheckedChange = { onToggleSelected() }, enabled = !covered)
        }
    }
}

@Composable
private fun PositionBadge(position: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text("${position + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Opacity of a deck already included through its selected parent. */
private const val DISABLED_ALPHA = 0.45f

/** Deeper levels aren't indented further, so names keep enough room on a phone screen. */
private const val MAX_INDENT_LEVELS = 4

@Composable
private fun DeckCounts(deck: DeckItem) {
    if (deck.newCount == null || deck.learnCount == null || deck.reviewCount == null) return
    // Same colors as AnkiDroid's deck list: blue new, red learning, green review.
    val text = stringResource(R.string.deck_counts, deck.newCount, deck.learnCount, deck.reviewCount)
    val numbers = listOf(deck.newCount to NewCountColor, deck.learnCount to LearnCountColor, deck.reviewCount to ReviewCountColor)
    val parts = text.split(" · ")
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append(" · ")
                val (count, color) = numbers.getOrNull(index) ?: (0 to null)
                if (color != null && count > 0) {
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(part) }
                } else {
                    append(part)
                }
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StartReviewingBar(enabled: Boolean, isStarting: Boolean, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Button(
            onClick = onClick,
            enabled = enabled && !isStarting,
            modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().height(52.dp),
        ) {
            if (isStarting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (enabled) R.string.start_reviewing else R.string.select_a_deck_first))
            }
        }
    }
}

@Composable
private fun MessageScreen(padding: PaddingValues, title: String, message: String, buttonLabel: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClick) { Text(buttonLabel) }
    }
}
