package com.labteto.dshmobile.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Current workspace image, fetched only while the read_image disclosure is expanded.
 * No persistent bitmap cache: reopening rechecks the file, including deletion. The original
 * tool result remains below; this preview does not claim to be a historical snapshot.
 */
@Composable
fun ReadImagePreview(path: String, modifier: Modifier = Modifier) {
    val store = rememberSessionStore()
    val host by store.connectionState.collectAsState()
    val sessionId by store.currentSessionId.collectAsState()
    val owner = com.labteto.dshmobile.ui.screens.main.ComposerKey(
        host.host?.let { "${it.baseUrl}|${it.id}" }.orEmpty(), sessionId.orEmpty(),
    )
    var loading by remember(owner, path) { mutableStateOf(true) }
    var image by remember(owner, path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(owner, path) {
        try {
            val api = store.apiForHost(owner.host) ?: return@LaunchedEffect
            val stat = api.workspaceFileStat(owner.sessionId, path)
            val value = (stat as? com.labteto.dshmobile.core.wire.RpcResult.Ok)?.value ?: return@LaunchedEffect
            val byteCount = value.bytes ?: return@LaunchedEffect
            if (byteCount > 16L * 1024 * 1024) return@LaunchedEffect
            val read = api.workspaceFileReadBytes(owner.sessionId, path)
            val data = (read as? com.labteto.dshmobile.core.wire.RpcResult.Ok)?.value ?: return@LaunchedEffect
            if (data.version != value.version || data.data.size > 16 * 1024 * 1024) return@LaunchedEffect
            image = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data.data, 0, data.data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(data.data, 0, data.data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { image = null }
        finally { loading = false }
    }
    val colors = DsTheme.colors
    var fullscreen by remember(owner, path) { mutableStateOf(false) }
    if (fullscreen && image != null) ImageViewer(image!!, path) { fullscreen = false }
    Box(modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp)
        .clip(DsShapes.block).background(colors.bgModulePlatform), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, path, Modifier.fillMaxWidth().clickable { fullscreen = true }, contentScale = ContentScale.Fit)
        else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Image, null, Modifier.size(28.dp), tint = colors.labelCaption)
            Text(stringResource(if (loading) R.string.common_loading else R.string.chat_read_image_unavailable),
                style = DsType.small13, color = colors.labelTertiary)
        }
    }
}

/** Only accept an actual read_image invocation, not paths inferred from arbitrary result text. */
internal fun readImagePath(tool: String, arguments: String?): String? {
    if (tool != "read_image" || arguments == null) return null
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject
        (obj?.get("file_path") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Full-screen image, fixed close toolbar, bounded pinch zoom and drag pan. */
@Composable
private fun ImageViewer(bitmap: ImageBitmap, path: String, onClose: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val colors = DsTheme.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(colors.bgBase).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(path, Modifier.weight(1f).padding(8.dp), style = DsType.caption11, color = colors.labelTertiary)
                DsIconButton(Icons.Filled.Close, stringResource(R.string.common_close), onClose)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(DsShapes.block), contentAlignment = Alignment.Center) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                Image(bitmap, path, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(bitmap, width, height) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val x = width * (scale - 1) / 2
                            val y = height * (scale - 1) / 2
                            offset = if (scale == 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
                        }
                    }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            }
        }
    }
}
