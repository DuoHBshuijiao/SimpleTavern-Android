package com.simpletavern.runtime.host

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "st_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun putSecret(id: String, secret: String) {
        prefs.edit().putString(id, secret).apply()
    }

    fun getSecret(id: String): String? = prefs.getString(id, null)

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }
}
