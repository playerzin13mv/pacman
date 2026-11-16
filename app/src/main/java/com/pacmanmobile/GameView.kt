package com.pacmanmobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desenha tudo numa tela lógica de 224x288 (como o arcade) e escala para o celular
 * sem suavização, para manter o pixel art nítido.
 * Controles: arrastar o dedo ou D-Pad (escolhido nas configurações).
 */
class GameView(ctx: Context) : View(ctx) {

    private val sfx = Sfx(ctx)
    private val prefs = ctx.getSharedPreferences("pacman", Context.MODE_PRIVATE)
    private val game = Game(sfx, prefs)

    private val sprites: Bitmap
    private val mazeBmp: Bitmap

    private val bmpPaint = Paint()
    private val whitePaint = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()
    private val rect = RectF()

    private var last = 0L
    private var paused = false

    // toque
    private var sx = 0f
    private var sy = 0f
    private var moved = false
    private val swipeThr = resources.displayMetrics.density * 20f

    // configurações
    private var settingsOpen = false
    private var ctrlMode = 0      // 0 = arrastar, 1 = D-Pad
    private var dpadSide = 1      // 0 = esquerda, 1 = centro, 2 = direita
    private var ghostLvl = 1      // 0 = lento, 1 = normal, 2 = rápido
    private var soundOn = true
    private val ghostMuls = floatArrayOf(0.6f, 0.75f, 1.0f)

    // layout (calculado em layout())
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var dSize = 0f
    private var dCx = 0f
    private var dCy = 0f
    private var dpadDir = -1

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
        stroke.style = Paint.Style.STROKE
        setBackgroundColor(Color.BLACK)
        loadSettings()
    }

    // ------------------------------------------------------------ configurações

    private fun loadSettings() {
        ctrlMode = prefs.getInt("ctrl", 0).coerceIn(0, 1)
        dpadSide = prefs.getInt("dside", 1).coerceIn(0, 2)
        ghostLvl = prefs.getInt("ghost", 1).coerceIn(0, 2)
        soundOn = prefs.getBoolean("sound", true)
        applySettings()
    }

    private fun applySettings() {
        game.ghostMult = ghostMuls[ghostLvl]
        sfx.enabled = soundOn
        if (!soundOn) sfx.stopLoop()
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("ctrl", ctrlMode)
            .putInt("dside", dpadSide)
            .putInt("ghost", ghostLvl)
            .putBoolean("sound", soundOn)
            .apply()
        applySettings()
    }

    private fun openSettings() {
        settingsOpen = true
        dpadDir = -1
        sfx.stopLoop()
    }

    private fun closeSettings() {
        settingsOpen = false
        last = 0L
    }

    /** Chamado pelo botão Voltar do celular. Retorna true se tratou o clique. */
    fun onBack(): Boolean {
        if (settingsOpen) {
            closeSettings()
            return true
        }
        return false
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

    // ------------------------------------------------------------ layout

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (ctrlMode == 1) {
            dSize = min(w * 0.62f, h * 0.30f)
            val pad = h * 0.03f
            val bottomGap = h * 0.02f
            val availH = h - dSize - pad * 2f - bottomGap
            scale = min(w / 224f, availH / 288f)
            offX = (w - 224f * scale) / 2f
            offY = max(0f, (availH - 288f * scale) / 2f)
            dCy = h - pad - bottomGap - dSize / 2f
            dCx = when (dpadSide) {
                0 -> dSize / 2f + w * 0.04f
                2 -> w - dSize / 2f - w * 0.04f
                else -> w / 2f
            }
        } else {
            scale = min(w / 224f, h / 288f)
            offX = (w - 224f * scale) / 2f
            offY = (h - 288f * scale) / 2f
        }
    }

    // ------------------------------------------------------------ toque

    private fun dpadDirAt(x: Float, y: Float): Int {
        val dx = x - dCx
        val dy = y - dCy
        val r = sqrt(dx * dx + dy * dy)
        if (r > dSize * 0.65f || r < dSize * 0.10f) return -1
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) Dir.R else Dir.L
        } else {
            if (dy > 0) Dir.D else Dir.U
        }
    }

    private fun scanDpad(e: MotionEvent, skip: Int) {
        var found = -1
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            val d = dpadDirAt(e.getX(i), e.getY(i))
            if (d >= 0) found = d
        }
        dpadDir = found
        if (found >= 0) game.swipe(found)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        layout()
        val act = e.actionMasked

        if (settingsOpen) {
            if (act == MotionEvent.ACTION_UP) {
                settingsTap((e.x - offX) / scale, (e.y - offY) / scale)
            }
            return true
        }

        if (ctrlMode == 1) {
            when (act) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN ->
                    scanDpad(e, -1)
                MotionEvent.ACTION_POINTER_UP -> scanDpad(e, e.actionIndex)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dpadDir = -1
            }
        }

        when (act) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - sx
                val dy = e.y - sy
                if (max(abs(dx), abs(dy)) > swipeThr) {
                    moved = true
                    if (ctrlMode == 0) {
                        val d = if (abs(dx) > abs(dy)) {
                            if (dx > 0) Dir.R else Dir.L
                        } else {
                            if (dy > 0) Dir.D else Dir.U
                        }
                        game.swipe(d)
                        sx = e.x
                        sy = e.y
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val lx = (e.x - offX) / scale
                    val ly = (e.y - offY) / scale
                    val gx = lx - GEAR_X
                    val gy = ly - GEAR_Y
                    if (gx * gx + gy * gy < 16f * 16f) openSettings() else game.tap()
                }
            }
        }
        return true
    }

    private fun settingsTap(lx: Float, ly: Float) {
        for (i in 0 until 4) {
            val y0 = ROW_Y0 + i * ROW_STEP
            if (lx in 12f..212f && ly in y0..(y0 + 28f)) {
                when (i) {
                    0 -> ctrlMode = 1 - ctrlMode
                    1 -> dpadSide = (dpadSide + 1) % 3
                    2 -> ghostLvl = (ghostLvl + 1) % 3
                    3 -> soundOn = !soundOn
                }
                saveSettings()
                return
            }
        }
        if (lx in 62f..162f && ly in 220f..248f) closeSettings()
    }

    // ------------------------------------------------------------ desenho

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
        last = now
        if (dt > 0.05f) dt = 0.05f
        if (!paused && !settingsOpen) game.update(dt)

        layout()
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)
        drawWorld(c)
        if (settingsOpen) drawSettings(c)
        c.restore()

        if (ctrlMode == 1 && !settingsOpen) drawDpad(c)

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

    private fun drawGear(c: Canvas) {
        stroke.color = Color.rgb(190, 190, 190)
        stroke.strokeWidth = 1.8f
        c.drawCircle(GEAR_X, GEAR_Y, 4.2f, stroke)
        stroke.strokeWidth = 2.4f
        for (k in 0 until 8) {
            val a = k * Math.PI / 4.0
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(GEAR_X + ca * 5.2f, GEAR_Y + sa * 5.2f, GEAR_X + ca * 7.4f, GEAR_Y + sa * 7.4f, stroke)
        }
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
        drawGear(c)

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
        fill.isAntiAlias = true

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
        val hint = if (ctrlMode == 0) "ARRASTE O DEDO PARA MOVER" else "USE O D-PAD PARA MOVER"
        label(c, hint, 112f, 212f, Color.LTGRAY, 9f, Paint.Align.CENTER)
        label(c, "ENGRENAGEM = CONFIGURACOES", 112f, 230f, Color.GRAY, 8f, Paint.Align.CENTER)
    }

    // ------------------------------------------------------------ tela de configurações

    private fun drawSettings(c: Canvas) {
        fill.color = Color.argb(245, 0, 0, 0)
        c.drawRect(0f, 0f, 224f, 288f, fill)
        label(c, "CONFIGURACOES", 112f, 40f, yellow, 15f, Paint.Align.CENTER)

        val names = arrayOf("CONTROLE", "LADO DO D-PAD", "FANTASMAS", "SOM")
        val values = arrayOf(
            if (ctrlMode == 0) "ARRASTAR" else "D-PAD",
            arrayOf("ESQUERDO", "CENTRO", "DIREITO")[dpadSide],
            arrayOf("LENTO", "NORMAL", "RAPIDO")[ghostLvl],
            if (soundOn) "LIGADO" else "DESLIGADO"
        )
        for (i in 0 until 4) {
            val y0 = ROW_Y0 + i * ROW_STEP
            val dim = i == 1 && ctrlMode == 0
            stroke.color = if (dim) Color.rgb(40, 40, 110) else Color.rgb(33, 33, 255)
            stroke.strokeWidth = 1.5f
            rect.set(12f, y0, 212f, y0 + 28f)
            c.drawRoundRect(rect, 4f, 4f, stroke)
            label(c, names[i], 20f, y0 + 18f, if (dim) Color.DKGRAY else Color.WHITE, 9f, Paint.Align.LEFT)
            label(c, values[i], 204f, y0 + 18f, if (dim) Color.DKGRAY else yellow, 10f, Paint.Align.RIGHT)
        }

        stroke.color = yellow
        rect.set(62f, 220f, 162f, 248f)
        c.drawRoundRect(rect, 4f, 4f, stroke)
        label(c, "VOLTAR", 112f, 238f, yellow, 12f, Paint.Align.CENTER)

        label(c, "TOQUE NA LINHA PARA TROCAR", 112f, 272f, Color.GRAY, 8f, Paint.Align.CENTER)
    }

    // ------------------------------------------------------------ D-Pad

    private fun drawArrow(c: Canvas, d: Int, px: Float, py: Float, k: Float) {
        path.reset()
        when (d) {
            Dir.R -> {
                path.moveTo(px - k, py - k); path.lineTo(px + k, py); path.lineTo(px - k, py + k)
            }
            Dir.L -> {
                path.moveTo(px + k, py - k); path.lineTo(px - k, py); path.lineTo(px + k, py + k)
            }
            Dir.U -> {
                path.moveTo(px - k, py + k); path.lineTo(px, py - k); path.lineTo(px + k, py + k)
            }
            else -> {
                path.moveTo(px - k, py - k); path.lineTo(px, py + k); path.lineTo(px + k, py - k)
            }
        }
        path.close()
        c.drawPath(path, dp)
    }

    private fun drawDpad(c: Canvas) {
        val ds = dSize
        val arm = ds * 0.34f
        val r = arm * 0.30f
        dp.style = Paint.Style.FILL

        // base em cruz
        dp.color = Color.argb(200, 45, 45, 45)
        rect.set(dCx - ds / 2f, dCy - arm / 2f, dCx + ds / 2f, dCy + arm / 2f)
        c.drawRoundRect(rect, r, r, dp)
        rect.set(dCx - arm / 2f, dCy - ds / 2f, dCx + arm / 2f, dCy + ds / 2f)
        c.drawRoundRect(rect, r, r, dp)

        // direção pressionada
        if (dpadDir >= 0) {
            dp.color = Color.argb(170, 255, 255, 0)
            when (dpadDir) {
                Dir.R -> rect.set(dCx, dCy - arm / 2f, dCx + ds / 2f, dCy + arm / 2f)
                Dir.L -> rect.set(dCx - ds / 2f, dCy - arm / 2f, dCx, dCy + arm / 2f)
                Dir.U -> rect.set(dCx - arm / 2f, dCy - ds / 2f, dCx + arm / 2f, dCy)
                else -> rect.set(dCx - arm / 2f, dCy, dCx + arm / 2f, dCy + ds / 2f)
            }
            c.drawRoundRect(rect, r, r, dp)
        }

        // setas
        dp.color = Color.argb(230, 220, 220, 220)
        val off = ds * 0.31f
        val k = ds * 0.06f
        for (d in 0..3) {
            drawArrow(c, d, dCx + Dir.DX[d] * off, dCy + Dir.DY[d] * off, k)
        }
    }

    companion object {
        private const val GEAR_X = 208f
        private const val GEAR_Y = 12f
        private const val ROW_Y0 = 62f
        private const val ROW_STEP = 38f
    }
}
