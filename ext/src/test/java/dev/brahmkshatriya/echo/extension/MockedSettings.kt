package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings

class MockedSettings : Settings {
    private val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String): Boolean? = values[key] as Boolean?
    override fun getInt(key: String): Int? = values[key] as Int?
    override fun getString(key: String): String? = values[key] as String?
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String): Set<String>? = values[key] as Set<String>?

    private fun put(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun putBoolean(key: String, value: Boolean?) = put(key, value)
    override fun putInt(key: String, value: Int?) = put(key, value)
    override fun putString(key: String, value: String?) = put(key, value)
    override fun putStringSet(key: String, value: Set<String>?) = put(key, value)
}
