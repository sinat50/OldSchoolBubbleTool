package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.PanelFinder.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Runs the Puzzle Box Solver on the owner's screenshots of a real puzzle box, which aren't part of the project:
// they're in Documents\Androiddev\puzzle_shots (or set PUZZLE_SHOTS=<folder>), described in its notes.md.
// Skipped without them. Taken at the game's lowest, 50% and highest brightness (November 2026). Each expected
// layout was checked by eye: the screenshot's tiles, put back where the layout says they belong, make the whole
// troll picture.
class PuzzleScreenshotsTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF

        // The tiles cut out, as TileMatcher gets them
        fun pixels(area: Box): IntArray = IntArray(area.width * area.height) { i ->
            image.getRGB(area.left + i % area.width, area.top + i / area.width)
        }
    }

    private val dir = File(System.getenv("PUZZLE_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\puzzle_shots")
    private val puzzles by lazy { TileMatcher.readPuzzles(File("src/main/assets/puzzle_tiles.bin").readBytes()) }

    // board[place] = the tile there (0-24, left to right, top to bottom; 24 = the empty space)
    private val layout1 = intArrayOf(0, 2, 8, 6, 13, 5, 1, 7, 9, 4, 10, 11, 17, 18, 3, 15, 16, 12, 23, 14, 20, 21, 22, 24, 19)
    private val layout2 = intArrayOf(0, 2, 17, 8, 13, 5, 24, 7, 6, 4, 10, 1, 18, 9, 3, 15, 11, 16, 12, 14, 20, 21, 22, 23, 19)
    private val layout5 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 12, 9, 13, 10, 11, 22, 17, 7, 15, 16, 8, 18, 14, 20, 21, 23, 24, 19)
    private val layout6 = intArrayOf(0, 1, 2, 3, 4, 11, 5, 7, 14, 8, 6, 18, 23, 13, 9, 10, 12, 15, 19, 21, 20, 16, 17, 24, 22)

    // Screenshot, its layout, and whether the scan should find it (Troll_5 has the guide's outlines drawn over
    // the lines between tiles, which the app hides while it scans, so there it's given the tiles' place by hand)
    private val shots = listOf(
        Triple("Troll_1", layout1, true),    // 50%
        Triple("Troll_2", layout2, true),    // 50%
        Triple("Troll_3", layout2, true),    // lowest
        Triple("Troll_4", layout2, true),    // highest
        Triple("Troll_5", layout5, false),   // 50%, guiding; the app solved it from this layout
        Triple("Troll_6", layout6, true),    // lowest, the app's window open beside it
        Triple("Troll_7", layout6, true)     // highest, the app's window open beside it
    )

    @Test
    fun findsAndReadsThePuzzleAtEveryBrightness() {
        assumeTrue(dir.isDirectory)
        var checked = 0
        for ((name, layout, findable) in shots) {
            val file = File(dir, "$name.png")
            if (!file.isFile) continue
            val shot = Shot(file)
            val area = if (findable) PuzzleFinder.findBox(shot) else Box(709, 401, 1232, 924)
            assertNotNull("$name: puzzle found", area)
            area!!
            // the tiles are about 530 pixels across on the owner's phone
            assertTrue("$name: size ${area.width} x ${area.height}", area.width in 500..560 && area.height in 500..560)

            val match = TileMatcher.identify(shot.pixels(area), area.width, area.height, puzzles)!!
            assertEquals("$name: which puzzle", "Troll", match.puzzleName)
            assertEquals("$name: layout", layout.toList(), match.board.toList())

            // while guiding: the empty space is spotted, and the frame counts as still there
            assertEquals("$name: empty space", layout.indexOf(TileMatcher.EMPTY), PuzzleFinder.emptySpace(shot, area))
            assertTrue("$name: frame there", PuzzleFinder.frameVisible(shot, area, PuzzleFinder.frameColours(shot, area)))
            checked++
        }
        assumeTrue(checked > 0)
        println("Checked $checked puzzle screenshots")
    }

    // Once the puzzle is closed, the frame's spots show something else: here, a screenshot of the Path of Het
    @Test
    fun noticesWhenThePuzzleIsGone() {
        val open = File(dir, "Troll_1.png")
        val other = otherScreenshots().firstOrNull()
        assumeTrue(open.isFile && other != null)
        val shot = Shot(open)
        val area = PuzzleFinder.findBox(shot)!!
        assertFalse(PuzzleFinder.frameVisible(Shot(other!!), area, PuzzleFinder.frameColours(shot, area)))
    }

    // Screenshots with no puzzle box in them (Path of Het, Gear Loadouts): nothing is found
    @Test
    fun findsNothingWithoutAPuzzle() {
        val others = otherScreenshots()
        assumeTrue(others.isNotEmpty())
        for (file in others) assertNull(file.name, PuzzleFinder.findBox(Shot(file)))
        println("No puzzle found in ${others.size} other screenshots")
    }

    private fun otherScreenshots(): List<File> = listOf(
        "C:\\Users\\Tanis\\Documents\\Androiddev\\OSRSBubbleTool\\pathofhet",
        "C:\\Users\\Tanis\\Documents\\Androiddev\\loadout_shots"
    ).map(::File).filter { it.isDirectory }
        .flatMap { d -> d.listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }.take(20) }
}
