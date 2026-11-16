package com.pacmanmobile

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = GameView(this)
        setContentView(view)
        hideSystemUi()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val ctrl = window.insetsController
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.systemBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            legacyHide()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyHide() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        view.onPauseGame()
    }

    override fun onResume() {
        super.onResume()
        view.onResumeGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        view.release()
    }
}
