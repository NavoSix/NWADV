package com.northwoodsadv.ride

import java.io.File

/** Platform specific services supplied by the Android / desktop launchers. */
interface Platform {
    /** Directory for downloaded map data. Survives app restarts. */
    val mapCacheDir: File

    /** True on phones/tablets: shows on-screen controls and enables tilt steering. */
    val isTouchDevice: Boolean

    /**
     * Ask the user for a GPX file. [onResult] is called on the render thread with the file
     * name and contents, or with nulls if the user cancelled.
     */
    fun pickGpxFile(onResult: (name: String?, contents: String?) -> Unit)

    /** A GPX file handed to the app by the OS (e.g. "Open with" from a file manager), consumed once. */
    fun takePendingGpx(): Pair<String, String>? = null

    /** Short vibration for crashes / hard landings, where supported. */
    fun vibrate(millis: Int) {}
}
