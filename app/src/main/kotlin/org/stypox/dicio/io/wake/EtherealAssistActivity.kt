package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import org.stypox.dicio.MainActivity

/**
 * Invisible entry point for the assist gesture (ACTION_ASSIST / ACTION_VOICE_COMMAND /
 * VOICE_SEARCH_HANDS_FREE: long-press home, side key "digital assistant", headset button) when
 * this app is the default digital assistant. Instead of opening Dicio's UI it asks [WakeService]
 * to run one Ethereal turn (focus, beep, record, streamed reply) and finishes right away.
 * It is allowed over the lock screen, so the turn also works with the phone locked.
 */
class EtherealAssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }

        if (!EtherealClient.ENABLED ||
            ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED
        ) {
            // stock behavior (or let the main activity ask for the microphone permission)
            startActivity(Intent(intent).setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            try {
                WakeService.requestEtherealTurn(this)
            } catch (e: Exception) {
                Log.e(TAG, "could not start the Ethereal turn", e)
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "EtherealAssist"
    }
}
