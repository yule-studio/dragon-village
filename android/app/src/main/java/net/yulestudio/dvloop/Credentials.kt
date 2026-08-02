package net.yulestudio.dvloop

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 하이브로 계정 저장소.
 *
 * 비밀번호는 평문으로 두지 않는다. Android Keystore 로 감싼
 * EncryptedSharedPreferences 에만 보관한다 (맥 쪽 키체인과 같은 역할).
 */
class Credentials(ctx: Context) {

    private val prefs: SharedPreferences by lazy {
        @Suppress("DEPRECATION")
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        @Suppress("DEPRECATION")
        EncryptedSharedPreferences.create(
            "dv_credentials",
            alias,
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var id: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PW, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_PW, v).apply()

    val isSaved: Boolean get() = id.isNotBlank() && password.isNotBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_ID = "highbrow_id"
        const val KEY_PW = "highbrow_pw"
    }
}
