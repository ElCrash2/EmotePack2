package com.example

import android.content.Context

/**
 * Centralized application configuration.
 * Change R.string.app_name to update the app display name and the WhatsApp sticker pack author everywhere.
 */
object AppConfig {
    const val MAX_STICKERS_PER_COLLECTION = 30
    const val MIN_STICKERS_FOR_WHATSAPP = 3
    const val CONVERTER_VERSION = 5

    fun getPackAuthor(context: Context): String = context.getString(R.string.app_name)
}
