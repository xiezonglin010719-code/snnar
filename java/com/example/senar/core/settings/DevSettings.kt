package com.example.senar.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

object DevSettings {
    private val KEY_DEV_MODE = booleanPreferencesKey("dev_mode")

    fun devModeFlow(ctx: Context): Flow<Boolean> {
        return ctx.dataStore.data.map { it[KEY_DEV_MODE] ?: false }
    }

    suspend fun setDevMode(ctx: Context, enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_DEV_MODE] = enabled }
    }
}
