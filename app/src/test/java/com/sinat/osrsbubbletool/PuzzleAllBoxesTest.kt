package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.PanelFinder.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

// Tests the Puzzle Box Solver on every puzzle box, not just the Troll one the owner has screenshots of.
//
// It makes fake phone screens: the owner's Troll screenshots (lowest, 50% and highest game brightness, from
// Documents\Androiddev\puzzle_shots) with the puzzle swapped for another one, shuffled, drawn the way the game draws
// it. The other puzzles come from the wiki's solved pictures, downloaded to the PC only by
// tools/puzzle-box/get_test_pictures.py (skipped without them, or without the screenshots).
//
// How the game draws the puzzle was measured on the owner's screenshots (November 2026), and the Troll picture drawn
// this way matches them to within 1 colour step in 255 on average:
// - The wiki picture is enlarged 2.1467 times (a tile and its line are 50 wiki pixels, 107.33 on the phone) with
//   smoothing between pixels (bilinear).
// - The brightness setting raises every colour to a power: 255 x (colour / 255) ^ power, the same for red, green and
//   blue (1.548 at the lowest, 1.158 at 50%, 0.736 at the highest). The frame and the lines between the tiles change
//   the same way. The empty space doesn't change: it's always 55,28,0.
// - In the wiki picture each tile is 46 x 46 pixels, the first one's top left corner at 19,18, one every 50 pixels.
class PuzzleAllBoxesTest {

    private val shotsDir = File(System.getenv("PUZZLE_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\puzzle_shots")
    private val pictureDir = File("../tools/puzzle-box/picture_cache")
    private val puzzles by lazy { TileMatcher.readPuzzles(File("src/main/assets/puzzle_tiles.bin").readBytes()) }

    // A background screenshot: its brightness power, and where a wiki line's middle (66.5, 65.5) lands on it
    private class Background(val file: String, val label: String, val power: Double, val lineX: Double, val lineY: Double)

    private val backgrounds = listOf(
        Background("Troll_3.png", "lowest", 1.548, 804.0, 501.25),
        Background("Troll_1.png", "50%", 1.158, 804.0, 501.5),
        Background("Troll_4.png", "highest", 0.736, 804.0, 501.5)
    )

    private companion object {
        const val SCALE = 107.333 / 50.0
        const val EMPTY_COLOUR = (55 shl 16) or (28 shl 8)
        // The part of the screen redrawn: the tiles, the lines between them and the frame's inner edge
        const val LEFT = 699; const val RIGHT = 1233; const val TOP = 399; const val BOTTOM = 929
    }

    // A screen as plain pixels, so it can be changed and read quickly
    private class Screen(override val width: Int, override val height: Int, val pixels: IntArray) : LightBoxReader.PixelSource {
        override fun rgb(x: Int, y: Int) = pixels[y * width + x] and 0xFFFFFF
        fun cut(area: Box) = IntArray(area.width * area.height) { i ->
            pixels[(area.top + i / area.width) * width + area.left + i % area.width]
        }
    }

    private fun load(file: File): Screen {
        val image = ImageIO.read(file)
        return Screen(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    // The wiki picture as red, green, blue numbers (0-255) per pixel
    private class Picture(val width: Int, val height: Int, val rgb: Array<DoubleArray>)

    private fun loadPicture(file: File): Picture {
        val image = ImageIO.read(file)
        return Picture(image.width, image.height, Array(3) { ch ->
            DoubleArray(image.width * image.height) { i ->
                ((image.getRGB(i % image.width, i / image.width) shr (16 - 8 * ch)) and 0xFF).toDouble()
            }
        })
    }

    // The puzzle with its tiles moved to `board` (board[place] = tile), at the background's brightness
    private fun arrange(picture: Picture, board: IntArray, power: Double): Picture {
        val bright = Array(3) { ch -> DoubleArray(picture.rgb[ch].size) { i -> 255.0 * (picture.rgb[ch][i] / 255.0).pow(power) } }
        val out = Array(3) { bright[it].copyOf() }
        for (place in 0 until 25) {
            val tile = board[place]
            val px = 19 + 50 * (place % 5); val py = 18 + 50 * (place / 5)
            val tx = 19 + 50 * (tile % 5); val ty = 18 + 50 * (tile / 5)
            for (dy in 0 until 46) for (dx in 0 until 46) {
                val to = (py + dy) * picture.width + px + dx
                val from = (ty + dy) * picture.width + tx + dx
                for (ch in 0..2) {
                    out[ch][to] = if (tile == TileMatcher.EMPTY) ((EMPTY_COLOUR shr (16 - 8 * ch)) and 0xFF).toDouble()
                    else bright[ch][from]
                }
            }
        }
        return Picture(picture.width, picture.height, out)
    }

    // Draws the arranged puzzle over the background, enlarged with smoothing as the game does
    private fun paint(background: Screen, place: Background, puzzle: Picture): Screen {
        val pixels = background.pixels.copyOf()
        for (y in TOP..BOTTOM) {
            val v = 65.5 + (y + 0.5 - place.lineY) / SCALE - 0.5
            val y0 = floor(v).toInt(); val fy = v - y0
            for (x in LEFT..RIGHT) {
                val u = 66.5 + (x + 0.5 - place.lineX) / SCALE - 0.5
                val x0 = floor(u).toInt(); val fx = u - x0
                var colour = 0xFF shl 24
                for (ch in 0..2) {
                    val c = puzzle.rgb[ch]; val w = puzzle.width
                    val top = c[y0 * w + x0] * (1 - fx) + c[y0 * w + x0 + 1] * fx
                    val under = c[(y0 + 1) * w + x0] * (1 - fx) + c[(y0 + 1) * w + x0 + 1] * fx
                    val value = (top * (1 - fy) + under * fy + 0.5).toInt().coerceIn(0, 255)
                    colour = colour or (value shl (16 - 8 * ch))
                }
                pixels[y * background.width + x] = colour
            }
        }
        return Screen(background.width, background.height, pixels)
    }

    // Shuffles the way the game does (only real slides), then walks the empty space to `emptyAt`
    private fun shuffle(random: Random, slides: Int, emptyAt: Int): IntArray {
        val board = IntArray(25) { it }
        var empty = 24
        fun slide(to: Int) { board[empty] = board[to]; board[to] = TileMatcher.EMPTY; empty = to }
        repeat(slides) {
            val next = listOfNotNull(if (empty >= 5) empty - 5 else null, if (empty < 20) empty + 5 else null,
                if (empty % 5 > 0) empty - 1 else null, if (empty % 5 < 4) empty + 1 else null).random(random)
            slide(next)
        }
        while (empty % 5 != emptyAt % 5) slide(if (empty % 5 < emptyAt % 5) empty + 1 else empty - 1)
        while (empty / 5 != emptyAt / 5) slide(if (empty / 5 < emptyAt / 5) empty + 5 else empty - 5)
        return board
    }

    // Tiles that look the same in the wiki picture (plain sky, say) can't be told apart, and it doesn't matter which
    // one goes where
    private fun sameLook(picture: Picture, a: Int, b: Int): Boolean {
        if (a == b) return true
        if (a == TileMatcher.EMPTY || b == TileMatcher.EMPTY) return false
        var sum = 0.0
        for (dy in 0 until 46) for (dx in 0 until 46) for (ch in 0..2) {
            val c = picture.rgb[ch]
            sum += abs(c[(18 + 50 * (a / 5) + dy) * picture.width + 19 + 50 * (a % 5) + dx] -
                c[(18 + 50 * (b / 5) + dy) * picture.width + 19 + 50 * (b % 5) + dx])
        }
        return sum / (46 * 46 * 3) < 1.0
    }

    // The fakes are only worth something if they look like the game: the Troll puzzle, drawn this way with the
    // layout of each screenshot (worked out by eye in PuzzleScreenshotsTest), matches the screenshot itself
    @Test
    fun fakeTrollMatchesTheRealScreenshots() {
        val troll = File(pictureDir, "Troll_puzzle_solved.png")
        assumeTrue(troll.isFile && shotsDir.isDirectory)
        val picture = loadPicture(troll)
        val layout1 = intArrayOf(0, 2, 8, 6, 13, 5, 1, 7, 9, 4, 10, 11, 17, 18, 3, 15, 16, 12, 23, 14, 20, 21, 22, 24, 19)
        val layout2 = intArrayOf(0, 2, 17, 8, 13, 5, 24, 7, 6, 4, 10, 1, 18, 9, 3, 15, 11, 16, 12, 14, 20, 21, 22, 23, 19)
        for (place in backgrounds) {
            val file = File(shotsDir, place.file)
            if (!file.isFile) continue
            val real = load(file)
            val fake = paint(real, place, arrange(picture, if (place.file == "Troll_1.png") layout1 else layout2, place.power))
            var sum = 0L; var n = 0
            for (y in TOP + 10..BOTTOM - 10) for (x in LEFT + 10..RIGHT - 10) for (shift in intArrayOf(0, 8, 16)) {
                sum += abs(((real.rgb(x, y) shr shift) and 0xFF) - ((fake.rgb(x, y) shr shift) and 0xFF)); n++
            }
            val average = sum.toDouble() / n
            println("${place.file}: the fake differs by ${"%.2f".format(average)} colour steps on average")
            assertEquals("${place.file}: fake looks like the real one", 0.0, average, 1.5)
        }
    }

    // Set PUZZLE_DUMP=<folder> to save the first few screens that went wrong, to look at
    private fun dump(screen: Screen, name: String) {
        val dir = System.getenv("PUZZLE_DUMP")?.let(::File) ?: return
        if (dir.listFiles().orEmpty().size >= 40) return
        val image = java.awt.image.BufferedImage(screen.width, screen.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, screen.width, screen.height, screen.pixels, 0, screen.width)
        dir.mkdirs()
        ImageIO.write(image, "png", File(dir, "${name.replace(' ', '_').replace("%", "pc")}.png"))
    }

    @Test
    fun readsEveryPuzzleAtEveryBrightness() {
        val shots = backgrounds.filter { File(shotsDir, it.file).isFile }
        val pictures = puzzles.associate { it.name to File(pictureDir, it.name.replace(' ', '_') + "_puzzle_solved.png") }
        assumeTrue("no screenshots", shots.isNotEmpty())
        assumeTrue("no wiki pictures (run tools/puzzle-box/get_test_pictures.py)", pictures.values.all { it.isFile })

        val problems = mutableListOf<String>()
        var screens = 0
        for (place in shots) {
            val background = load(File(shotsDir, place.file))
            // Where the solver finds the real Troll puzzle on this screenshot: the fakes sit in exactly the same place
            val realBox = PuzzleFinder.findBox(background)
            assertNotNull("${place.file}: the real puzzle is found", realBox)
            for (puzzle in puzzles) {
                val picture = loadPicture(pictures.getValue(puzzle.name))
                val random = Random(puzzle.name.hashCode())
                var wrongPuzzle = 0; var wrongTiles = 0; var notFound = 0; var wrongEmpty = 0; var fixed = 0
                // One board with the empty space in each of the 25 places, plus 5 nearly solved ones (like a re-plan)
                val boards = List(25) { shuffle(random, 300, it) } + List(5) { shuffle(random, 6, random.nextInt(25)) }
                for (board in boards) {
                    screens++
                    val screen = paint(background, place, arrange(picture, board, place.power))
                    val box = PuzzleFinder.findBox(screen)
                    if (box == null || abs(box.left - realBox!!.left) > 3 || abs(box.top - realBox.top) > 3 ||
                        abs(box.right - realBox.right) > 3 || abs(box.bottom - realBox.bottom) > 3) {
                        notFound++; dump(screen, "${puzzle.name}_${place.label}_notfound_$notFound"); continue
                    }
                    val match = TileMatcher.identify(screen.cut(box), box.width, box.height, puzzles)
                    if (match == null || match.puzzleName != puzzle.name) { wrongPuzzle++; continue }
                    if (match.correctedPositions.isNotEmpty()) fixed++
                    if ((0 until 25).any { !sameLook(picture, match.board[it], board[it]) }) {
                        wrongTiles++
                        if (wrongTiles == 1) problems += "${puzzle.name} at ${place.label}: read ${match.board.toList()}, " +
                            "was ${board.toList()}"
                    }
                    if (PuzzleFinder.emptySpace(screen, box) != board.indexOf(TileMatcher.EMPTY)) {
                        wrongEmpty++; dump(screen, "${puzzle.name}_${place.label}_empty_${board.indexOf(TileMatcher.EMPTY)}")
                    }
                }
                val summary = "${puzzle.name} at ${place.label} brightness: ${boards.size} boards, not found $notFound, " +
                    "wrong puzzle $wrongPuzzle, wrong tiles $wrongTiles, empty space missed $wrongEmpty " +
                    "(fixed by the solvable check: $fixed)"
                println(summary)
                if (notFound + wrongPuzzle + wrongTiles + wrongEmpty > 0) problems += summary
            }
        }
        println("$screens fake screens checked")
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
