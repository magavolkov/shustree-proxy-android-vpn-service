package ru.shustree.shustreeproxy.data

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * A singleton object to manage the persistent, unique device ID for the entire application.
 * It ensures the ID is created only once and retrieved from SharedPreferences on subsequent launches.
 */
object DeviceIdManager {

    @Volatile
    private var deviceId: String? = null

    fun getOrCreateDeviceId(context: Context): String {
        deviceId?.let { return it }

        return synchronized(this) {
            deviceId?.let { return it }

            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            // Если ANDROID_ID по какой-то причине пустой или сбойный — используем фоллбэк
            val finalId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                UUID.nameUUIDFromBytes(androidId.toByteArray(Charsets.UTF_8)).toString()
            } else {
                // Если с ANDROID_ID совсем беда — берем системный BUILD fingerprint
                val fallback = android.os.Build.FINGERPRINT + android.os.Build.SERIAL
                UUID.nameUUIDFromBytes(fallback.toByteArray(Charsets.UTF_8)).toString()
            }

            deviceId = finalId
            finalId
        }
    }
}
