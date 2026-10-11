package com.ankidroid.companion

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the user choose how the lock screen card looks. Also opened from the system wallpaper picker. */
class WallpaperSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnkiNotificationsTheme {
                WallpaperSettingsScreen(onBack = ::finish)
            }
        }
    }
}

private val BACKGROUND_COLORS = listOf(
    WallpaperStyle.DEFAULT_BACKGROUND, 0xFF0D1B2A.toInt(), 0xFF1B3A2F.toInt(), 0xFF2E1A3B.toInt(),
    0xFF3E2723.toInt(), 0xFF37474F.toInt(), 0xFFF3E9DC.toInt(), 0xFFFAFAFA.toInt(),
)
private val TEXT_COLORS = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFE082.toInt(), 0xFF80DEEA.toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(WallpaperStyle.load(context)) }
    var background by remember { mutableStateOf<Bitmap?>(null) }
    val sample = remember { previewContent(context) }

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
                title = { Text(stringResource(R.string.wallpaper_settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
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
        CardContent(question, state!!.deckName.replace("::", " › "))
    } else {
        CardContent(context.getString(R.string.wallpaper_preview_question), context.getString(R.string.wallpaper_preview_deck))
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
