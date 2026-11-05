package com.notdxbi.dxbi.tv.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One Stremio account kept on this device: its session (the page's "profile": sign-in key, addons,
 * settings) and its people (dx.bi's viewers and what is theirs). Switching accounts swaps these in and
 * starts Stremio's page again, so the password is typed once, when the account is added, and never kept.
 */
data class SavedAccount(
    /** Stremio's user id (the email when it has none); "guest" is browsing without an account */
    val id: String,
    val email: String,
    val avatar: String?,
    /** The page's profile, as the page wrote it ("" for guest) */
    val profile: String,
    /** dx.bi's data for this account's people, as JSON */
    val data: String,
) {
    val isGuest get() = id == GUEST
    val dataObject: JSONObject get() = runCatching { JSONObject(data) }.getOrDefault(JSONObject())

    companion object {
        const val GUEST = "guest"
    }
}

/** The kept accounts: encrypted with a key that never leaves the device's hardware-backed Keystore */
class AccountVault(context: Context) {
    private val prefs = context.getSharedPreferences("lumen-accounts", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String? = runCatching {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    private fun order(): MutableList<String> = prefs.getString("order", null)?.let { runCatching { JSONArray(it).strings() }.getOrNull() }?.toMutableList() ?: mutableListOf()

    fun save(a: SavedAccount) {
        val json = JSONObject().put("id", a.id).put("email", a.email).put("avatar", a.avatar ?: JSONObject.NULL).put("profile", a.profile).put("data", a.data)
        val ids = order()
        if (a.id !in ids) ids += a.id
        prefs.edit().putString("a.${a.id}", runCatching { encrypt(json.toString()) }.getOrNull() ?: return).putString("order", JSONArray(ids).toString()).apply()
    }

    fun load(id: String): SavedAccount? {
        val o = prefs.getString("a.$id", null)?.let { decrypt(it) }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return SavedAccount(o.optString("id"), o.optString("email"), o.text("avatar"), o.optString("profile"), o.optString("data"))
    }

    /** The signed-in accounts that are kept, in the order they were added (guest isn't one) */
    fun all(): List<SavedAccount> = order().mapNotNull { load(it) }.filter { !it.isGuest }

    fun remove(id: String) {
        prefs.edit().remove("a.$id").putString("order", JSONArray(order().filter { it != id }).toString()).apply()
    }

    private companion object {
        const val ALIAS = "lumen.accounts"
    }
}
