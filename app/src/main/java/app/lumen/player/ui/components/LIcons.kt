package app.lumen.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Свои иконки, нарисованные по макету: обводка 2 px, скруглённые концы. */
object LIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.White)) }
        }.build()

    val Play = fill("play", "M8 5.5v13l11-6.5z")
    val Pause = fill("pause", "M7.2 5h2.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2H7.2A1.2 1.2 0 0 1 6 17.8V6.2A1.2 1.2 0 0 1 7.2 5z M14.2 5h2.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2h-2.6a1.2 1.2 0 0 1-1.2-1.2V6.2A1.2 1.2 0 0 1 14.2 5z")
    val Next = fill("next", "M4 5.8v12.4a1 1 0 0 0 1.5.9l10.1-6.2a1 1 0 0 0 0-1.8L5.5 4.9A1 1 0 0 0 4 5.8z M17.4 5h2.6v14h-2.6z")
    val Prev = fill("prev", "M20 5.8v12.4a1 1 0 0 1-1.5.9L8.4 12.9a1 1 0 0 1 0-1.8l10.1-6.2a1 1 0 0 1 1.5.9z M4 5h2.6v14H4z")
    val Shuffle = stroke("shuffle", "M16 4h4v4", "M4 20L20 4", "M20 16v4h-4", "M15 15l5 5", "M4 4l5 5")
    val Repeat = stroke("repeat", "M17 2l3 3-3 3", "M4 11V9a4 4 0 0 1 4-4h12", "M7 22l-3-3 3-3", "M20 13v2a4 4 0 0 1-4 4H4")
    val RepeatOne = stroke("repeat_one", "M17 2l3 3-3 3", "M4 11V9a4 4 0 0 1 4-4h12", "M7 22l-3-3 3-3", "M20 13v2a4 4 0 0 1-4 4H4", "M11 10.5l1.5-1v5")
    private const val HEART = "M12 21s-7.5-4.6-9.5-9.3C1.3 8.6 3.4 5 7 5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.6 0 5.7 3.6 4.5 6.7C19.5 16.4 12 21 12 21z"
    val Heart = fill("heart", HEART)
    val HeartOutline = stroke("heart_o", HEART)
    val Search = stroke("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Sliders = stroke("sliders", "M4 6h10", "M18 6h2", "M4 12h4", "M12 12h8", "M4 18h12", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f))
    val Back = stroke("back", "M15 5l-7 7 7 7", width = 2.2f)
    val Down = stroke("down", "M5 9l7 7 7-7", width = 2.2f)
    val More = fill("more", circle(12f, 5f, 1.8f), circle(12f, 12f, 1.8f), circle(12f, 19f, 1.8f))
    val MoreH = fill("more_h", circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f))
    val Home = stroke("home", "M4 10.5L12 4l8 6.5V20h-5v-6H9v6H4z")
    val Library = stroke("library", "M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z")
    val Lyrics = stroke("lyrics", "M4 6h16", "M4 11h16", "M4 16h10", circle(18f, 18f, 2.5f))
    val Queue = stroke("queue", "M4 6h12", "M4 11h12", "M4 16h7", "M18 13v8", "M14 17h8")
    val Moon = stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Close = stroke("close", "M6 6l12 12", "M18 6L6 18", width = 2.2f)
    val Folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Plus = stroke("plus", "M12 5v14", "M5 12h14")
    val Refresh = stroke("refresh", "M20 11a8 8 0 1 0-2.3 5.7", "M20 5v6h-6")
    val Check = stroke("check", "M5 12l5 5L20 7", width = 2.2f)
    val Note = stroke("note", "M9 18V5l11-2v13", circle(6f, 18f, 3f), circle(17f, 16f, 3f))
    val Trash = stroke("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val Person = stroke("person", circle(12f, 8f, 4f), "M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6")
    val Disc = stroke("disc", circle(12f, 12f, 9f), circle(12f, 12f, 2.5f))
    val Sparkle = stroke("sparkle", "M11 3l1.9 5.1L18 10l-5.1 1.9L11 17l-1.9-5.1L4 10l5.1-1.9z", "M19 15l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z")
    val ArrowUp = stroke("arrow_up", "M12 19V5", "M6 11l6-6 6 6")
    val ArrowDown = stroke("arrow_down", "M12 5v14", "M6 13l6 6 6-6")
    val DragHandle = stroke("drag", "M5 9h14", "M5 15h14")
    val Expand = stroke("expand", "M15 3h6v6", "M9 21H3v-6", "M21 3l-7 7", "M3 21l7-7")
    val PlayNext =stroke("play_next", "M4 6h10", "M4 11h10", "M4 16h6", "M15 13l5 3.5-5 3.5z")
    val Playlist = stroke("playlist", "M4 6h12", "M4 11h12", "M4 16h7", "M17 14v6", circle(15f, 20f, 2f), "M17 14l4-1")
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Wave = stroke("wave", "M3 12h2", "M7 8v8", "M11 5v14", "M15 9v6", "M19 11v2")
    val Dislike = stroke("dislike", HEART, "M12.6 7.6l-2 3.6 3 1.6-1.8 3.8")
    val Duplicate = stroke("duplicate", "M8 8h11v11H8z", "M5 16V5h11")
    val Rules = stroke("rules", "M4 6h9", "M4 12h6", "M4 18h9", circle(17f, 6f, 2f), circle(14f, 12f, 2f), circle(17f, 18f, 2f))
    val Trim = stroke("trim", circle(6f, 6f, 2.5f), circle(6f, 18f, 2.5f), "M8 7.5L20 18", "M8 16.5L20 6")
    val Info = stroke("info", circle(12f, 12f, 9f), "M12 11v6", "M12 7.5v.5")
}
