package com.northwoodsadv.ride.geo

import com.badlogic.gdx.utils.XmlReader

/** A named list of points from a GPX track, route or the waypoints. */
class GpxTrack(val name: String, val points: List<GeoPoint>)

class GpxWaypoint(val name: String, val point: GeoPoint)

class GpxFile(val name: String, val tracks: List<GpxTrack>, val waypoints: List<GpxWaypoint>) {
    /**
     * The route to ride: every track segment and route concatenated in file order. Club GPX
     * files are usually a single track, but files exported from planners often hold a <rte>.
     */
    val ridePoints: List<GeoPoint> by lazy { tracks.flatMap { it.points } }
}

class GpxParseException(msg: String) : Exception(msg)

/** Tolerant GPX 1.0/1.1 reader (tracks, routes and waypoints; namespaces ignored). */
object GpxParser {
    fun parse(text: String, fallbackName: String = "Route"): GpxFile {
        val root = try {
            XmlReader().parse(text.trimStart('﻿', ' ', '\n', '\r', '\t'))
        } catch (e: Exception) {
            throw GpxParseException("Not a readable GPX file: ${e.message}")
        } ?: throw GpxParseException("Empty GPX file")
        if (local(root.name) != "gpx") throw GpxParseException("Root element is <${root.name}>, expected <gpx>")

        val tracks = ArrayList<GpxTrack>()
        val waypoints = ArrayList<GpxWaypoint>()
        var fileName: String? = null

        for (i in 0 until root.childCount) {
            val el = root.getChild(i)
            when (local(el.name)) {
                "metadata" -> fileName = childText(el, "name") ?: fileName
                "trk" -> {
                    val name = childText(el, "name") ?: "Track ${tracks.size + 1}"
                    val pts = ArrayList<GeoPoint>()
                    for (s in 0 until el.childCount) {
                        val seg = el.getChild(s)
                        if (local(seg.name) != "trkseg") continue
                        for (p in 0 until seg.childCount) point(seg.getChild(p), "trkpt")?.let(pts::add)
                    }
                    if (pts.size >= 2) tracks += GpxTrack(name, dedupe(pts))
                }
                "rte" -> {
                    val name = childText(el, "name") ?: "Route ${tracks.size + 1}"
                    val pts = ArrayList<GeoPoint>()
                    for (p in 0 until el.childCount) point(el.getChild(p), "rtept")?.let(pts::add)
                    if (pts.size >= 2) tracks += GpxTrack(name, dedupe(pts))
                }
                "wpt" -> point(el, "wpt")?.let { waypoints += GpxWaypoint(childText(el, "name") ?: "Waypoint", it) }
            }
        }
        if (tracks.isEmpty()) throw GpxParseException("The GPX file has no track or route with at least two points")
        return GpxFile(fileName ?: tracks.first().name.ifBlank { fallbackName }, tracks, waypoints)
    }

    private fun point(el: XmlReader.Element, tag: String): GeoPoint? {
        if (local(el.name) != tag) return null
        val lat = el.getAttribute("lat", null)?.toDoubleOrNull() ?: return null
        val lon = el.getAttribute("lon", null)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val ele = childText(el, "ele")?.toDoubleOrNull() ?: Double.NaN
        return GeoPoint(lat, lon, ele)
    }

    /** Drops consecutive points closer than half a metre (GPS jitter while stopped). */
    private fun dedupe(pts: List<GeoPoint>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>(pts.size)
        for (p in pts) if (out.isEmpty() || out.last().distanceTo(p) > 0.5) out += p
        return out
    }

    private fun childText(el: XmlReader.Element, name: String): String? {
        for (i in 0 until el.childCount) {
            val c = el.getChild(i)
            if (local(c.name) == name) return c.text?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun local(name: String) = name.substringAfter(':').lowercase()
}
