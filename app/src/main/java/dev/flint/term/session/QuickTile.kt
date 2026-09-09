package dev.flint.term.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.data.Host

/**
 * Quick settings tile that connects to the last host used, in one tap.
 *
 * The tile names its target so a pull-down is enough to see where it would go
 * without opening the app.
 */
class QuickTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val host = recentHost()
        val name = host?.displayName ?: "No hosts yet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = getString(R.string.app_name)
            tile.subtitle = name
        } else {
            // Tiles below Q have no subtitle line, so the host has to be the label.
            tile.label = name
        }
        tile.state = if (host == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val host = recentHost() ?: return
        val intent = Shortcuts.connectIntent(this, host)
        // A connection needs the keystore, so get past the lock screen first.
        if (isLocked) unlockAndRun { launch(intent) } else launch(intent)
    }

    // Android 14 made the Intent form throw, which is what the branch below is
    // for; lint flags the call anyway, guarded or not.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, flags))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun recentHost(): Host? =
        (application as App).store.hosts.value.maxByOrNull { it.lastConnected }
}
