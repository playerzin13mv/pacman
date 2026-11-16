package com.pacmanmobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Desenha tudo numa tela lógica de 224x288 (como o arcade) e escala para o celular
 * sem suavização, para manter o pixel art nítido.
 */
class GameView(ctx: Context) : View(ctx) {

    private val sfx = Sfx(ctx)
    private val game = Game(sfx, ctx.getSharedPreferences("pacman", Context.MODE_PRIVATE))

    private val sprites: Bitmap
    private val mazeBmp: Bitmap

    private val bmpPaint = Paint()
    private val whitePaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private var last = 0L
    private var paused = false

    private var sx = 0f
    private var sy = 0f
    private var moved = false
    private val swipeThr = resources.displayMetrics.density * 20f

    private val dotColor = Color.rgb(255, 183, 174)
    private val yellow = Color.rgb(255, 255, 0)

    init {
        val o = BitmapFactory.Options()
        o.inScaled = false
        sprites = ctx.assets.open("sprites.png").use { BitmapFactory.decodeStream(it, null, o)!! }
        mazeBmp = ctx.assets.open("maze.png").use { BitmapFactory.decodeStream(it, null, o)!! }
        bmpPaint.isFilterBitmap = false
        bmpPaint.isAntiAlias = false
        whitePaint.isFilterBitmap = false
        whitePaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setBackgroundColor(Color.BLACK)
    }

    fun onPauseGame() {
        paused = true
        sfx.stopLoop()
    }

    fun onResumeGame() {
        paused = false
        last = 0L
        invalidate()
    }

    fun release() {
        sfx.release()
    }

    // ------------------------------------------------------------ toque

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - sx
                val dy = e.y - sy
                if (max(abs(dx), abs(dy)) > swipeThr) {
                    val d = if (abs(dx) > abs(dy)) {
                        if (dx > 0) Dir.R else Dir.L
                    } else {
                        if (dy > 0) Dir.D else Dir.U
                    }
                    game.swipe(d)
                    sx = e.x
                    sy = e.y
                    moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) game.tap()
            }
        }
        return true
    }

    // ------------------------------------------------------------ desenho

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
        last = now
        if (dt > 0.05f) dt = 0.05f
        if (!paused) game.update(dt)

        c.drawColor(Color.BLACK)
        val s = min(width / 224f, height / 288f)
        val ox = (width - 224f * s) / 2f
        val oy = (height - 288f * s) / 2f
        c.save()
        c.translate(ox, oy)
        c.scale(s, s)
        drawWorld(c)
        c.restore()

        if (!paused) postInvalidateOnAnimation()
    }

    /** Desenha a célula 16x16 (col,row) da sprite sheet; hx/hy = ponto central dentro da célula. */
    private fun spr(c: Canvas, col: Int, row: Int, cx: Float, cy: Float, hx: Float = 8f, hy: Float = 8f) {
        src.set(col * 16, row * 16, col * 16 + 16, row * 16 + 16)
        val l = Math.round(cx - hx).toFloat()
        val t = Math.round(cy - hy).toFloat()
        dst.set(l, t, l + 16f, t + 16f)
        c.drawBitmap(sprites, src, dst, bmpPaint)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float, align: Paint.Align) {
        text.color = color
        text.textSize = size
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    private fun drawHud(c: Canvas) {
        val g = game
        val white = Color.WHITE
        if (g.state != Game.S.PLAY || ((g.time * 2f).toInt() and 1) == 0) {
            label(c, "1UP", 24f, 10f, white, 13f, Paint.Align.LEFT)
        }
        label(c, g.score.toString(), 64f, 21f, white, 13f, Paint.Align.RIGHT)
        label(c, "HIGH SCORE", 112f, 10f, white, 13f, Paint.Align.CENTER)
        label(c, g.hi.toString(), 112f, 21f, white, 13f, Paint.Align.CENTER)

        // vidas
        if (g.state != Game.S.TITLE) {
            val n = max(0, g.lives - 1)
            for (i in 0 until min(n, 5)) spr(c, 1, 1, 16f + i * 16f, 280f, 8.5f, 7.5f)
            // frutas dos últimos níveis
            val from = max(1, g.level - 6)
            var k = 0
            for (l in g.level downTo from) {
                spr(c, 3 + g.fruitIdx(l), 3, 208f - k * 16f, 280f)
                k++
            }
        }
    }

    private fun drawWorld(c: Canvas) {
        val g = game
        val t = g.time
        drawHud(c)

        val flashWhite = g.state == Game.S.CLEAR && g.stateT > 1.0f &&
            (((g.stateT - 1.0f) / 0.27f).toInt() % 2 == 1)
        c.drawBitmap(mazeBmp, 0f, 24f, if (flashWhite) whitePaint else bmpPaint)

        // bolinhas e pílulas
        fill.color = dotColor
        fill.isAntiAlias = false
        val blink = ((t / 0.2f).toInt() % 2 == 0)
        for (y in 0 until Maze.H) {
            for (x in 0 until Maze.W) {
                if (!g.dots[y][x]) continue
                val px = x * 8f
                val py = 24f + y * 8f
                if (g.pellets[y][x]) {
                    if (blink) {
                        fill.isAntiAlias = true
                        c.drawCircle(px + 4f, py + 4f, 3.6f, fill)
                        fill.isAntiAlias = false
                    }
                } else {
                    c.drawRect(px + 3f, py + 3f, px + 5f, py + 5f, fill)
                }
            }
        }

        c.save()
        c.clipRect(0f, 24f, 224f, 272f)

        val showGhosts = g.state == Game.S.READY || g.state == Game.S.PLAY || g.state == Game.S.PAC_HIT
        val showPac = showGhosts || g.state == Game.S.CLEAR

        // fruta
        if (g.fruitT > 0f && g.state == Game.S.PLAY) {
            spr(c, 3 + g.fruitIdx(g.level), 3, 112f, 164f)
        }

        if (showGhosts) {
            for (gh in g.ghosts) {
                val cx = gh.x() * 8f
                var cy = 24f + gh.y() * 8f
                var dirForSprite = gh.dir
                if (gh.state == GState.WAIT) {
                    val w = sin(t * 9f + gh.id)
                    cy += w * 3f
                    dirForSprite = if (w > 0f) Dir.D else Dir.U
                }
                val anim = (t * 8f).toInt() and 1
                if (gh.state == GState.EYES) {
                    spr(c, 8 + gh.dir, 5, cx, cy)
                } else if (gh.frightened) {
                    val white = g.flashing() && ((t * 6f).toInt() and 1) == 1
                    spr(c, 8 + (if (white) 2 else 0) + anim, 4, cx, cy)
                } else {
                    val base = intArrayOf(0, 2, 4, 6)[dirForSprite]
                    spr(c, base + anim, 4 + gh.id, cx, cy)
                }
            }
        }

        if (showPac) {
            val cx = g.pac.x() * 8f
            val cy = 24f + g.pac.y() * 8f
            val col = when {
                g.state == Game.S.READY -> 2
                g.pac.moving -> intArrayOf(1, 0, 1, 2)[(g.pac.animT * 30f).toInt() and 3]
                else -> 1
            }
            val d = g.pac.dir
            spr(c, col, d, cx, cy, if (d == Dir.L) 8.5f else 7.5f, if (d == Dir.U) 8.5f else 7.5f)
        }

        if (g.state == Game.S.DYING) {
            val cx = g.pac.x() * 8f
            val cy = 24f + g.pac.y() * 8f
            val idx = (g.stateT / 0.14f).toInt()
            if (idx <= 9) {
                spr(c, 3 + idx, 0, cx, cy, 7.5f, 7.5f)
            } else if (g.stateT < 1.7f) {
                spr(c, 13, 0, cx, cy, 7.5f, 7.5f)
            }
        }

        // pontos que aparecem ao comer fantasma/fruta
        for (p in g.popups) {
            label(c, p.text, p.x * 8f, 24f + p.y * 8f + 3f, Color.CYAN, 9f, Paint.Align.CENTER)
        }
        c.restore()

        // mensagens
        if (g.state == Game.S.READY) {
            label(c, "READY!", 112f, 168f, yellow, 13f, Paint.Align.CENTER)
        }
        if (g.state == Game.S.OVER) {
            label(c, "GAME  OVER", 112f, 168f, Color.RED, 13f, Paint.Align.CENTER)
            if (g.stateT > 1.2f && ((t * 2f).toInt() and 1) == 0) {
                label(c, "TOQUE PARA CONTINUAR", 112f, 196f, Color.WHITE, 9f, Paint.Align.CENTER)
            }
        }
        if (g.state == Game.S.TITLE) drawTitle(c)
    }

    private fun drawTitle(c: Canvas) {
        val t = game.time
        fill.color = Color.argb(185, 0, 0, 0)
        c.drawRect(0f, 24f, 224f, 272f, fill)
        label(c, "PAC-MAN", 112f, 110f, yellow, 30f, Paint.Align.CENTER)

        val anim = (t * 8f).toInt() and 1
        spr(c, intArrayOf(1, 0, 1, 2)[(t * 8f).toInt() and 3], 0, 44f, 140f, 7.5f, 7.5f)
        for (i in 0..3) spr(c, anim, 4 + i, 76f + i * 24f, 140f)

        if (((t * 2f).toInt() and 1) == 0) {
            label(c, "TOQUE PARA JOGAR", 112f, 192f, Color.WHITE, 12f, Paint.Align.CENTER)
        }
        label(c, "ARRASTE O DEDO PARA MOVER", 112f, 212f, Color.LTGRAY, 9f, Paint.Align.CENTER)
    }
}
