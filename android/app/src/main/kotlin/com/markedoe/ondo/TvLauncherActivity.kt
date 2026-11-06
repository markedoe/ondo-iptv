package com.markedoe.ondo

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The TV home screen's entry: it opens [MainActivity] on Home, at its top with Play focused, also when Ondo is
 * running and was left on another page or in the player (closed then: the TV's launcher opens the app, not the film).
 * Nothing of its own shows. A phone's app icon opens [MainActivity] directly, which comes back where it was left.
 */
class TvLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(homeIntent(this))
        finish()
    }

    companion object {
        /** [MainActivity] brought to the front with the pages over it closed, told to show Home ([MainActivity.EXTRA_OPEN_HOME]). */
        fun homeIntent(context: android.content.Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_HOME, true)
    }
}
