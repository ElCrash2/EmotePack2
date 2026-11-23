package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StickerCollectionEntity::class,
        StickerItemEntity::class,
        WhatsAppSubpackEntity::class,
        EmoteCacheEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun stickerItemDao(): StickerItemDao
    abstract fun subpackDao(): SubpackDao
    abstract fun emoteCacheDao(): EmoteCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sticker_collections ADD COLUMN isAnimatedPack INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_items ADD COLUMN versionUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_items ADD COLUMN versionKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sticker_items ADD COLUMN conversionMode TEXT NOT NULL DEFAULT 'STATIC'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sticker_items_emoteId ON sticker_items(emoteId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS emote_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        emoteId TEXT NOT NULL,
                        versionKey TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        originalFormat TEXT NOT NULL,
                        originalFilePath TEXT,
                        staticWebpPath TEXT,
                        animatedWebpPath TEXT,
                        fakeVidWebpPath TEXT,
                        converterVersion INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emote_cache_emoteId ON emote_cache(emoteId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "emotepack_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
