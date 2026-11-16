package com.pacmanmobile

import android.content.SharedPreferences
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

object Dir {
    const val R = 0
    const val L = 1
    const val U = 2
    const val D = 3
    val DX = intArrayOf(1, -1, 0, 0)
    val DY = intArrayOf(0, 0, -1, 1)
    fun opp(d: Int): Int = when (d) {
        R -> L
        L -> R
        U -> D
        else -> U
    }
}

/**
 * Posição em "tile + progresso": está no tile (tx,ty) e já andou [f] (0..1)
 * em direção [dir]. Assim as curvas e decisões acontecem sempre no centro do tile.
 */
open class Mover {
    var tx = 0
    var ty = 0
    var dir = Dir.L
    var f = 0f

    fun x(): Float = tx + 0.5f + Dir.DX[dir] * f
    fun y(): Float = ty + 0.5f + Dir.DY[dir] * f

    fun flip() {
        tx += Dir.DX[dir]
        ty += Dir.DY[dir]
        dir = Dir.opp(dir)
        f = 1f - f
    }

    /** Túnel: tiles virtuais de -2 a 29, teleporta fora da tela. */
    fun wrap() {
        if (tx <= -2) tx = Maze.W + 1 else if (tx >= Maze.W + 1) tx = -2
    }
}

class Pac : Mover() {
    var desired = Dir.L
    var pauseT = 0f
    var animT = 0f
    var moving = false
}

enum class GState { WAIT, LEAVE, NORMAL, EYES }

class Ghost(val id: Int) : Mover() {
    var state = GState.WAIT
    var frightened = false
    var pendingReverse = false

    fun setup(x: Int, y: Int, d: Int, ff: Float, st: GState) {
        tx = x
        ty = y
        dir = d
        f = ff
        state = st
        frightened = false
        pendingReverse = false
    }
}

class Popup(val x: Float, val y: Float, val text: String, var ttl: Float)

class Game(private val sfx: Sfx?, private val prefs: SharedPreferences?) {

    enum class S { TITLE, READY, PLAY, PAC_HIT, DYING, CLEAR, OVER }

    var state = S.TITLE
    var stateT = 0f
    var time = 0f

    var level = 1
    var lives = 3
    var score = 0
    var hi = 0

    val dots = Array(Maze.H) { BooleanArray(Maze.W) }
    val pellets = Array(Maze.H) { BooleanArray(Maze.W) }
    var dotsLeft = 0
    var dotsEaten = 0

    val pac = Pac()
    val ghosts = Array(4) { Ghost(it) }
    val popups = ArrayList<Popup>()

    var frightT = 0f
    var fruitT = 0f

    private var modeIdx = 0
    private var modeT = 0f
    private var sched = floatArrayOf()
    private var releaseCounter = 0
    private var idleT = 0f
    private var ghostsEaten = 0
    private var freezeT = 0f
    private var readyDur = 2f
    private var wakaToggle = false
    private val rnd = Random()

    private val distExit = Maze.bfs(listOf(Pair(13, 11), Pair(14, 11)))
    private val distHouse = Maze.bfs(listOf(Pair(13, 13)))

    private val base = 9.4f
    private val order = intArrayOf(Dir.U, Dir.L, Dir.D, Dir.R)

    init {
        hi = prefs?.getInt("hi", 0) ?: 0
        fillDots()
        resetPositions()
    }

    // ------------------------------------------------------------ parâmetros

    private fun pacSpeed(): Float = base * when {
        level == 1 -> 0.8f
        level <= 4 -> 0.9f
        level <= 20 -> 1.0f
        else -> 0.9f
    }

    private fun pacFrightSpeed(): Float = base * when {
        level == 1 -> 0.9f
        level <= 4 -> 0.95f
        else -> 1.0f
    }

    private fun ghostSpeed(): Float = base * when {
        level == 1 -> 0.75f
        level <= 4 -> 0.85f
        else -> 0.95f
    }

    private fun tunnelSpeed(): Float = base * when {
        level == 1 -> 0.4f
        level <= 4 -> 0.45f
        else -> 0.5f
    }

    private fun frightSpeed(): Float = base * when {
        level == 1 -> 0.5f
        level <= 4 -> 0.55f
        else -> 0.6f
    }

    private fun frightTime(): Float {
        val t = floatArrayOf(6f, 5f, 4f, 3f, 2f, 5f, 2f, 2f, 1f, 5f, 2f, 1f, 1f, 3f, 1f, 1f, 0f, 1f, 0f)
        return if (level <= t.size) t[level - 1] else 0f
    }

    private fun schedule(): FloatArray = when {
        level == 1 -> floatArrayOf(7f, 20f, 7f, 20f, 5f, 20f, 5f)
        level <= 4 -> floatArrayOf(7f, 20f, 7f, 20f, 5f, 1033f, 0.017f)
        else -> floatArrayOf(5f, 20f, 5f, 20f, 5f, 1037f, 0.017f)
    }

    private fun dotLimit(id: Int): Int = when (id) {
        1 -> 0
        2 -> if (level == 1) 30 else 0
        else -> when (level) {
            1 -> 60
            2 -> 50
            else -> 0
        }
    }

    fun fruitIdx(l: Int): Int = when {
        l <= 1 -> 0
        l == 2 -> 1
        l <= 4 -> 2
        l <= 6 -> 3
        l <= 8 -> 4
        l <= 10 -> 5
        l <= 12 -> 6
        else -> 7
    }

    private val fruitPoints = intArrayOf(100, 300, 500, 700, 1000, 2000, 3000, 5000)

    fun flashing(): Boolean = frightT > 0f && frightT < 2f

    // ------------------------------------------------------------ fluxo

    fun swipe(d: Int) {
        pac.desired = d
    }

    fun tap() {
        when (state) {
            S.TITLE -> startGame()
            S.OVER -> if (stateT > 1.2f) {
                state = S.TITLE
                stateT = 0f
            }
            else -> {}
        }
    }

    private fun startGame() {
        score = 0
        lives = 3
        level = 1
        newLevel(true)
    }

    private fun fillDots() {
        var n = 0
        for (y in 0 until Maze.H) {
            for (x in 0 until Maze.W) {
                val c = Maze.ROWS[y][x]
                dots[y][x] = c == '.' || c == 'o'
                pellets[y][x] = c == 'o'
                if (dots[y][x]) n++
            }
        }
        dotsLeft = n
        dotsEaten = 0
    }

    private fun newLevel(first: Boolean) {
        fillDots()
        modeIdx = 0
        modeT = 0f
        sched = schedule()
        fruitT = 0f
        popups.clear()
        resetPositions()
        state = S.READY
        stateT = 0f
        readyDur = if (first) 4.3f else 2.3f
        if (first) sfx?.play("intro")
    }

    private fun resetPositions() {
        pac.tx = 14
        pac.ty = 23
        pac.dir = Dir.L
        pac.f = 0.5f
        pac.desired = Dir.L
        pac.pauseT = 0f
        pac.moving = false
        ghosts[0].setup(14, 11, Dir.L, 0.5f, GState.NORMAL)
        ghosts[1].setup(13, 14, Dir.R, 0.5f, GState.WAIT)
        ghosts[2].setup(11, 14, Dir.R, 0f, GState.WAIT)
        ghosts[3].setup(16, 14, Dir.R, 0f, GState.WAIT)
        frightT = 0f
        ghostsEaten = 0
        freezeT = 0f
        releaseCounter = 0
        idleT = 0f
    }

    fun update(dt: Float) {
        time += dt
        stateT += dt
        val iter = popups.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.ttl -= dt
            if (p.ttl <= 0f) iter.remove()
        }
        when (state) {
            S.TITLE -> {}
            S.READY -> if (stateT >= readyDur) {
                state = S.PLAY
                stateT = 0f
            }
            S.PLAY -> play(dt)
            S.PAC_HIT -> if (stateT >= 1.0f) {
                state = S.DYING
                stateT = 0f
                popPlayed = false
                sfx?.play("death")
            }
            S.DYING -> {
                if (!popPlayed && stateT >= 1.5f) {
                    popPlayed = true
                    sfx?.play("death2")
                }
                if (stateT >= 3.0f) endDeath()
            }
            S.CLEAR -> if (stateT >= 2.8f) {
                level++
                newLevel(false)
            }
            S.OVER -> {}
        }
    }

    private var popPlayed = false

    private fun endDeath() {
        lives--
        if (lives <= 0) {
            state = S.OVER
            stateT = 0f
            saveHi()
        } else {
            modeIdx = 0
            modeT = 0f
            resetPositions()
            state = S.READY
            stateT = 0f
            readyDur = 2.3f
        }
    }

    private fun saveHi() {
        prefs?.edit()?.putInt("hi", hi)?.apply()
    }

    private fun addScore(n: Int) {
        val before = score
        score += n
        if (before < 10000 && score >= 10000) {
            lives++
            sfx?.play("extra")
        }
        if (score > hi) hi = score
    }

    // ------------------------------------------------------------ jogo

    private fun play(dt: Float) {
        if (freezeT > 0f) {
            freezeT -= dt
            return
        }
        updatePac(dt)
        if (state != S.PLAY) return
        for (g in ghosts) updateGhost(g, dt)
        timers(dt)
        checkCollisions()
        if (state == S.PLAY) updateLoopSound()
    }

    private fun updateLoopSound() {
        var eyes = false
        for (g in ghosts) if (g.state == GState.EYES) eyes = true
        val name = when {
            eyes -> "eyes"
            frightT > 0f -> "fright"
            dotsLeft <= 20 -> "siren4"
            dotsLeft <= 60 -> "siren3"
            dotsLeft <= 110 -> "siren2"
            dotsLeft <= 170 -> "siren1"
            else -> "siren0"
        }
        sfx?.loop(name)
    }

    private fun timers(dt: Float) {
        if (frightT > 0f) {
            frightT -= dt
            if (frightT <= 0f) {
                frightT = 0f
                for (g in ghosts) g.frightened = false
            }
        } else {
            modeT += dt
            if (modeIdx < sched.size && modeT >= sched[modeIdx]) {
                modeT = 0f
                modeIdx++
                for (g in ghosts) if (g.state == GState.NORMAL) g.pendingReverse = true
            }
        }
        if (fruitT > 0f) fruitT -= dt

        idleT += dt
        val limit = if (level < 5) 4f else 3f
        for (id in 1..3) {
            val g = ghosts[id]
            if (g.state == GState.WAIT) {
                if (releaseCounter >= dotLimit(id) || idleT >= limit) {
                    g.state = GState.LEAVE
                    idleT = 0f
                }
                break
            }
        }
    }

    private fun updatePac(dt: Float) {
        val p = pac
        if (p.pauseT > 0f) {
            p.pauseT -= dt
            p.moving = false
            return
        }
        var move = (if (frightT > 0f) pacFrightSpeed() else pacSpeed()) * dt
        if (p.f > 0f && p.desired == Dir.opp(p.dir)) p.flip()
        p.moving = false
        var guard = 0
        while (move > 1e-5f && guard++ < 8) {
            if (p.f <= 0f) {
                val nd = p.desired
                if (Maze.open(p.tx + Dir.DX[nd], p.ty + Dir.DY[nd])) {
                    p.dir = nd
                } else if (!Maze.open(p.tx + Dir.DX[p.dir], p.ty + Dir.DY[p.dir])) {
                    break
                }
            }
            val step = min(move, 1f - p.f)
            p.f += step
            move -= step
            p.moving = true
            if (p.f >= 0.9999f) {
                p.tx += Dir.DX[p.dir]
                p.ty += Dir.DY[p.dir]
                p.f = 0f
                p.wrap()
                eatTile(p.tx, p.ty)
                if (state != S.PLAY || p.pauseT > 0f) break
            }
        }
        if (p.moving) p.animT += dt
        checkFruit()
    }

    private fun eatTile(x: Int, y: Int) {
        if (x < 0 || x >= Maze.W || y < 0 || y >= Maze.H) return
        if (!dots[y][x]) return
        dots[y][x] = false
        dotsLeft--
        dotsEaten++
        releaseCounter++
        idleT = 0f
        if (pellets[y][x]) {
            pellets[y][x] = false
            addScore(50)
            pac.pauseT = 3f / 60f
            startFright()
        } else {
            addScore(10)
            pac.pauseT = 1f / 60f
            wakaToggle = !wakaToggle
            sfx?.play(if (wakaToggle) "waka1" else "waka2")
        }
        if (dotsEaten == 70 || dotsEaten == 170) fruitT = 9.5f
        if (dotsLeft <= 0) {
            state = S.CLEAR
            stateT = 0f
            sfx?.stopLoop()
        }
    }

    private fun checkFruit() {
        if (fruitT <= 0f) return
        if (abs(pac.x() - 14f) < 0.9f && abs(pac.y() - 17.5f) < 0.9f) {
            val i = fruitIdx(level)
            addScore(fruitPoints[i])
            popups.add(Popup(14f, 17.5f, fruitPoints[i].toString(), 1.5f))
            fruitT = 0f
            sfx?.play("fruit")
        }
    }

    private fun startFright() {
        val t = frightTime()
        ghostsEaten = 0
        for (g in ghosts) {
            if (g.state == GState.NORMAL) g.pendingReverse = true
            if (t > 0f && g.state != GState.EYES) g.frightened = true
        }
        if (t > 0f) frightT = t
    }

    private fun checkCollisions() {
        val px = pac.x()
        val py = pac.y()
        for (g in ghosts) {
            if (g.state != GState.NORMAL && g.state != GState.LEAVE) continue
            if (abs(g.x() - px) < 0.7f && abs(g.y() - py) < 0.7f) {
                if (g.frightened) {
                    eatGhost(g)
                } else {
                    state = S.PAC_HIT
                    stateT = 0f
                    sfx?.stopLoop()
                    return
                }
            }
        }
    }

    private fun eatGhost(g: Ghost) {
        ghostsEaten++
        val pts = 200 shl (ghostsEaten - 1)
        addScore(pts)
        popups.add(Popup(g.x(), g.y(), pts.toString(), 0.8f))
        g.state = GState.EYES
        g.frightened = false
        g.pendingReverse = false
        freezeT = 0.6f
        sfx?.play("eatghost")
    }

    // ------------------------------------------------------------ fantasmas

    private fun elroyMult(): Float = when {
        dotsLeft <= 10 -> 1.13f
        dotsLeft <= 20 -> 1.07f
        else -> 1f
    }

    /** Multiplicador de velocidade dos fantasmas (configurável no jogo). */
    var ghostMult = 0.75f

    private fun ghostSpd(g: Ghost): Float {
        val v = rawGhostSpd(g)
        return if (g.state == GState.EYES) v else v * ghostMult
    }

    private fun rawGhostSpd(g: Ghost): Float = when {
        g.state == GState.EYES -> 14f
        g.state == GState.WAIT -> 0f
        g.state == GState.LEAVE -> base * 0.45f
        Maze.isTunnel(g.tx, g.ty) -> tunnelSpeed()
        g.frightened -> frightSpeed()
        g.id == 0 -> ghostSpeed() * elroyMult()
        else -> ghostSpeed()
    }

    private fun updateGhost(g: Ghost, dt: Float) {
        if (g.state == GState.WAIT) return
        var move = ghostSpd(g) * dt
        var guard = 0
        while (move > 1e-5f && guard++ < 10) {
            if (g.f <= 0f) decide(g)
            val step = min(move, 1f - g.f)
            g.f += step
            move -= step
            if (g.f >= 0.9999f) {
                g.tx += Dir.DX[g.dir]
                g.ty += Dir.DY[g.dir]
                g.f = 0f
                g.wrap()
                onGhostArrive(g)
            }
        }
    }

    private fun onGhostArrive(g: Ghost) {
        when (g.state) {
            GState.EYES -> if (g.tx == 13 && g.ty == 13) {
                g.state = GState.LEAVE
                g.frightened = false
                g.dir = Dir.D
            }
            GState.LEAVE -> if (g.ty == 11 && (g.tx == 13 || g.tx == 14)) {
                g.state = GState.NORMAL
                g.dir = Dir.L
                g.pendingReverse = false
            }
            else -> {}
        }
    }

    private fun decide(g: Ghost) {
        if (g.state == GState.EYES || g.state == GState.LEAVE) {
            val field = if (g.state == GState.EYES) distHouse else distExit
            var best = -1
            var bd = Maze.INF
            for (d in order) {
                val nx = g.tx + Dir.DX[d]
                val ny = g.ty + Dir.DY[d]
                if (nx < 0 || nx >= Maze.W || ny < 0 || ny >= Maze.H) continue
                if (field[ny][nx] < bd) {
                    bd = field[ny][nx]
                    best = d
                }
            }
            if (best >= 0) g.dir = best
            return
        }

        if (g.pendingReverse) {
            g.dir = Dir.opp(g.dir)
            g.pendingReverse = false
            return
        }

        val back = Dir.opp(g.dir)
        if (g.frightened) {
            val cand = ArrayList<Int>()
            for (d in order) {
                if (d == back) continue
                if (Maze.open(g.tx + Dir.DX[d], g.ty + Dir.DY[d])) cand.add(d)
            }
            g.dir = if (cand.isEmpty()) back else cand[rnd.nextInt(cand.size)]
            return
        }

        val target = targetOf(g)
        var best = -1
        var bd = Long.MAX_VALUE
        for (d in order) {
            if (d == back) continue
            val nx = g.tx + Dir.DX[d]
            val ny = g.ty + Dir.DY[d]
            if (!Maze.open(nx, ny)) continue
            val dx = (nx - target[0]).toLong()
            val dy = (ny - target[1]).toLong()
            val dist = dx * dx + dy * dy
            if (dist < bd) {
                bd = dist
                best = d
            }
        }
        g.dir = if (best >= 0) best else back
    }

    private fun isScatter(): Boolean = modeIdx < sched.size && modeIdx % 2 == 0

    private fun pacTileX(): Int = Math.floor(pac.x().toDouble()).toInt()
    private fun pacTileY(): Int = Math.floor(pac.y().toDouble()).toInt()

    private fun corner(id: Int): IntArray = when (id) {
        0 -> intArrayOf(25, -3)
        1 -> intArrayOf(2, -3)
        2 -> intArrayOf(27, 31)
        else -> intArrayOf(0, 31)
    }

    private fun targetOf(g: Ghost): IntArray {
        val elroy = g.id == 0 && dotsLeft <= 20
        if (isScatter() && !elroy) return corner(g.id)
        val px = pacTileX()
        val py = pacTileY()
        val dx = Dir.DX[pac.dir]
        val dy = Dir.DY[pac.dir]
        return when (g.id) {
            0 -> intArrayOf(px, py)
            1 -> if (pac.dir == Dir.U) intArrayOf(px - 4, py - 4) else intArrayOf(px + 4 * dx, py + 4 * dy)
            2 -> {
                val ax = if (pac.dir == Dir.U) px - 2 else px + 2 * dx
                val ay = py + 2 * dy
                val b = ghosts[0]
                val bx = Math.floor(b.x().toDouble()).toInt()
                val by = Math.floor(b.y().toDouble()).toInt()
                intArrayOf(2 * ax - bx, 2 * ay - by)
            }
            else -> {
                val gx = Math.floor(g.x().toDouble()).toInt()
                val gy = Math.floor(g.y().toDouble()).toInt()
                val ddx = gx - px
                val ddy = gy - py
                if (ddx * ddx + ddy * ddy > 64) intArrayOf(px, py) else corner(3)
            }
        }
    }
}
