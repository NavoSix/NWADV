package com.northwoodsadv.ride.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.northwoodsadv.ride.physics.Motorcycle
import com.northwoodsadv.ride.world.Ground
import com.northwoodsadv.ride.world.GroundSample
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Wake and spray when the bike rides through water: foam rings spread from each wheel on the
 * water surface (together they draw the V of a wake) and droplets fly off the front wheel.
 * Positions are world metres; drawn each frame as one small dynamic mesh.
 */
class WakeFx(private val ground: Ground) : Disposable {
    private class Ring(var x: Double, var y: Double, var level: Double, var age: Float, var life: Float, var grow: Float, var strength: Float)
    private class Drop(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double, var age: Float, var life: Float, var size: Float)

    private val rings = ArrayList<Ring>()
    private val drops = ArrayList<Drop>()
    private val rnd = Random(3)
    private val gs = GroundSample()
    private var ringTimer = 0.0
    private val mb = MeshBuilder(2048)
    private var mesh: Mesh? = null
    private var capacity = 0

    fun update(dt: Double, bike: Motorcycle) {
        for (r in rings) r.age += dt.toFloat()
        rings.removeAll { it.age >= it.life }
        for (d in drops) {
            d.age += dt.toFloat()
            d.vz -= 9.81 * dt
            d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
        }
        drops.removeAll { it.age >= it.life }

        val speed = bike.speed
        if (bike.airborne || speed < 0.3) return
        val ch = cos(bike.yaw); val sh = sin(bike.yaw)
        ringTimer -= dt
        val spawnRing = ringTimer <= 0
        if (spawnRing) ringTimer = (0.11 - min(speed, 15.0) * 0.004).coerceAtLeast(0.05)
        for (wheel in 0..1) {
            val off = if (wheel == 0) bike.spec.cgToFront else -bike.spec.cgToRear
            val wx = bike.x + ch * off; val wy = bike.y + sh * off
            ground.sample(wx, wy, gs, false)
            val level = gs.waterLevel
            if (level.isNaN() || gs.height > level - 0.02) continue
            val depth = min(level - gs.height, 0.6)
            if (spawnRing && rings.size < MAX_RINGS)
                rings += Ring(wx, wy, level, 0f, 1.6f + rnd.nextFloat() * 0.6f, (0.6 + speed * 0.12).toFloat(), (0.5 + depth * 1.2).toFloat().coerceAtMost(1f))
            if (wheel == 0) {
                // Spray: a fan off each side of the front tyre, more with speed and depth.
                val n = (speed * depth * 60 * dt + rnd.nextDouble()).toInt().coerceAtMost(12)
                repeat(n) {
                    if (drops.size >= MAX_DROPS) return@repeat
                    val side = if (rnd.nextBoolean()) 1.0 else -1.0
                    val lat = (0.6 + rnd.nextDouble() * 1.4) * min(speed, 12.0) * 0.25 * side
                    val fwd = speed * (0.35 + rnd.nextDouble() * 0.35)
                    drops += Drop(wx, wy, level, ch * fwd - sh * lat, sh * fwd + ch * lat, 1.0 + rnd.nextDouble() * (1.0 + speed * 0.25),
                        0f, 0.5f + rnd.nextFloat() * 0.5f, (0.04 + rnd.nextDouble() * 0.05).toFloat())
                }
            }
        }
    }

    fun render(shader: ShaderProgram, originE: Double, originN: Double, baseH: Double) {
        if (rings.isEmpty() && drops.isEmpty()) return
        mb.clear()
        for (r in rings) {
            val t = r.age / r.life
            val rad = 0.25f + r.grow * r.age
            val w = 0.10f + 0.25f * t
            val a = r.strength * 0.6f * (1 - t) * (1 - t)
            val inner = MeshBuilder.color(0.92f, 0.94f, 0.92f, 0f)
            val mid = MeshBuilder.color(0.92f, 0.94f, 0.92f, a)
            val x = (r.x - originE).toFloat(); val z = (-(r.y - originN)).toFloat(); val y = (r.level - baseH + 0.015).toFloat()
            val first = mb.vertCount
            for (i in 0..SEG) {
                val ang = i * MeshBuilder.TWO_PI / SEG
                val c = cos(ang); val s = sin(ang)
                mb.vertex(x + c * (rad - w), y, z + s * (rad - w), 0f, 1f, 0f, inner)
                mb.vertex(x + c * rad, y, z + s * rad, 0f, 1f, 0f, mid)
                mb.vertex(x + c * (rad + w), y, z + s * (rad + w), 0f, 1f, 0f, inner)
            }
            for (i in 0 until SEG) {
                val p = first + i * 3; val q = p + 3
                mb.quad(p, q, q + 1, p + 1); mb.quad(p + 1, q + 1, q + 2, p + 2)
            }
        }
        for (d in drops) {
            val a = 0.8f * (1 - d.age / d.life)
            val c = MeshBuilder.color(0.9f, 0.93f, 0.95f, a)
            val s = d.size * (1 + d.age)
            val x = (d.x - originE).toFloat(); val y = (d.z - baseH).toFloat(); val z = (-(d.y - originN)).toFloat()
            // Two crossed quads read as a droplet from any side.
            val v0 = mb.vertex(x - s, y - s, z, 0f, 1f, 0f, c); val v1 = mb.vertex(x + s, y - s, z, 0f, 1f, 0f, c)
            val v2 = mb.vertex(x + s, y + s, z, 0f, 1f, 0f, c); val v3 = mb.vertex(x - s, y + s, z, 0f, 1f, 0f, c)
            mb.quad(v0, v1, v2, v3)
            val w0 = mb.vertex(x, y - s, z - s, 0f, 1f, 0f, c); val w1 = mb.vertex(x, y - s, z + s, 0f, 1f, 0f, c)
            val w2 = mb.vertex(x, y + s, z + s, 0f, 1f, 0f, c); val w3 = mb.vertex(x, y + s, z - s, 0f, 1f, 0f, c)
            mb.quad(w0, w1, w2, w3)
        }
        if (mb.vertCount > capacity) {
            mesh?.dispose()
            capacity = max(mb.vertCount, 1024) * 2
            mesh = Mesh(false, capacity, capacity * 2, ChunkManager.ATTRS)
        }
        val m = mesh ?: return
        m.setVertices(mb.verts, 0, mb.vertCount * MeshBuilder.STRIDE)
        m.setIndices(mb.idx, 0, mb.idxCount)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glDisable(GL20.GL_CULL_FACE)
        shader.setUniformf("u_mode", ChunkManager.MODE_FX)
        shader.setUniformf("u_offset", 0f, 0f, 0f)
        m.render(shader, GL20.GL_TRIANGLES, 0, mb.idxCount)
        shader.setUniformf("u_mode", ChunkManager.MODE_OBJECT)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dispose() { mesh?.dispose() }

    companion object {
        private const val SEG = 18
        private const val MAX_RINGS = 60
        private const val MAX_DROPS = 260
    }
}
