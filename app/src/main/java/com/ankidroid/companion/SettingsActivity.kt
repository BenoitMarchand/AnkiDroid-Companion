package com.ankidroid.companion

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ankidroid.companion.ui.WallpaperInstructionsDialog
import com.ankidroid.companion.ui.WallpaperSetup
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The app's settings: reviewing behavior, what the lock screen wallpaper shows, and how it looks. Opened from the
 * main screen and from the system wallpaper picker.
 */
class SettingsActivity : ComponentActivity() {
    /** Read again on resume: the user may come back from the wallpaper settings. */
    private var wallpaperActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnkiNotificationsTheme {
                SettingsScreen(wallpaperActive = wallpaperActive, onBack = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wallpaperActive = CardWallpaperService.isActive(this)
    }
}

private val BACKGROUND_COLORS = listOf(
    WallpaperStyle.DEFAULT_BACKGROUND, 0xFF0D1B2A.toInt(), 0xFF1B3A2F.toInt(), 0xFF2E1A3B.toInt(),
    0xFF3E2723.toInt(), 0xFF37474F.toInt(), 0xFFF3E9DC.toInt(), 0xFFFAFAFA.toInt(),
)
private val TEXT_COLORS = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFE082.toInt(), 0xFF80DEEA.toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(wallpaperActive: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var severalInARow by remember { mutableStateOf(ReviewSettings.severalInARow(context)) }
    var rotateOnUnlock by remember { mutableStateOf(ReviewSettings.rotateOnUnlock(context)) }
    var style by remember { mutableStateOf(WallpaperStyle.load(context)) }
    var background by remember { mutableStateOf<Bitmap?>(null) }
    val sample = remember { previewContent(context) }
    var showWallpaperInstructions by rememberSaveable { mutableStateOf(false) }
    if (showWallpaperInstructions) {
        WallpaperInstructionsDialog(
            lockOnlyWallpaper = WallpaperSetup.lockOnly,
            onDismiss = { showWallpaperInstructions = false },
            onOpenWallpaperSettings = { WallpaperSetup.openSettings(context) },
        )
    }

    fun update(newStyle: WallpaperStyle) {
        style = newStyle
        WallpaperStyle.save(context, newStyle)
    }

    LaunchedEffect(style.hasImage, style.imageVersion) {
        background = if (style.hasImage) withContext(Dispatchers.IO) { WallpaperStyle.loadBackground(context) } else null
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) { WallpaperStyle.importBackground(context, uri) }
            if (imported) {
                update(style.copy(hasImage = true, imageVersion = System.currentTimeMillis()))
            } else {
                Toast.makeText(context, R.string.wallpaper_image_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup(Icons.Filled.Refresh, stringResource(R.string.settings_behavior)) {
                SwitchRow(
                    title = stringResource(R.string.several_in_a_row),
                    summary = stringResource(if (severalInARow) R.string.several_in_a_row_on else R.string.several_in_a_row_off),
                    checked = severalInARow,
                    onChange = {
                        ReviewSettings.setSeveralInARow(context, it)
                        severalInARow = it
                    },
                )
                Column {
                    SwitchRow(
                        title = stringResource(R.string.rotate_on_unlock),
                        summary = stringResource(if (rotateOnUnlock) R.string.rotate_on_unlock_on else R.string.rotate_on_unlock_off),
                        checked = rotateOnUnlock,
                        onChange = {
                            ReviewSettings.setRotateOnUnlock(context, it)
                            rotateOnUnlock = it
                        },
                    )
                    if (rotateOnUnlock) {
                        if (!wallpaperActive) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.rotate_on_unlock_needs_wallpaper),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        RotationSize()
                    }
                }
            }

            SettingsGroup(Icons.Filled.DateRange, stringResource(R.string.scheduled_review_settings)) {
                ScheduledReviewSection()
            }

            SettingsGroup(Icons.Filled.Lock, stringResource(R.string.wallpaper_content)) {
                WallpaperStatus(wallpaperActive, onSetWallpaper = { showWallpaperInstructions = true })
                Column {
                    val labels = listOf(R.string.wallpaper_content_question, R.string.wallpaper_content_question_answer)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false, true).forEachIndexed { index, showAnswer ->
                            SegmentedButton(
                                selected = style.showAnswer == showAnswer,
                                onClick = { update(style.copy(showAnswer = showAnswer)) },
                                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            if (style.showAnswer) R.string.wallpaper_content_question_answer_summary
                            else R.string.wallpaper_content_question_summary
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.lock_screen_sensitive_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup(Icons.Filled.Edit, stringResource(R.string.wallpaper_settings_title)) {
                WallpaperPreview(style, background, sample, Modifier.align(Alignment.CenterHorizontally))

                Section(stringResource(R.string.wallpaper_background)) {
                    ColorChoices(BACKGROUND_COLORS, selected = style.backgroundColor.takeIf { !style.hasImage }) {
                        if (style.hasImage) WallpaperStyle.removeBackground(context)
                        update(style.copy(backgroundColor = it, hasImage = false))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(stringResource(R.string.wallpaper_choose_image)) }
                        if (style.hasImage) {
                            OutlinedButton(onClick = {
                                WallpaperStyle.removeBackground(context)
                                update(style.copy(hasImage = false))
                            }) { Text(stringResource(R.string.wallpaper_remove_image)) }
                        }
                    }
                }

                if (style.hasImage) {
                    PictureFilterSection(style, onPreview = { style = it }, onChange = ::update)
                }

                Section(stringResource(R.string.wallpaper_text_color)) {
                    ColorChoices(TEXT_COLORS, selected = style.textColor) { update(style.copy(textColor = it)) }
                }

                Section(stringResource(R.string.wallpaper_text_size)) {
                    val sizes = WallpaperStyle.TextSize.entries
                    val labels = listOf(R.string.text_size_small, R.string.text_size_medium, R.string.text_size_large)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        sizes.forEachIndexed { index, size ->
                            SegmentedButton(
                                selected = style.textSize == size,
                                onClick = { update(style.copy(textSize = size)) },
                                shape = SegmentedButtonDefaults.itemShape(index, sizes.size),
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val FILTER_LABELS = mapOf(
    PictureFilter.DARKEN to R.string.picture_filter_darken,
    PictureFilter.BLUR to R.string.picture_filter_blur,
    PictureFilter.GRADIENT to R.string.picture_filter_gradient,
    PictureFilter.PANEL to R.string.picture_filter_panel,
    PictureFilter.NONE to R.string.picture_filter_none,
)

/**
 * Filter type and intensity for the background picture. The slider updates the preview while it moves
 * ([onPreview]) and saves when released ([onChange]), so the wallpaper isn't redrawn on every step.
 */
@Composable
private fun PictureFilterSection(style: WallpaperStyle, onPreview: (WallpaperStyle) -> Unit, onChange: (WallpaperStyle) -> Unit) {
    Section(stringResource(R.string.picture_filter)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PictureFilter.entries.forEach { filter ->
                FilterChip(
                    selected = style.filter == filter,
                    onClick = { onChange(style.copy(filter = filter)) },
                    label = {
                        // With dark text, the picture is lightened instead of darkened.
                        val label = if (filter == PictureFilter.DARKEN && !CardRenderer.isLight(style.textColor)) {
                            R.string.picture_filter_lighten
                        } else {
                            FILTER_LABELS.getValue(filter)
                        }
                        Text(stringResource(label))
                    },
                )
            }
        }
        if (style.filter != PictureFilter.NONE) {
            var intensity by remember(style.filter) { mutableIntStateOf(style.filterIntensity) }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.picture_filter_intensity, intensity),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = intensity.toFloat(),
                onValueChange = {
                    intensity = it.toInt()
                    onPreview(style.copy(filterIntensity = intensity))
                },
                onValueChangeFinished = { onChange(style.copy(filterIntensity = intensity)) },
                valueRange = 0f..PictureFilter.MAX_INTENSITY.toFloat(),
            )
        }
    }
}

/** The current card if there is one, otherwise an example, to preview the style with real-looking content. */
private fun previewContent(context: android.content.Context): CardContent {
    val state = AnkiDroidHelper(context).storedState
    val question = state?.takeIf { it.hasCard() }?.let { CardText.clean(it.question) }.orEmpty()
    return if (question.isNotEmpty()) {
        CardContent(question, state!!.deckName.replace("::", " › "), CardText.clean(state.answer))
    } else {
        CardContent(
            context.getString(R.string.wallpaper_preview_question),
            context.getString(R.string.wallpaper_preview_deck),
            context.getString(R.string.wallpaper_preview_answer),
        )
    }
}

@Composable
private fun WallpaperPreview(style: WallpaperStyle, background: Bitmap?, content: CardContent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val renderer = remember { CardRenderer(metrics.density) }
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels

    Canvas(
        modifier
            .width(220.dp)
            .aspectRatio(screenWidth.toFloat() / screenHeight)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
    ) {
        drawIntoCanvas { canvas ->
            // Draw at the screen's real size, scaled down, so the preview matches the lock screen.
            val native = canvas.nativeCanvas
            native.save()
            native.scale(size.width / screenWidth, size.height / screenHeight)
            renderer.draw(native, screenWidth, screenHeight, style, background, content)
            native.restore()
        }
    }
}

/** Number of due cards "Change the card at every unlock" rotates through, with a warning above 20. */
@Composable
private fun RotationSize() {
    val context = LocalContext.current
    var size by remember { mutableIntStateOf(ReviewSettings.rotationSize(context)) }
    Column {
        Text(stringResource(R.string.rotation_size, size), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = size.toFloat(),
            onValueChange = { size = it.roundToInt() },
            onValueChangeFinished = { ReviewSettings.setRotationSize(context, size) },
            valueRange = CardRotation.MIN_SIZE.toFloat()..CardRotation.MAX_SIZE.toFloat(),
            steps = CardRotation.MAX_SIZE - CardRotation.MIN_SIZE - 1,
        )
        if (size > CardRotation.WARNING_ABOVE) {
            Text(
                stringResource(R.string.rotation_size_warning, CardRotation.WARNING_ABOVE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * "Review at set times": the switch, then the times of day. Tapping a time changes it, the cross removes it.
 * Each change sets the next alarm again.
 */
@Composable
private fun ScheduledReviewSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ScheduledReviewSettings.isEnabled(context)) }
    var times by remember { mutableStateOf(ScheduledReviewSettings.times(context)) }

    fun saveTimes(newTimes: List<Int>) {
        times = newTimes
        ScheduledReviewSettings.setTimes(context, newTimes)
        ScheduledReview.schedule(context)
    }

    /** Opens the time picker at [initial]; the chosen time replaces [old], or is added. */
    fun pickTime(initial: Int, old: Int?) {
        TimePickerDialog(
            context,
            { _, hour, minute -> saveTimes(ReviewSchedule.put(times, hour * 60 + minute, old)) },
            initial / 60,
            initial % 60,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    Column {
        SwitchRow(
            title = stringResource(R.string.scheduled_review),
            summary = stringResource(if (enabled) R.string.scheduled_review_on else R.string.scheduled_review_off),
            checked = enabled,
            onChange = {
                ScheduledReviewSettings.setEnabled(context, it)
                enabled = it
                ScheduledReview.schedule(context)
            },
        )
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            if (times.isEmpty()) {
                Text(
                    stringResource(R.string.scheduled_review_no_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            times.forEach { time ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickTime(time, old = time) }) {
                        Text(formatTime(context, time), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { saveTimes(times - time) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.scheduled_review_remove_time))
                    }
                }
            }
            OutlinedButton(onClick = { pickTime(times.lastOrNull()?.plus(60)?.rem(ReviewSchedule.MINUTES_PER_DAY) ?: 12 * 60, old = null) }) {
                Text(stringResource(R.string.scheduled_review_add_time))
            }
        }
    }
}

/** [minutes] since midnight, in the phone's 12 or 24 hour format. */
private fun formatTime(context: android.content.Context, minutes: Int): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern(pattern))
}

/**
 * A group of settings in its own card, under an icon and a title. Tapping the title folds or unfolds it
 * (unfolded at first; kept on rotation).
 */
@Composable
private fun SettingsGroup(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
            }
        }
    }
}

/**
 * Whether the card wallpaper is set on the lock screen. If not, a warning and a button to the steps to set it:
 * the lock screen content and style only show with it.
 */
@Composable
private fun WallpaperStatus(wallpaperActive: Boolean, onSetWallpaper: () -> Unit) {
    if (wallpaperActive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallpaper_status_active), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.wallpaper_status_missing),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.setup_wallpaper_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onSetWallpaper, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.set_lock_wallpaper), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ColorChoices(colors: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { color ->
            val isSelected = color == selected
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (CardRenderer.isLight(color)) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}
