package com.sinat.osrsbubbletool

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave

// The app's line icons, drawn on a 24 x 24 grid with the same line thickness and round ends. All original drawings.
object Icons {
    fun path(name: String) = PATHS.getValue(name)

    private val PATHS = mapOf(
        "back" to "M15 5l-7 7 7 7",
        "next" to "M9 5l7 7-7 7",
        "down" to "M6 9l6 6 6-6",
        "close" to "M6 6l12 12M18 6L6 18",
        "plus" to "M12 5v14M5 12h14",
        "check" to "M5 12.5l4.5 4.5L19 7.5",
        "stop" to "M7 7h10v10H7z",
        "trash" to "M4 7h16M9 7V4h6v3M6.5 7l1 13h9l1-13M10 11v5M14 11v5",
        "gear" to "M10.58 4.84L10.94 2.06L13.06 2.06L13.42 4.84L16.06 5.93L18.28 4.22L19.78 5.72L18.07 7.94L19.16 10.58L21.94 10.94L21.94 13.06L19.16 13.42L18.07 16.06L19.78 18.28L18.28 19.78L16.06 18.07L13.42 19.16L13.06 21.94L10.94 21.94L10.58 19.16L7.94 18.07L5.72 19.78L4.22 18.28L5.93 16.06L4.84 13.42L2.06 13.06L2.06 10.94L4.84 10.58L5.93 7.94L4.22 5.72L5.72 4.22L7.94 5.93zM12 8.9a3.1 3.1 0 1 0 0 6.2 3.1 3.1 0 0 0 0-6.2z",
        "sun" to "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6L7 7M17 17l1.4 1.4M18.4 5.6L17 7M7 17l-1.4 1.4",
        "moon" to "M20 14A8 8 0 1 1 10 4a6.5 6.5 0 0 0 10 10z",
        "search" to "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4",
        "dot" to "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z",
        "puzzle" to "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM15 15h3v3h-3z",
        "bulb" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z",
        "pyramid" to "M3 20L12 5l9 15zM8 20l4-7 4 7",
        "flask" to "M9 3h6M10 3v6l-5.5 9.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3M7.5 15h9",
        // the outline of Zulrah's island: a horseshoe, turned the way the Zulrah Helper's map shows it
        "island" to "M12 4.5C8.7 4.5 6.2 5.4 4.7 7.2 3.6 8.6 3.4 10.2 3.3 12.2 3.2 13.8 3 15 2.2 16.4 1.8 17.2 2.6 18.4 3.8 19 5.5 19.8 7.9 19.6 8.9 18.4 9.4 17.8 8.3 16.3 8 14.6 7.7 12.5 8.2 10.9 9.6 9.7 10.4 9 11.2 8.7 12 8.7S13.6 9 14.4 9.7C15.8 10.9 16.3 12.5 16 14.6 15.7 16.3 14.6 17.8 15.1 18.4 16.1 19.6 18.5 19.8 20.2 19 21.4 18.4 22.2 17.2 21.8 16.4 21 15 20.8 13.8 20.7 12.2 20.6 10.2 20.4 8.6 19.3 7.2 17.8 5.4 15.3 4.5 12 4.5z",
        "book" to "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11M9 8h6",
        "xp" to "M4 20v-5h4v5zM10 20V10h4v10zM16 20V4h4v16z",   // three bars, each taller than the last
        "sword" to "M20.5 3.5h-3.8L7.2 13l3.8 3.8 9.5-9.5zM5.2 11l7.8 7.8M8 16l-4 4M2.8 18.8l2.4 2.4",   // blade, crossguard, grip, pommel
        "chart" to "M4 4v16h16M8 15l4-4 3 3 5-6",
        "calc" to "M6 3h12v18H6zM9 7h6M9 12h.01M12 12h.01M15 12h.01M9 16h.01M12 16h.01M15 16h.01",
        "clock" to "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7.5V12l3 2",
        "bell" to "M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 21h4",
        "star" to "M12 3l2.7 5.6 6.1.8-4.5 4.2 1.2 6.1L12 16.8l-5.5 2.9 1.2-6.1-4.5-4.2 6.1-.8z",
        "drop" to "M12 3s7 7.2 7 12a7 7 0 1 1-14 0c0-4.8 7-12 7-12z",
        "target" to "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 12h.01",
        "sync" to "M4 12a8 8 0 0 1 13.7-5.6L20 9M20 4v5h-5M20 12a8 8 0 0 1-13.7 5.6L4 15M4 20v-5h5",
        "globe" to "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18",
        "helmet" to "M12 3c4.5 0 7 3 7 7v8l-3 3h-2.500v-7.500l2-2.500V9.500h-7V11l2 2.500V21H8l-3-3v-8c0-4 2.500-7 7-7z",   // a knight's helmet, face on
        "pin" to "M12 21s7-6.2 7-11.5a7 7 0 1 0-14 0C5 14.8 12 21 12 21zM12 7a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z",
        "note" to "M6 3h12v18H6zM9 8h6M9 12h6M9 16h4",
        "dice" to "M5 5h14v14H5zM9 9h.01M15 9h.01M12 12h.01M9 15h.01M15 15h.01",
        "minus" to "M5 12h14",
        "turn_map" to "M4 10h10v10H4zM9 6.5a8.5 8.5 0 0 1 10.5 6.5M17 11.2l2.5 1.8 1.6-2.8",   // a square map with an arrow turning it
        "reset" to "M4 12a8 8 0 1 0 2.6-5.9L4 8.5M4 3.5v5h5",
        "play" to "M8 5l11 7-11 7z",
        "shield" to "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6zM9 12l2 2 4-4",
        "flag" to "M6 21V4M6 4h11l-2 4 2 4H6",
        "doc" to "M7 3h7l4 4v14H7zM14 3v4h4M10 12h5M10 16h5",
        "download" to "M12 4v11M7 10l5 5 5-5M5 20h14",
        "coffee" to "M5 9h12v6a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5zM17 10h1.5a2.5 2.5 0 0 1 0 5H17M9 3v3M13 3v3",
        "chat" to "M4 5h16v11H9l-5 4z",
        "warning" to "M12 4 2.8 20h18.4zM12 10v4.500M12 17.500h.01"
    )
}

// One of the icons above, in a colour
class StrokeIcon(data: String, colour: Int) : Drawable() {
    private val path = PathParser.createPathFromPathData(data)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colour
    }

    override fun draw(canvas: Canvas) {
        val scale = bounds.width() / 24f
        canvas.withSave {
            translate(bounds.left.toFloat(), bounds.top.toFloat())
            scale(scale, scale)
            drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
