package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Process-wide lock state; re-locks after the grace period in the background. */
object AppLock {
    @Volatile var unlockedAt: Long = 0

    fun isLocked(enabled: Boolean, graceSeconds: Int, lastBackgroundedAt: Long): Boolean {
        if (!enabled) return false
        if (unlockedAt == 0L) return true
        // Locked when the app was backgrounded after the last unlock and stayed away longer than the grace.
        val away = if (lastBackgroundedAt > unlockedAt) System.currentTimeMillis() - lastBackgroundedAt else 0
        return away > graceSeconds * 1000L
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticators() = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun prompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        ask(
            activity,
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock flintTerm")
                .setSubtitle("Your hosts and keys are locked")
                .setAllowedAuthenticators(authenticators())
                .build(),
        ) { result ->
            if (result != null) unlockedAt = System.currentTimeMillis()
            onResult(result != null)
        }
    }

    /**
     * Raise the platform's prompt and report what came back.
     *
     * Must be called on the main thread — [BiometricPrompt] attaches a fragment
     * to the activity. [crypto] binds the prompt to an operation the keystore
     * will only allow once the person is there, which is how a per-signature key
     * gets used; the result carries that same object back, now usable.
     */
    fun ask(
        activity: FragmentActivity,
        info: BiometricPrompt.PromptInfo,
        crypto: BiometricPrompt.CryptoObject? = null,
        onResult: (BiometricPrompt.AuthenticationResult?) -> Unit,
    ) {
        var answered = false
        // Success and error are the two ends; a single failed finger is neither,
        // and the prompt stays up for another try. Whichever arrives first wins,
        // so a late callback cannot answer twice.
        val once = { result: BiometricPrompt.AuthenticationResult? -> if (!answered) { answered = true; onResult(result) } }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = once(result)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = once(null)
        })
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }
}

@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppLock.prompt(activity) { ok -> if (ok) onUnlocked() else failed = true } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.applock_locked), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.applock_unlock_with_your_fingerprint_face_or_screen_lock), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { failed = false; AppLock.prompt(activity) { ok -> if (ok) onUnlocked() else failed = true } }) { Text(if (failed) stringResource(R.string.applock_try_again) else "Unlock") }
        }
    }
}
