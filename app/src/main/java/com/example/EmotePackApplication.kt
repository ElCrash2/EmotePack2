package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.AppDatabase
import com.example.data.local.CollectionRepository
import com.example.data.local.LocalStorageManager
import com.example.data.local.PreferencesRepository
import com.example.data.remote.SevenTvApiService
import com.example.data.remote.SevenTvRepository
import com.example.ui.state.SelectionSessionManager
import com.example.worker.PackBuildWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

class EmotePackApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var okHttpClient: OkHttpClient
        private set
    lateinit var sevenTvRepository: SevenTvRepository
        private set
    lateinit var collectionRepository: CollectionRepository
        private set
    lateinit var preferencesRepository: PreferencesRepository
        private set
    lateinit var localStorageManager: LocalStorageManager
        private set
    val selectionSession = SelectionSessionManager()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val httpCacheDir = File(cacheDir, "http_cache").apply { mkdirs() }
        okHttpClient = OkHttpClient.Builder()
            .cache(Cache(httpCacheDir, 50L * 1024L * 1024L))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.7tv.app/")
            .client(okHttpClient)
            .build()

        val api = retrofit.create(SevenTvApiService::class.java)
        sevenTvRepository = SevenTvRepository(api)

        val db = AppDatabase.getInstance(this)
        localStorageManager = LocalStorageManager(this)
        collectionRepository = CollectionRepository(this, db, localStorageManager)
        preferencesRepository = PreferencesRepository(this)

        // Configure global Coil ImageLoader with animated WebP/GIF playback
        val coilCacheDir = File(cacheDir, "image_cache").apply { mkdirs() }
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(coilCacheDir)
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .crossfade(150)
            .build()
        Coil.setImageLoader(imageLoader)

        appScope.launch {
            preferencesRepository.applySavedLanguageOnStartup()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PackBuildWorker.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
