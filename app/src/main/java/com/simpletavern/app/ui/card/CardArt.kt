package com.simpletavern.app.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.simpletavern.app.ui.theme.Foil
import com.simpletavern.app.ui.theme.LocalTable
import com.simpletavern.app.ui.theme.Type

enum class Suit(val glyph: String, val red: Boolean) { Spade("♠", false), Heart("♥", true) }

private val Outer = RoundedCornerShape(14.dp)
private val Inner = RoundedCornerShape(12.dp)

/** Foil-edged ivory card with an inner gold rule and concave corner ornaments. */
@Composable
fun CardFrame(
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalTable.current
    Box(
        modifier
            .clip(if (rounded) Outer else RoundedCornerShape(0.dp))
            .background(Foil)
            .padding(2.dp)
            .clip(if (rounded) Inner else RoundedCornerShape(0.dp))
            .background(t.paper)
            .drawBehind {
                val inset = 7.dp.toPx()
                drawRoundRect(
                    color = t.gold.copy(alpha = 0.85f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
                drawCornerArcs(t.gold, 11.dp.toPx(), 10.dp.toPx(), 1.5.dp.toPx())
            },
        content = content,
    )
}

private fun DrawScope.drawCornerArcs(color: Color, inset: Float, r: Float, w: Float) {
    val corners = listOf(
        Offset(inset, inset) to 0f,
        Offset(size.width - inset, inset) to 90f,
        Offset(size.width - inset, size.height - inset) to 180f,
        Offset(inset, size.height - inset) to 270f,
    )
    corners.forEach { (c, start) ->
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(w),
        )
    }
}

/** Rank + suit printed in a corner. */
@Composable
fun CornerIndex(rank: String, suit: Suit, modifier: Modifier = Modifier) {
    val t = LocalTable.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(rank, style = Type.index.copy(color = t.ink))
        BasicText(suit.glyph, style = Type.index.copy(fontSize = 16.sp, color = if (suit.red) t.red else t.ink))
    }
}

/** Message card: who line, body with an illuminated first letter, faded at the bottom when clipped. */
@Composable
fun CardFace(
    rank: String,
    suit: Suit,
    who: String,
    body: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 7,
    placeholder: String = "…",
) {
    val t = LocalTable.current
    CardFrame(modifier) {
        CornerIndex(rank, suit, Modifier.align(Alignment.TopStart).padding(start = 15.dp, top = 13.dp))
        CornerIndex(
            rank, suit,
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 15.dp, bottom = 13.dp)
                .graphicsLayer { rotationZ = 180f },
        )
        Column(Modifier.fillMaxSize().padding(start = 44.dp, top = 30.dp, end = 36.dp, bottom = 28.dp)) {
            BasicText(who, style = Type.who.copy(color = t.goldDeep), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 6.dp).fadeBottom()) {
                BasicText(
                    text = illuminated(body.ifBlank { placeholder }, t.red),
                    style = Type.body.copy(color = t.ink),
                    maxLines = maxLines,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** The face-down card: burgundy cloth, gold lattice, a fleuron in the middle. */
@Composable
fun CardBack(modifier: Modifier = Modifier, mark: Boolean = true) {
    val t = LocalTable.current
    Box(
        modifier
            .clip(Outer)
            .background(Foil)
            .padding(2.dp)
            .clip(Inner)
            .background(t.cloth)
            .drawBehind { drawLattice(t.gold.copy(alpha = 0.4f), 9.dp.toPx(), 1.dp.toPx()) },
        contentAlignment = Alignment.Center,
    ) {
        if (mark) BasicText("❦", style = Type.title.copy(fontSize = 54.sp, color = t.gold, letterSpacing = 0.em))
    }
}

private fun DrawScope.drawLattice(color: Color, step: Float, w: Float) {
    clipRect {
        val span = size.width + size.height
        var x = -size.height
        while (x < span) {
            drawLine(color, Offset(x, 0f), Offset(x + size.height, size.height), w)
            drawLine(color, Offset(x + size.height, 0f), Offset(x, size.height), w)
            x += step * 1.414f
        }
    }
}

/** First letter enlarged in red, like an illuminated manuscript. */
fun illuminated(text: String, red: Color): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    val first = text.codePointAt(0).let { Character.charCount(it) }
    withStyle(SpanStyle(color = red, fontWeight = FontWeight.Bold, fontSize = 1.5.em)) { append(text.substring(0, first)) }
    append(text.substring(first))
}

/** Fades the bottom 20% of the content to transparent. */
fun Modifier.fadeBottom(from: Float = 0.8f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(from to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** Rounded pill button outline used across the table. */
@Composable
fun rememberPillShape(): RoundedCornerShape = remember { RoundedCornerShape(999.dp) }

val CardCorner: Dp = 14.dp
