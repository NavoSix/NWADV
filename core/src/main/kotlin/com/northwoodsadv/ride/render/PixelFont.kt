package com.northwoodsadv.ride.render

/**
 * A 5x7 pixel font for lettering road signs in 3D (street names, speed limits, landmark signs):
 * each lit run of pixels becomes one small quad, so a sign needs no texture.
 */
object PixelFont {
    const val W = 5
    const val H = 7

    private val glyphs: Map<Char, IntArray> = buildMap {
        fun g(c: Char, vararg rows: String) { put(c, IntArray(H) { r -> rows[r].foldIndexed(0) { i, acc, ch -> if (ch == '#') acc or (1 shl (W - 1 - i)) else acc } }) }
        g('A', " ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #")
        g('B', "#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### ")
        g('C', " ### ", "#   #", "#    ", "#    ", "#    ", "#   #", " ### ")
        g('D', "#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### ")
        g('E', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####")
        g('F', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    ")
        g('G', " ### ", "#   #", "#    ", "# ###", "#   #", "#   #", " ####")
        g('H', "#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #")
        g('I', " ### ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### ")
        g('J', "  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  ")
        g('K', "#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #")
        g('L', "#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####")
        g('M', "#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #")
        g('N', "#   #", "#   #", "##  #", "# # #", "#  ##", "#   #", "#   #")
        g('O', " ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ")
        g('P', "#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    ")
        g('Q', " ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #")
        g('R', "#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #")
        g('S', " ####", "#    ", "#    ", " ### ", "    #", "    #", "#### ")
        g('T', "#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ")
        g('U', "#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ")
        g('V', "#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  ")
        g('W', "#   #", "#   #", "#   #", "# # #", "# # #", "# # #", " # # ")
        g('X', "#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #")
        g('Y', "#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  ")
        g('Z', "#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####")
        g('0', " ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### ")
        g('1', "  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### ")
        g('2', " ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####")
        g('3', "#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### ")
        g('4', "   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # ")
        g('5', "#####", "#    ", "#### ", "    #", "    #", "#   #", " ### ")
        g('6', "  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### ")
        g('7', "#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   ")
        g('8', " ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### ")
        g('9', " ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  ")
        g('.', "     ", "     ", "     ", "     ", "     ", " ##  ", " ##  ")
        g('-', "     ", "     ", "     ", " ### ", "     ", "     ", "     ")
        g('\'', "  #  ", "  #  ", " #   ", "     ", "     ", "     ", "     ")
        g('&', " ##  ", "#  # ", "# #  ", " #   ", "# # #", "#  # ", " ## #")
        g('/', "    #", "    #", "   # ", "  #  ", " #   ", "#    ", "#    ")
        g('#', " # # ", " # # ", "#####", " # # ", "#####", " # # ", " # # ")
    }

    /** Width of [text] in pixels (one blank column between characters). */
    fun width(text: String) = if (text.isEmpty()) 0 else text.length * (W + 1) - 1

    /**
     * Calls [quad] (x0, y0, x1, y1 in pixel units, y up from the baseline) for every horizontal run
     * of lit pixels in [text]. Unknown characters are left blank; lower case is drawn as capitals.
     */
    inline fun runs(text: String, quad: (Int, Int, Int, Int) -> Unit) {
        var cx = 0
        for (ch in text) {
            val rows = glyph(ch)
            if (rows != null) for (r in 0 until H) {
                val bits = rows[r]
                var c = 0
                while (c < W) {
                    if (bits and (1 shl (W - 1 - c)) == 0) { c++; continue }
                    var e = c
                    while (e < W && bits and (1 shl (W - 1 - e)) != 0) e++
                    quad(cx + c, H - 1 - r, cx + e, H - r)
                    c = e
                }
            }
            cx += W + 1
        }
    }

    fun glyph(c: Char): IntArray? = glyphs[c.uppercaseChar()]

    /** Road names the way they appear on a street blade: "Bear Notch Road" -> "BEAR NOTCH RD". */
    fun signName(name: String): String {
        val abbrev = mapOf(
            "ROAD" to "RD", "STREET" to "ST", "HIGHWAY" to "HWY", "AVENUE" to "AVE", "DRIVE" to "DR",
            "LANE" to "LN", "TURNPIKE" to "TPKE", "MOUNTAIN" to "MTN", "NORTH" to "N", "SOUTH" to "S",
            "EAST" to "E", "WEST" to "W", "BOULEVARD" to "BLVD", "PLACE" to "PL", "CIRCLE" to "CIR",
            "COURT" to "CT", "EXTENSION" to "EXT", "TERRACE" to "TER", "PARKWAY" to "PKWY",
        )
        val words = name.uppercase().replace(Regex("[^A-Z0-9 .'&/#-]"), "").split(' ').filter { it.isNotEmpty() }
        // Only the last word is abbreviated as a suffix (North Road -> NORTH RD); directions lead.
        return words.mapIndexed { i, w ->
            if (i == words.size - 1 || w in setOf("NORTH", "SOUTH", "EAST", "WEST")) abbrev[w] ?: w else w
        }.joinToString(" ")
    }
}
