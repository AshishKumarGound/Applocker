package com.example.applocker

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores the admin password (salted PBKDF2 hash, never the plain text), the allowed package list
 * and the restricted-mode flag in EncryptedSharedPreferences.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun hasPin(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected) // constant-time compare
    }

    var restricted: Boolean
        get() = prefs.getBoolean(KEY_RESTRICTED, false)
        set(v) { prefs.edit().putBoolean(KEY_RESTRICTED, v).apply() }

    var allowedPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet())
        set(v) { prefs.edit().putStringSet(KEY_ALLOWED, HashSet(v)).apply() }

    /** Packages we suspended through DevicePolicyManager, so we can un-suspend them later. */
    var suspendedPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_SUSPENDED, emptySet()) ?: emptySet())
        set(v) { prefs.edit().putStringSet(KEY_SUSPENDED, HashSet(v)).apply() }

    /** Packages we hid (setApplicationHidden) through DevicePolicyManager, so we can un-hide them later. */
    var hiddenPackages: Set<String>
        get() = HashSet(prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())
        set(v) { prefs.edit().putStringSet(KEY_HIDDEN, HashSet(v)).apply() }

    /** "GREY_OUT" or "HIDE" — see PolicyManager.BlockMode. */
    var blockMode: String
        get() = prefs.getString(KEY_BLOCK_MODE, "GREY_OUT") ?: "GREY_OUT"
        set(v) { prefs.edit().putString(KEY_BLOCK_MODE, v).apply() }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val KEY_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val KEY_RESTRICTED = "restricted"
        const val KEY_ALLOWED = "allowed_packages"
        const val KEY_SUSPENDED = "suspended_packages"
        const val KEY_HIDDEN = "hidden_packages"
        const val KEY_BLOCK_MODE = "block_mode"

        fun createPrefs(ctx: Context): SharedPreferences = try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "locker_secure_prefs", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore problems on some OEM builds: fall back so a launcher never crash-loops.
            Log.w("SecureStore", "Encrypted prefs unavailable, using plain prefs (PIN is still hashed)", e)
            ctx.getSharedPreferences("locker_fallback_prefs", Context.MODE_PRIVATE)
        }
    }
}
