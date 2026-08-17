package ru.shustree.shustreeproxy.data

import android.content.Context
import java.util.UUID

/**
 * A singleton object to manage the persistent, unique device ID for the entire application.
 * It ensures the ID is created only once and retrieved from SharedPreferences on subsequent launches.
 */
object DeviceIdManager {

    // Define a constant for the preference file and the key for the ID.
    private const val PREFS_FILE_NAME = "shustree_app_prefs"
    private const val KEY_DEVICE_ID = "unique_device_id"

    @Volatile
    private var deviceId: String? = null

    fun getOrCreateDeviceId(context: Context): String {
        deviceId?.let { return it }

        return synchronized(this) {
            deviceId?.let { return it }

            val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)

            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            deviceId = id
            id
        }
    }
}
