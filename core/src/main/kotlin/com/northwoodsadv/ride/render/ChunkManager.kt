package com.northwoodsadv.ride.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.utils.Disposable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Streams terrain around the camera with a quadtree of detail levels (128 m chunks at 2 m
 * resolution near the rider, up to 2 km chunks at 32 m on the horizon). Chunk geometry is built
 * on worker threads and uploaded a few per frame.
 */
class ChunkManager(private val builder: ChunkBuilder, threads: Int) : Disposable {
    private class Job(val key: Long, val level: Int, val cx: Int, val cy: Int, val priority: Double, val seq: Long) : Comparable<Job> {
        override fun compareTo(other: Job) = compareValuesBy(this, other, { it.priority }, { it.seq })
    }

    class Chunk(val data: ChunkData, val terrain: Mesh, val roads: Mesh?, val trees: Mesh?, val bounds: BoundingBox) {
        var lastNeeded = 0L
        fun dispose() { terrain.dispose(); roads?.dispose(); trees?.dispose() }
    }

    private val ready = HashMap<Long, Chunk>()
    private val queued = HashSet<Long>()
    private val jobs = PriorityBlockingQueue<Job>()
    private val done = ConcurrentLinkedQueue<ChunkData>()
    private val seq = AtomicLong()
    @Volatile private var running = true
    private val workers = List(threads) { i ->
        Thread({
            while (running) {
                val j = try { jobs.take() } catch (_: InterruptedException) { break }
                if (!running) break
                try { done.add(builder.build(j.level, j.cx, j.cy)) } catch (e: Exception) {
                    Gdx.app?.error("ChunkManager", "chunk build failed", e)
                }
            }
        }, "chunk-builder-$i").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private var needed = ArrayList<Long>()
    private var frame = 0L
    private var lastSelectE = Double.NaN; private var lastSelectN = Double.NaN
    val pending get() = queued.size
    val chunkCount get() = ready.size

    private fun key(level: Int, cx: Int, cy: Int) = (level.toLong() shl 56) or ((cx.toLong() and 0xfffffff) shl 28) or (cy.toLong() and 0xfffffff)

    /** Re-select needed chunks around the camera (east/north in world metres). */
    fun update(camE: Double, camN: Double, viewDist: Double) {
        frame++
        if (lastSelectE.isNaN() || (camE - lastSelectE).let { it * it } + (camN - lastSelectN).let { it * it } > 64.0) {
            lastSelectE = camE; lastSelectN = camN
            val sel = ArrayList<Long>()
            val top = MAX_LEVEL
            val size = ChunkBuilder.BASE * (1 shl top)
            val r = (viewDist / size).toInt() + 1
            val ccx = floor(camE / size).toInt(); val ccy = floor(camN / size).toInt()
            for (cy in ccy - r..ccy + r) for (cx in ccx - r..ccx + r) select(top, cx, cy, camE, camN, viewDist, sel)
            needed = sel
            for (k in sel) if (!ready.containsKey(k) && queued.add(k)) {
                val level = (k ushr 56).toInt()
                val cx = ((k ushr 28) and 0xfffffff).toInt().let { if (it and 0x8000000 != 0) it or 0xf0000000.toInt() else it }
                val cy = (k and 0xfffffff).toInt().let { if (it and 0x8000000 != 0) it or 0xf0000000.toInt() else it }
                val s = ChunkBuilder.BASE * (1 shl level)
                val d = dist(camE, camN, cx * s, cy * s, s)
                jobs.add(Job(k, level, cx, cy, d - level * 40.0, seq.incrementAndGet()))
            }
        }
        // Upload a few finished chunks per frame.
        var uploads = 0
        while (uploads < 4) {
            val d = done.poll() ?: break
            val k = key(d.level, d.cx, d.cy)
            queued.remove(k)
            ready.remove(k)?.dispose()
            ready[k] = upload(d)
            uploads++
        }
        for (k in needed) ready[k]?.lastNeeded = frame
        // Retire chunks that haven't been needed for a while, once their area is covered.
        if (frame % 30 == 0L) {
            val it = ready.entries.iterator()
            while (it.hasNext()) {
                val (_, c) = it.next()
                if (frame - c.lastNeeded > 90 && queued.isEmpty()) { c.dispose(); it.remove() }
                else if (frame - c.lastNeeded > 600) { c.dispose(); it.remove() }
            }
        }
    }

    private fun dist(px: Double, py: Double, x0: Double, y0: Double, s: Double): Double {
        val dx = max(0.0, max(x0 - px, px - (x0 + s))); val dy = max(0.0, max(y0 - py, py - (y0 + s)))
        return sqrt(dx * dx + dy * dy)
    }

    private fun select(level: Int, cx: Int, cy: Int, e: Double, n: Double, viewDist: Double, out: ArrayList<Long>) {
        val s = ChunkBuilder.BASE * (1 shl level)
        val d = dist(e, n, cx * s, cy * s, s)
        if (d > viewDist) return
        if (level > 0 && d < s * SPLIT) {
            for (dy in 0..1) for (dx in 0..1) select(level - 1, cx * 2 + dx, cy * 2 + dy, e, n, viewDist, out)
        } else out += key(level, cx, cy)
    }

    private fun upload(d: ChunkData): Chunk {
        val t = toMesh(d.terrain)
        val r = d.roads?.let(::toMesh)
        val tr = d.trees?.let(::toMesh)
        var minY = d.terrain.minY; var maxY = d.terrain.maxY
        d.trees?.let { maxY = max(maxY, it.maxY) }
        if (minY > maxY) { minY = 0f; maxY = 0f }
        val bb = BoundingBox(Vector3(0f, minY, -d.size.toFloat()), Vector3(d.size.toFloat(), maxY, 0f))
        return Chunk(d, t, r, tr, bb)
    }

    private fun toMesh(m: MeshBuilder): Mesh {
        val mesh = Mesh(true, m.vertCount, m.idxCount, ATTRS)
        mesh.setVertices(m.verts, 0, m.vertCount * MeshBuilder.STRIDE)
        mesh.setIndices(m.idx, 0, m.idxCount)
        return mesh
    }

    private val tmpBox = BoundingBox()
    private val tmpMin = Vector3(); private val tmpMax = Vector3()
    var drawn = 0; private set

    /**
     * Draw everything needed this frame (falling back to whatever coarser/finer chunk is ready
     * while new detail loads). originE/originN is the render origin in world metres.
     */
    fun render(shader: ShaderProgram, cam: PerspectiveCamera, originE: Double, originN: Double) {
        drawn = 0
        val visible = HashSet<Long>()
        for (k in needed) {
            if (ready.containsKey(k)) { visible += k; continue }
            // Fallback: nearest ready ancestor.
            var level = (k ushr 56).toInt()
            var cx = ((k ushr 28) and 0xfffffff).toInt().let { if (it and 0x8000000 != 0) it or 0xf0000000.toInt() else it }
            var cy = (k and 0xfffffff).toInt().let { if (it and 0x8000000 != 0) it or 0xf0000000.toInt() else it }
            while (level < MAX_LEVEL) {
                level++; cx = Math.floorDiv(cx, 2); cy = Math.floorDiv(cy, 2)
                val pk = key(level, cx, cy)
                if (ready.containsKey(pk)) { visible += pk; break }
            }
        }
        Gdx.gl.glEnable(GL20.GL_CULL_FACE)
        for (pass in 0..2) {
            if (pass == 1) { Gdx.gl.glDisable(GL20.GL_CULL_FACE); Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL); Gdx.gl.glPolygonOffset(-1f, -2f) }
            if (pass == 2) { Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL); Gdx.gl.glEnable(GL20.GL_CULL_FACE) }
            for (k in visible) {
                val c = ready[k] ?: continue
                val ox = (c.data.e0 - originE).toFloat(); val oz = (-(c.data.n0 - originN)).toFloat()
                tmpMin.set(c.bounds.min).add(ox, 0f, oz); tmpMax.set(c.bounds.max).add(ox, 0f, oz)
                tmpBox.set(tmpMin, tmpMax)
                if (!cam.frustum.boundsInFrustum(tmpBox)) continue
                val mesh = when (pass) { 0 -> c.terrain; 1 -> c.roads; else -> c.trees } ?: continue
                shader.setUniformf("u_offset", ox, 0f, oz)
                mesh.render(shader, GL20.GL_TRIANGLES)
                if (pass == 0) drawn++
            }
        }
        Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL)
    }

    /** Throw everything away (e.g. after changing the season). */
    fun clear() {
        jobs.clear(); queued.clear()
        for (c in ready.values) c.dispose()
        ready.clear()
        while (done.poll() != null) {}
        lastSelectE = Double.NaN
    }

    override fun dispose() {
        running = false
        workers.forEach { it.interrupt() }
        clear()
    }

    companion object {
        const val MAX_LEVEL = 4
        const val SPLIT = 1.25
        val ATTRS = VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked())
    }
}
