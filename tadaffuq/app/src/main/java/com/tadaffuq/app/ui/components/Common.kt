package com.tadaffuq.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.engine.SegmentState
import kotlin.math.max

/** عنوان كبير بروح Apple مع سطر رمادي اختياري. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = T.extra.secondaryText, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = MaterialTheme.shapes.large,
        color = T.extra.card,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
            content = content,
        )
    }
}

/** صف إعداد: عنوان وسطر شرح دائماً، وعنصر تحكم في الطرف. */
@Composable
fun SettingRow(
    title: String,
    description: String,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun ToggleRow(title: String, description: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) =
    SettingRow(title, description, icon, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
fun SliderRow(title: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}

@Composable
fun Hairline() = HorizontalDivider(Modifier.padding(start = 16.dp), color = T.extra.hairline)

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** شريط تقدم بزوايا دائرية يتبع اتجاه الواجهة. */
@Composable
fun ProgressLine(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val anim = rememberInfiniteTransition(label = "indeterminate")
    val shift by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shift")
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (fraction == null) {
            val w = size.width * 0.3f
            val x = (size.width + w) * shift - w
            drawRoundRect(color, topLeft = Offset(if (rtl) size.width - x - w else x, 0f), size = Size(w, size.height), cornerRadius = r)
        } else {
            val w = size.width * fraction.coerceIn(0f, 1f)
            if (w > 0) drawRoundRect(color, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** خريطة الأجزاء: كل جزء بموضعه الحقيقي من الملف، والمنجز منه ملوّن. */
@Composable
fun SegmentMap(segments: List<SegmentState>, total: Long, color: Color, description: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val divider = T.extra.card
    Canvas(modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)).semantics { contentDescription = description }) {
        drawRect(track)
        if (total <= 0) return@Canvas
        fun x(pos: Long) = (pos.toDouble() / total * size.width).toFloat().let { if (rtl) size.width - it else it }
        segments.forEach { s ->
            val a = x(s.start); val b = x(s.start + s.downloaded)
            val left = minOf(a, b); val w = kotlin.math.abs(b - a)
            if (w > 0) drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
            drawLine(divider, Offset(a, 0f), Offset(a, size.height), strokeWidth = 1.5f)
        }
    }
}

/** رسم حي للسرعة من عيّنات حقيقية (عيّنة كل ثانية). */
@Composable
fun SpeedChart(history: List<Long>, modifier: Modifier = Modifier, description: String) {
    val line = T.extra.chartLine
    val fill = T.extra.chartFill
    val grid = T.extra.hairline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = description }) {
        val maxV = max(history.maxOrNull() ?: 0L, 1L).toFloat() * 1.15f
        for (i in 1..3) {
            val y = size.height * i / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (history.size < 2) return@Canvas
        val step = size.width / (history.size - 1)
        // الأحدث في جهة نهاية القراءة: يمين في الإنجليزية ويسار في العربية
        fun px(i: Int) = if (rtl) size.width - i * step else i * step
        fun py(v: Long) = size.height - (v / maxV) * size.height
        val path = Path().apply {
            moveTo(px(0), py(history[0]))
            for (i in 1 until history.size) lineTo(px(i), py(history[i]))
        }
        val area = Path().apply {
            addPath(path)
            lineTo(px(history.lastIndex), size.height)
            lineTo(px(0), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fill, Color.Transparent)))
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * المشهد الحي: نقاط تتدفق من الخادم إلى الهاتف بسرعة تتناسب مع السرعة الحقيقية،
 * وعددها مع عدد الاتصالات. عند توقف البيانات تتوقف الحركة تماماً (لا قراءات مختلقة).
 */
@Composable
fun LiveScene(speedBps: Long, connections: Int, description: String, modifier: Modifier = Modifier) {
    val active = speedBps > 0
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // من 2.5 ثانية للعبور (بطيء) إلى 0.6 ثانية (سريع جداً)، بمقياس لوغاريتمي
    val mbps = speedBps / 1_048_576.0
    val period = if (!active) 0 else (2500 - (kotlin.math.ln(1 + mbps) / kotlin.math.ln(101.0) * 1900)).toInt().coerceIn(600, 2500)
    val anim = rememberInfiniteTransition(label = "scene")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(if (active) period else 1_000_000, easing = LinearEasing), RepeatMode.Restart), label = "flow")
    val primary = MaterialTheme.colorScheme.primary
    val accent = T.extra.chartLine
    val idle = T.extra.hairline
    Canvas(modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = description }) {
        val cy = size.height / 2
        val margin = 28.dp.toPx()
        val from = if (rtl) size.width - margin else margin
        val to = if (rtl) margin else size.width - margin
        // الخادم: ثلاث خانات
        for (i in -1..1) drawRoundRect(primary, topLeft = Offset(from - 12.dp.toPx(), cy + i * 9.dp.toPx() - 3.5.dp.toPx()), size = Size(24.dp.toPx(), 7.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
        // الهاتف
        drawRoundRect(primary, topLeft = Offset(to - 9.dp.toPx(), cy - 15.dp.toPx()), size = Size(18.dp.toPx(), 30.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
        val start = from + (if (rtl) -1 else 1) * 20.dp.toPx()
        val end = to - (if (rtl) -1 else 1) * 16.dp.toPx()
        drawLine(idle, Offset(start, cy), Offset(end, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        if (!active) return@Canvas
        val lanes = connections.coerceIn(1, 8)
        val dots = 4
        for (lane in 0 until lanes) {
            val laneY = cy + (lane - (lanes - 1) / 2f) * 4.dp.toPx()
            for (d in 0 until dots) {
                val p = ((t + d.toFloat() / dots + lane * 0.13f) % 1f)
                val x = start + (end - start) * p
                drawCircle(accent.copy(alpha = 0.35f + 0.65f * (1 - kotlin.math.abs(p - 0.5f) * 2)), radius = 2.6.dp.toPx(), center = Offset(x, laneY))
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, count: Int? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        if (count != null && count > 0) Text(com.tadaffuq.app.core.Fmt.iso(count.toString()), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
    }
}
