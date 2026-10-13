package ec.gob.simertpi.simertpi_citizen_app

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Device-only vault. Private-key use requires Class 3 biometric for EVERY decrypt.
 * Public-key encryption safely updates rotated session material without a prompt.
 * No backup, no passwords, no biometric templates, no success boolean as authority.
 */
class DeviceSecurityChannel(
    private val activity: FragmentActivity,
    messenger: BinaryMessenger,
    private val protectBack: (Boolean) -> Unit,
) {
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    private var pending: MethodChannel.Result? = null
    private var prompt: BiometricPrompt? = null
    private var epoch = 0

    init {
        MethodChannel(messenger, "simertpi/device_security").setMethodCallHandler { call, result ->
            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
            try {
                if (call.method == "protect") {
                    protectBack(args["locked"] == true)
                    result.success(null)
                } else {
                    val namespace = args["namespace"] as? String ?: throw IllegalArgumentException()
                    val owner = args["owner"] as? String ?: throw IllegalArgumentException()
                    require(owner.isNotBlank() && namespace.isNotBlank())
                    val alias = "simertpi.bio." + MessageDigest.getInstance("SHA-256")
                        .digest((namespace + "\u0000" + owner).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    when (call.method) {
                        "verifySession" -> {
                            val session = JSONObject(args["session"] as? String ?: throw IllegalArgumentException())
                            val file = sessionGuard(alias)
                            val saved = if (file.exists()) JSONObject(file.readText()) else null
                            result.success(session.getString("userId") == owner && saved != null && saved.optBoolean("active") &&
                                saved.optString("refreshHash") == refreshHash(session))
                        }
                        "invalidateSession" -> {
                            // Written before deletion/PUSH/network, never copied to another phone.
                            write(sessionGuard(alias), JSONObject().put("active", false).toString())
                            epoch++
                            prompt?.cancelAuthentication()
                            remove(alias)
                            result.success(null)
                        }
                        "activityWrite" -> {
                            val at = (args["at"] as? Number)?.toLong() ?: throw IllegalArgumentException()
                            val wall = System.currentTimeMillis()
                            val elapsed = SystemClock.elapsedRealtime() - (wall - at).coerceAtLeast(0)
                            write(File(activity.noBackupFilesDir, "$alias.activity"), JSONObject()
                                .put("wall", at).put("elapsed", elapsed).put("boot", boot()).toString())
                            result.success(null)
                        }
                        "activityAge" -> {
                            val file = File(activity.noBackupFilesDir, "$alias.activity")
                            val last = if (file.exists()) JSONObject(file.readText()) else null
                            val age = last?.let { SystemClock.elapsedRealtime() - it.getLong("elapsed") }
                            result.success(if (last == null || age == null || age < 0 ||
                                boot() < 0 || boot() != last.getInt("boot") || System.currentTimeMillis() < last.getLong("wall")) null else age)
                        }
                        "status" -> result.success(status(alias))
                        "shouldInvite" -> result.success(!declined(alias).exists())
                        "decline" -> { write(declined(alias), "declined"); result.success(null) }
                        "disable" -> {
                            epoch++
                            prompt?.cancelAuthentication()
                            remove(alias)
                            result.success(null)
                        }
                        "enable" -> {
                            require(status(alias) == "disabled")
                            require(pending == null)
                            val session = args["session"] as? String ?: throw IllegalArgumentException()
                            require(JSONObject(session).getString("userId") == owner)
                            createKey(alias)
                            val envelope = encrypt(alias, session)
                            authenticate(alias, envelope, owner, result, enabling = true)
                        }
                        "update" -> {
                            val session = args["session"] as? String ?: throw IllegalArgumentException()
                            val parsed = JSONObject(session)
                            require(parsed.getString("userId") == owner)
                            write(sessionGuard(alias), JSONObject().put("active", true)
                                .put("refreshHash", refreshHash(parsed)).toString())
                            if (keys.containsAlias(alias) && vault(alias).exists()) {
                                write(vault(alias), encrypt(alias, session).toString())
                            }
                            result.success(null)
                        }
                        "unlock" -> {
                            require(pending == null)
                            require(status(alias) == "enabled")
                            authenticate(alias, JSONObject(vault(alias).readText()), owner, result, enabling = false)
                        }
                        else -> result.notImplemented()
                    }
                }
            } catch (_: Exception) {
                result.error("UNAVAILABLE", "Biometric operation unavailable", null)
            }
        }
    }
    private fun boot(): Int = if (Build.VERSION.SDK_INT >= 24)
        Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT, -1) else -1
    private fun vault(alias: String) = File(activity.noBackupFilesDir, "$alias.vault")
    private fun sessionGuard(alias: String) = File(activity.noBackupFilesDir, "$alias.session")
    private fun refreshHash(session: JSONObject) = MessageDigest.getInstance("SHA-256")
        .digest(session.getString("refreshToken").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun declined(alias: String) = File(activity.noBackupFilesDir, "$alias.declined")
    private fun write(file: File, content: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(content.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun remove(alias: String) {
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        val file = vault(alias)
        if (file.exists()) check(file.delete())
    }
    private fun status(alias: String): String {
        // API 24 adds explicit enrollment invalidation. Older devices use password.
        if (Build.VERSION.SDK_INT < 24 || BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            return "unavailable"
        }
        if (!keys.containsAlias(alias) || !vault(alias).exists()) return "disabled"
        return try { decryptCipher(alias); "enabled" }
        catch (_: KeyPermanentlyInvalidatedException) { remove(alias); "disabled" }
        catch (_: Exception) { "unavailable" }
    }
    private fun createKey(alias: String) {
        remove(alias)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(builder.build()); generateKeyPair()
        }
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun bytes(json: JSONObject, key: String) = Base64.decode(json.getString(key), Base64.NO_WRAP)
    private fun encrypt(alias: String, session: String): JSONObject {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"))
            aes.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val payload = aes.doFinal(session.toByteArray(Charsets.UTF_8))
            // Unrestricted PUBLIC key; the PRIVATE key never leaves Keystore.
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(keys.getCertificate(alias).publicKey.encoded))
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
            return JSONObject().put("key", b64(rsa.doFinal(secret)))
                .put("iv", b64(aes.iv)).put("payload", b64(payload))
        } finally { secret.fill(0) }
    }
    private fun decryptCipher(alias: String): Cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
        init(Cipher.DECRYPT_MODE, keys.getKey(alias, null) as PrivateKey, oaep)
    }
    private fun authenticate(alias: String, envelope: JSONObject, owner: String, result: MethodChannel.Result, enabling: Boolean) {
        val cipher = try { decryptCipher(alias) } catch (_: Exception) {
            remove(alias); result.error("INVALIDATED", "Password required", null); return
        }
        pending = result
        val generation = epoch
        fun finish(value: String? = null, error: String? = null) {
            if (pending !== result) return
            var failure = error
            if (failure == null && enabling) {
                try { write(vault(alias), envelope.toString()) }
                catch (_: Exception) { failure = "UNAVAILABLE" }
            }
            if (failure != null && enabling) {
                try { remove(alias) } catch (_: Exception) { /* No persisted session is authorized. */ }
            }
            pending = null
            prompt = null
            if (failure != null) {
                result.error(failure, "Biometric verification did not complete", null)
            } else {
                if (enabling) {
                    result.success(null)
                } else result.success(value)
            }
        }
        prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                if (generation != epoch) { finish(error = "CANCELLED"); return }
                var secret: ByteArray? = null
                try {
                    check(authentication.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
                    secret = authentication.cryptoObject?.cipher?.doFinal(bytes(envelope, "key")) ?: throw IllegalStateException()
                    val aes = Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, bytes(envelope, "iv")))
                    aes.updateAAD(alias.toByteArray(Charsets.UTF_8))
                    val session = String(aes.doFinal(bytes(envelope, "payload")), Charsets.UTF_8)
                    check(JSONObject(session).getString("userId") == owner)
                    finish(value = session)
                } catch (_: Exception) { finish(error = "INVALIDATED") }
                finally { secret?.fill(0) }
            }
            override fun onAuthenticationFailed() {
                // Never release material on a non-match. Android's sensor keeps
                // its failure counters across prompts; retries are user initiated.
                val activePrompt = prompt
                finish(error = "NOT_RECOGNIZED")
                activePrompt?.cancelAuthentication()
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                finish(error = when (code) {
                    BiometricPrompt.ERROR_LOCKOUT -> "LOCKOUT"
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "LOCKOUT_PERMANENT"
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "CANCELLED"
                    else -> "UNAVAILABLE"
                })
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(if (enabling) "Activar biometría" else "Desbloquear SIMERTPI")
            .setSubtitle("Verifica tu identidad en este dispositivo")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Utilizar contraseña").build()
        try { prompt!!.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
        catch (_: Exception) { finish(error = "UNAVAILABLE") }
    }
}
