package com.pacmanmobile

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Toca os efeitos sonoros reais do Pac-Man (arquivos WAV em assets/sounds).
 * Sons de sirene, susto e olhos ficam em loop.
 */
class Sfx(ctx: Context) {
    private val pool: SoundPool
    private val ids = HashMap<String, Int>()
    private var loopName: String? = null
    private var loopStream = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()

        fun reg(name: String, file: String) {
            try {
                ctx.assets.openFd("sounds/$file.wav").use { ids[name] = pool.load(it, 1) }
            } catch (e: Exception) {
                // sem som é melhor do que travar o jogo
            }
        }

        reg("intro", "start")
        reg("waka1", "eat_dot_0")
        reg("waka2", "eat_dot_1")
        reg("eatghost", "eat_ghost")
        reg("fruit", "eat_fruit")
        reg("extra", "extend")
        reg("death", "death_0")
        reg("death2", "death_1")
        reg("fright", "fright")
        reg("eyes", "eyes")
        for (i in 0..4) reg("siren$i", "siren$i")
    }

    fun play(name: String) {
        val id = ids[name] ?: return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Toca um som em loop; só troca se for outro. */
    fun loop(name: String) {
        if (loopName == name) return
        stopLoop()
        val id = ids[name] ?: return
        loopStream = pool.play(id, 0.8f, 0.8f, 0, -1, 1f)
        loopName = name
    }

    fun stopLoop() {
        if (loopStream != 0) pool.stop(loopStream)
        loopStream = 0
        loopName = null
    }

    fun release() {
        stopLoop()
        pool.release()
    }
}
