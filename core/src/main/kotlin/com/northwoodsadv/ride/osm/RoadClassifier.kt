package com.northwoodsadv.ride.osm

import com.northwoodsadv.ride.world.Surface

enum class RoadKind(val label: String) {
    HIGHWAY("Highway"),
    MAJOR("Main road"),
    MINOR("Paved road"),
    SERVICE("Driveway / lot"),
    GRAVEL("Gravel road"),
    CLASS_VI("Class VI road"),
    TRAIL("Trail"),
}

class RoadSpec(
    val kind: RoadKind,
    val surface: Surface,
    val width: Double,
    /** 0 = smooth, 1 = boulder-strewn. Drives procedural ruts, rocks, washouts and water bars. */
    val roughness: Double,
    val name: String?,
    val bridge: Boolean = false,
    val ford: Boolean = false,
    val tunnel: Boolean = false,
    /** A New England covered bridge (bridge=covered), and the bridge's own name if it has one. */
    val covered: Boolean = false,
    val bridgeName: String? = null,
) {
    val isClassVI get() = kind == RoadKind.CLASS_VI || kind == RoadKind.TRAIL
}

/**
 * Turns OSM tags into something rideable.
 *
 * New Hampshire Class VI roads are legal public ways the towns no longer maintain. OSM mappers
 * tag them in several ways, so several signals count: highway=track, an explicit class tag with
 * value VI, maintenance tags, poor tracktype/smoothness, or "Class VI" in the name or note.
 */
object RoadClassifier {
    private val motorAllowed = setOf("yes", "permissive", "designated", "destination")

    fun classify(t: Map<String, String>): RoadSpec? {
        val hw = t["highway"] ?: return null
        val surfaceTag = t["surface"]
        val name = t["name"] ?: t["ref"]
        val bridge = t["bridge"].let { it != null && it != "no" }
        val ford = t["ford"] == "yes"
        val tunnel = t["tunnel"].let { it != null && it != "no" }

        val classVI = isClassVI(t)
        var kind = when (hw) {
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link" -> RoadKind.HIGHWAY
            "secondary", "secondary_link" -> RoadKind.MAJOR
            "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "road" -> RoadKind.MINOR
            "service" -> RoadKind.SERVICE
            "track" -> RoadKind.CLASS_VI
            "path", "bridleway" -> {
                val motor = listOf("motor_vehicle", "motorcycle", "atv", "ohv").any { t[it] in motorAllowed }
                if (!motor && !classVI) return null
                RoadKind.TRAIL
            }
            else -> return null
        }
        if (t["access"] in setOf("private", "no") && t["motorcycle"] !in motorAllowed && t["motor_vehicle"] !in motorAllowed &&
            kind == RoadKind.SERVICE) return null
        if (hw == "track" && t["tracktype"] == "grade1") kind = RoadKind.GRAVEL

        val surface = surfaceFromTag(surfaceTag) ?: when (kind) {
            RoadKind.CLASS_VI, RoadKind.TRAIL -> Surface.DIRT
            RoadKind.GRAVEL -> Surface.GRAVEL_PACKED
            else -> Surface.ASPHALT
        }
        if (classVI && kind != RoadKind.TRAIL) kind = RoadKind.CLASS_VI
        if (kind == RoadKind.MINOR || kind == RoadKind.SERVICE || kind == RoadKind.MAJOR) {
            if (!surface.isPaved) kind = RoadKind.GRAVEL
        }

        val roughness = roughness(t, kind, surface)
        val width = width(t, hw, kind)
        val covered = bridge && (t["bridge"] == "covered" || t["covered"] == "yes")
        val bridgeName = t["bridge:name"] ?: t["name"]?.takeIf { "bridge" in it.lowercase() }
        return RoadSpec(kind, surface, width, roughness, name, bridge, ford, tunnel, covered, bridgeName)
    }

    fun isClassVI(t: Map<String, String>): Boolean {
        for ((k, v) in t) {
            val kl = k.lowercase(); val vl = v.lowercase().trim()
            if (("class" in kl || "legal" in kl) && (vl == "vi" || vl == "6" || vl == "class vi" || vl == "class 6")) return true
            if ((kl == "name" || kl == "note" || kl == "description" || kl == "official_name" || kl == "alt_name") &&
                ("class vi" in vl || "class 6" in vl)) return true
        }
        if (t["maintained"] == "no" || t["maintenance"] == "no" || t["maintenance"] == "none") return true
        if (t["highway"] == "unclassified" || t["highway"] == "residential") {
            if (t["smoothness"] in setOf("very_bad", "horrible", "very_horrible", "impassable")) return true
            if (t["tracktype"] in setOf("grade3", "grade4", "grade5")) return true
        }
        return false
    }

    fun surfaceFromTag(s: String?): Surface? = when (s?.lowercase()?.substringBefore(';')) {
        null -> null
        "asphalt", "paved", "concrete", "concrete:plates", "paving_stones", "sett", "metal", "wood" -> Surface.ASPHALT
        "chipseal", "concrete:lanes", "cobblestone" -> Surface.ASPHALT_WORN
        "compacted", "fine_gravel" -> Surface.GRAVEL_PACKED
        "gravel", "pebblestone", "unpaved", "shells" -> Surface.GRAVEL_LOOSE
        "dirt", "earth", "ground", "soil" -> Surface.DIRT
        "mud" -> Surface.MUD
        "sand" -> Surface.SAND
        "rock", "stone" -> Surface.ROCK
        "grass", "grass_paver" -> Surface.GRASS
        "woodchips" -> Surface.FOREST
        else -> null
    }

    private fun roughness(t: Map<String, String>, kind: RoadKind, surface: Surface): Double {
        var r = when (kind) {
            RoadKind.HIGHWAY -> 0.02
            RoadKind.MAJOR -> 0.05
            RoadKind.MINOR -> 0.12 // frost heaves are a fact of life on NH back roads
            RoadKind.SERVICE -> 0.1
            RoadKind.GRAVEL -> 0.25
            RoadKind.CLASS_VI -> 0.6
            RoadKind.TRAIL -> 0.7
        }
        when (t["tracktype"]) {
            "grade2" -> r = maxOf(r, 0.35); "grade3" -> r = maxOf(r, 0.55)
            "grade4" -> r = maxOf(r, 0.75); "grade5" -> r = maxOf(r, 0.9)
        }
        when (t["smoothness"]) {
            "excellent", "good" -> r = minOf(r, 0.15)
            "intermediate" -> r = (r + 0.3) / 2
            "bad" -> r = maxOf(r, 0.55); "very_bad" -> r = maxOf(r, 0.75)
            "horrible" -> r = maxOf(r, 0.9); "very_horrible", "impassable" -> r = 1.0
        }
        if (surface == Surface.ROCK) r = maxOf(r, 0.7)
        return r.coerceIn(0.0, 1.0)
    }

    private fun width(t: Map<String, String>, hw: String, kind: RoadKind): Double {
        t["width"]?.removeSuffix("m")?.trim()?.toDoubleOrNull()?.let { if (it in 1.5..30.0) return it }
        val lanes = t["lanes"]?.toIntOrNull()
        val base = when {
            hw.startsWith("motorway") -> 11.0
            hw.startsWith("trunk") || hw.startsWith("primary") -> 7.6
            hw.startsWith("secondary") -> 7.0
            hw.startsWith("tertiary") -> 6.4
            kind == RoadKind.GRAVEL -> 4.8
            kind == RoadKind.CLASS_VI -> 3.2
            kind == RoadKind.TRAIL -> 1.8
            hw == "service" -> 3.6
            else -> 5.6
        }
        return if (lanes != null && lanes > 2 && !hw.startsWith("motorway")) lanes * 3.4 else base
    }
}
