package mx.jano.comics.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mx.jano.comics.cache.DownloadProgress
import java.io.File

/**
 * Fondo de las pantallas de la ruta: la portada difuminada detrás (identidad de Cover), oscurecida para que el texto
 * se lea. En alto contraste (Ink Paper) no hay difuminado: negro liso.
 */
@Composable
internal fun CoverBackdrop(cover: File?, palette: ReaderPalette, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null && !palette.highContrast) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.scrim),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** «Descargando»: anillo de progreso con el porcentaje, MB bajados de MB totales, KB/s y lo que falta; Cancelar. */
@Composable
internal fun DownloadingScreen(
    title: String,
    cover: File?,
    progress: DownloadProgress?,
    palette: ReaderPalette,
    onCancel: () -> Unit,
) {
    CoverBackdrop(cover, palette) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
            val fraction = progress?.let { ReaderFormat.fraction(it.bytes, it.total) }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .size(132.dp)
                            .semantics { contentDescription = ReaderTexts.downloadDescription(ReaderFormat.percent(fraction)) },
                        color = palette.accent,
                        trackColor = palette.outline,
                        strokeWidth = 8.dp,
                    )
                    Text(ReaderFormat.percent(fraction), color = palette.onBand, style = MaterialTheme.typography.titleLarge)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(132.dp), color = palette.accent, strokeWidth = 8.dp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                color = palette.onBand,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (progress == null) ReaderTexts.PREPARING else ReaderTexts.DOWNLOADING,
                color = palette.secondary,
                style = MaterialTheme.typography.labelLarge,
            )
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                val parts = buildList {
                    add(ReaderFormat.downloaded(progress.bytes, progress.total))
                    if (progress.bytesPerSecond > 0) add(ReaderFormat.speed(progress.bytesPerSecond))
                    ReaderFormat.secondsLeft(progress.bytes, progress.total, progress.bytesPerSecond)?.let { add(ReaderFormat.timeLeft(it)) }
                }
                Text(parts.joinToString(" · "), color = palette.onBand, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel, border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.outline)) {
                Text(ReaderTexts.CANCEL, color = palette.onBand)
            }
        }
    }
}

/** «Abriendo el cómic»: leer el índice de un CBR grande puede tardar un momento. */
@Composable
internal fun OpeningScreen(title: String, cover: File?, palette: ReaderPalette) {
    CoverBackdrop(cover, palette) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp), color = palette.accent, strokeWidth = 4.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, color = palette.onBand, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(ReaderTexts.OPENING, color = palette.secondary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Error al descargar o abrir: el mensaje (ya en español), una pista si aplica, Reintentar y Volver. */
@Composable
internal fun RouteErrorScreen(
    headline: String,
    message: String,
    hint: String?,
    cover: File?,
    palette: ReaderPalette,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
) {
    CoverBackdrop(cover, palette) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(headline, color = palette.onBand, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(message, color = palette.secondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(hint, color = palette.secondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onRetry != null) {
                    PrimaryButton(ReaderTexts.RETRY, palette, onRetry)
                }
                OutlinedButton(onClick = onBack, border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.outline)) {
                    Text(ReaderTexts.BACK, color = palette.onBand)
                }
            }
        }
    }
}

/**
 * Fin de número (un pliego más al final del lector): «Siguiente: <nombre>» con su portada si la hay, «Leer» y
 * «Volver»; sin N+1, «Has terminado la carpeta».
 */
@Composable
internal fun EndOfIssuePage(
    nextName: String?,
    nextCover: File?,
    palette: ReaderPalette,
    onRead: () -> Unit,
    onBack: () -> Unit,
) {
    CoverBackdrop(nextCover, palette) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(ReaderTexts.ISSUE_DONE, color = palette.secondary, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            if (nextName != null) {
                Text(ReaderTexts.NEXT, color = palette.secondary, style = MaterialTheme.typography.titleMedium)
                Text(
                    nextName,
                    color = palette.onBand,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nextCover != null) {
                    Spacer(Modifier.height(16.dp))
                    AsyncImage(
                        model = nextCover,
                        contentDescription = ReaderTexts.NEXT_COVER,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .aspectRatio(DEFAULT_PAGE.aspect)
                            .border(if (palette.highContrast) 2.dp else 1.dp, palette.outline),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(ReaderTexts.READ, palette, onRead)
                    OutlinedButton(onClick = onBack, border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.outline)) {
                        Text(ReaderTexts.BACK, color = palette.onBand)
                    }
                }
            } else {
                Text(
                    ReaderTexts.FOLDER_DONE,
                    color = palette.onBand,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(ReaderTexts.FOLDER_DONE_HINT, color = palette.secondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onBack, border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.outline)) {
                    Text(ReaderTexts.BACK, color = palette.onBand)
                }
            }
        }
    }
}

/** Botón principal: rojo de Cover; en alto contraste, blanco con texto negro y borde. */
@Composable
private fun PrimaryButton(label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent),
        border = if (palette.highContrast) BorderStroke(2.dp, palette.outline) else null,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
