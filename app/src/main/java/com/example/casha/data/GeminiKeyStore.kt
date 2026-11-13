package com.example.casha.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GeminiKeyStore(context:Context,val scope:String) {
    private val prefs=context.getSharedPreferences("gemini_$scope",Context.MODE_PRIVATE)
    private val alias="casha.gemini.$scope"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        } catch (_: Exception) {
            runCatching { if (ks.containsAlias(alias)) ks.deleteEntry(alias) }
        }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        } catch (_: Exception) {
            val raw = ByteArray(32) { (it * 7 + 13).toByte() }
            javax.crypto.spec.SecretKeySpec(raw, "AES")
        }
    }
    fun save(value:String):Boolean {
        val clean = value.trim().removeSurrounding("\"").removeSurrounding("'").substringAfter("=").trim()
        require(clean.length in 10..300) { "Key format is invalid. Check for extra characters." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        return prefs.edit().putString("key",Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()
    }
    fun read():String? {
        val value=prefs.getString("key",null)?:return null
        val iv=prefs.getString("iv",null)?:return null
        return runCatching {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
    fun maskedKey():String? {
        val raw = read() ?: return null
        if (raw.length <= 8) return "••••••••"
        return raw.take(6) + "••••••••" + raw.takeLast(4)
    }
    fun exists()=prefs.contains("key") && prefs.contains("iv")
    fun remove():Boolean {
        val ok=prefs.edit().clear().commit()
        if(ok) runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
        return ok
    }
}
