package com.northwoodsadv.ride.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable

/**
 * Tiny immediate-mode UI for touch and mouse. Screen coordinates are pixels with y up.
 * Buttons fire on release inside the button they were pressed on.
 */
class Ui : InputAdapter(), Disposable {
    val batch = SpriteBatch()
    val shapes = ShapeRenderer()
    val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); setUseIntegerPositions(false) }
    val big = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); setUseIntegerPositions(false) }
    private val cam = OrthographicCamera()
    private val layout = GlyphLayout()
    var w = 0f; private set
    var h = 0f; private set
    /** Size unit: ~1/50 of the short screen side, so layouts scale from phones to monitors. */
    var u = 10f; private set

    // Pointer state.
    private val downX = FloatArray(10); private val downY = FloatArray(10)
    private val down = BooleanArray(10)
    private val releasedAt = ArrayList<FloatArray>()
    private var scrollDy = 0f

    fun resize(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        cam.setToOrtho(false, w, h)
        u = minOf(w, h) / 50f
        font.data.setScale((u / 7.5f).coerceAtLeast(1f))
        big.data.setScale((u / 3.2f).coerceAtLeast(2f))
    }

    fun begin() {
        cam.update()
        batch.projectionMatrix = cam.combined
        shapes.projectionMatrix = cam.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** Call at end of frame to forget this frame's releases. */
    fun endFrame() { releasedAt.clear(); scrollDy = 0f }

    fun rect(x: Float, y: Float, rw: Float, rh: Float, c: Color) {
        shapes.begin(ShapeRenderer.ShapeType.Filled); shapes.color = c; shapes.rect(x, y, rw, rh); shapes.end()
    }

    fun text(s: String, x: Float, y: Float, c: Color = Color.WHITE, align: Int = Align.left, f: BitmapFont = font, width: Float = 0f, wrap: Boolean = false) {
        batch.begin(); f.color = c; f.draw(batch, s, x, y, width, align, wrap); batch.end()
    }

    fun textWidth(s: String, f: BitmapFont = font): Float { layout.setText(f, s); return layout.width }
    fun lineHeight(f: BitmapFont = font) = f.lineHeight

    /** A labelled button. Returns true on the frame it is clicked. */
    fun button(label: String, x: Float, y: Float, bw: Float, bh: Float, active: Boolean = false, enabled: Boolean = true): Boolean {
        val pressed = pointerIn(x, y, bw, bh)
        val bg = when { !enabled -> DISABLED; pressed -> PRESSED; active -> ACTIVE; else -> BUTTON }
        Gdx.gl.glEnable(GL20.GL_BLEND)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = bg; shapes.rect(x, y, bw, bh)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = BORDER; shapes.rect(x, y, bw, bh)
        shapes.end()
        layout.setText(font, label, Color.WHITE, bw, Align.center, false)
        text(label, x, y + bh / 2 + layout.height / 2, if (enabled) Color.WHITE else Color.GRAY, Align.center, font, bw)
        return enabled && clicked(x, y, bw, bh)
    }

    fun clicked(x: Float, y: Float, bw: Float, bh: Float) = releasedAt.any { r ->
        r[0] in x..x + bw && r[1] in y..y + bh && r[2] in x..x + bw && r[3] in y..y + bh
    }

    /** True while any pointer that went down inside the rect is still held. */
    fun pointerIn(x: Float, y: Float, bw: Float, bh: Float): Boolean {
        for (p in 0 until 10) if (down[p] && downX[p] in x..x + bw && downY[p] in y..y + bh) return true
        return false
    }

    /**
     * Value 0..1 of a vertical slider for pointers that started inside it (bottom = 0, top = 1),
     * or -1 if not touched.
     */
    fun slider(x: Float, y: Float, sw: Float, sh: Float): Float {
        var v = -1f
        for (p in 0 until 10) {
            if (!down[p] || downX[p] !in x..x + sw || downY[p] !in y..y + sh) continue
            val py = h - Gdx.input.getY(p)
            v = maxOf(v, ((py - y) / sh).coerceIn(0f, 1f))
        }
        return v
    }

    /** Horizontal slider, -1..1, or NaN when not touched. */
    fun hslider(x: Float, y: Float, sw: Float, sh: Float): Float {
        for (p in 0 until 10) {
            if (!down[p] || downX[p] !in x..x + sw || downY[p] !in y..y + sh) continue
            val px = Gdx.input.getX(p).toFloat()
            return (((px - x) / sw) * 2 - 1).coerceIn(-1f, 1f)
        }
        return Float.NaN
    }

    val scroll get() = scrollDy

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer >= 10) return false
        down[pointer] = true; downX[pointer] = screenX.toFloat(); downY[pointer] = h - screenY
        return false
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer >= 10) return false
        if (down[pointer]) releasedAt += floatArrayOf(downX[pointer], downY[pointer], screenX.toFloat(), h - screenY)
        down[pointer] = false
        return false
    }

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer < 10) down[pointer] = false
        return false
    }

    override fun scrolled(amountX: Float, amountY: Float): Boolean { scrollDy += amountY; return false }

    override fun dispose() { batch.dispose(); shapes.dispose(); font.dispose(); big.dispose() }

    companion object {
        val BUTTON = Color(0.12f, 0.16f, 0.14f, 0.78f)
        val PRESSED = Color(0.35f, 0.45f, 0.3f, 0.9f)
        val ACTIVE = Color(0.2f, 0.42f, 0.25f, 0.85f)
        val DISABLED = Color(0.15f, 0.15f, 0.15f, 0.5f)
        val BORDER = Color(1f, 1f, 1f, 0.25f)
        val PANEL = Color(0.05f, 0.07f, 0.06f, 0.72f)
        val ACCENT = Color(0.95f, 0.55f, 0.15f, 1f)
    }
}
