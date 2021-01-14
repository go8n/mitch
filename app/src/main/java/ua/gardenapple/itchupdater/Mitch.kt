package ua.gardenapple.itchupdater

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.work.*
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2okhttp.OkHttpDownloader
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.ocpsoft.prettytime.PrettyTime
import ua.gardenapple.itchupdater.files.DownloadFileManager
import ua.gardenapple.itchupdater.client.UpdateCheckWorker
import ua.gardenapple.itchupdater.database.AppDatabase
import ua.gardenapple.itchupdater.files.ExternalFileManager
import ua.gardenapple.itchupdater.gitlab.GitlabUpdateCheckWorker
import ua.gardenapple.itchupdater.installer.*
import java.io.File
import java.util.concurrent.TimeUnit


const val PERMISSION_REQUEST_DOWNLOADS_VIEW_INTENT = 1
const val PERMISSION_REQUEST_MOVE_TO_DOWNLOADS = 2

const val FILE_PROVIDER = "ua.gardenapple.itchupdater.fileprovider"

const val NOTIFICATION_CHANNEL_ID_UPDATES = "updates_available"
const val NOTIFICATION_CHANNEL_ID_INSTALL_NEEDED = "updates"
const val NOTIFICATION_CHANNEL_ID_INSTALLING = "installing"
const val NOTIFICATION_CHANNEL_ID_WEB_RUNNING = "web_running"

const val NOTIFICATION_ID_SELF_UPDATE_CHECK = 999_999_999
const val NOTIFICATION_TAG_UPDATE_CHECK = "UpdateCheck"
const val NOTIFICATION_TAG_DOWNLOAD = "DownloadResult"
const val NOTIFICATION_TAG_INSTALL_RESULT = "InstallResult"

const val UPDATE_CHECK_TASK_TAG = "update_check"
const val GITLAB_UPDATE_CHECK_TASK_TAG = "gitlab_check"

const val FLAVOR_FDROID = "fdroid"
const val FLAVOR_ITCHIO = "itchio"
const val FLAVOR_GITLAB = "gitlab"

const val PREF_LAST_UPDATE_CHECK = "ua.gardenapple.itchupdater.lastupdatecheck"
const val PREF_UPDATE_CHECKING = "ua.gardenapple.itchupdater.updatechecking"

//TODO: Catch all app's exceptions in a nice way
class Mitch : Application() {

    companion object {
        const val LOGGING_TAG: String = "MitchApp"

        lateinit var httpClient: OkHttpClient
            private set
        private lateinit var fetch: Fetch
        lateinit var fileManager: DownloadFileManager
            private set
        lateinit var databaseHandler: InstallerDatabaseHandler
            private set
        lateinit var externalFileManager: ExternalFileManager
            private set

        val installer: Installer by lazy {
            Installer()
        }
        val prettyTime: PrettyTime by lazy {
            PrettyTime()
        }
    }

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "preference_update_check_if_metered" -> {
                    registerUpdateCheckTask(prefs.getBoolean(key, false),
                        ExistingPeriodicWorkPolicy.REPLACE)
                }
                "preference_theme" -> setThemeFromPreferences(prefs)
                "current_site_theme" -> {
                    if (prefs.getString("preference_theme", "site") == "site") {
                        when (prefs.getString(key, "light")) {
                            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        setThemeFromPreferences(PreferenceManager.getDefaultSharedPreferences(this))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var name = getString(R.string.notification_channel_install)
            var descriptionText = getString(R.string.notification_channel_install_desc)
            var importance = NotificationManager.IMPORTANCE_HIGH
            var channel =
                NotificationChannel(NOTIFICATION_CHANNEL_ID_INSTALL_NEEDED, name, importance).apply {
                    description = descriptionText
                }
            // Register the channel with the system
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_updates)
            descriptionText = getString(R.string.notification_channel_updates_desc)
            importance = NotificationManager.IMPORTANCE_DEFAULT
            channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATES, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_installing)
            descriptionText = getString(R.string.notification_channel_installing_desc)
            importance = NotificationManager.IMPORTANCE_DEFAULT
            channel =
                NotificationChannel(NOTIFICATION_CHANNEL_ID_INSTALLING, name, importance).apply {
                    description = descriptionText
                }
            notificationManager.createNotificationChannel(channel)


            name = getString(R.string.notification_channel_web_running)
            descriptionText = getString(R.string.notification_channel_web_running_desc)
            importance = NotificationManager.IMPORTANCE_LOW
            channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_WEB_RUNNING, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }


        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val workOnMetered = sharedPreferences.getBoolean("preference_update_check_if_metered", true)
        registerUpdateCheckTask(!workOnMetered, ExistingPeriodicWorkPolicy.KEEP)

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        val okHttpCacheDir = File(cacheDir, "OkHttp")
        okHttpCacheDir.mkdirs()
        httpClient = OkHttpClient.Builder().run {
            cache(Cache(
                directory = okHttpCacheDir,
                maxSize = 10 * 1024 * 1024 //10 MB
            ))
            build()
        }
        val fetchConfig = FetchConfiguration.Builder(applicationContext).run {
            setDownloadConcurrentLimit(3)
            setHttpDownloader(OkHttpDownloader(httpClient))
            setAutoRetryMaxAttempts(3)
            enableFileExistChecks(false)
            build()
        }
        fetch = fetchConfig.getNewFetchInstanceFromConfiguration()
        fetch.addListener(FileDownloadListener(applicationContext))
        fileManager = DownloadFileManager(applicationContext, fetch)

        databaseHandler = InstallerDatabaseHandler(applicationContext)
        externalFileManager = ExternalFileManager()
    }

    private fun registerUpdateCheckTask(requiresUnmetered: Boolean, existingWorkPolicy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder().run {
            if(requiresUnmetered)
                setRequiredNetworkType(NetworkType.UNMETERED)
            else
                setRequiredNetworkType(NetworkType.CONNECTED)
            build()
        }
        val updateCheckRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).run {
            //addTag(UPDATE_CHECK_TASK_TAG)
            setConstraints(constraints)
            //setInitialDelay(1, TimeUnit.MINUTES)
            build()
        }

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                UPDATE_CHECK_TASK_TAG,
                existingWorkPolicy,
                updateCheckRequest
            )

        if (BuildConfig.FLAVOR == FLAVOR_GITLAB) {
            val gitlabUpdateCheckRequest =
                PeriodicWorkRequestBuilder<GitlabUpdateCheckWorker>(1, TimeUnit.DAYS).run {
                    setConstraints(constraints)
                    build()
                }

            WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork(
                    GITLAB_UPDATE_CHECK_TASK_TAG,
                    existingWorkPolicy,
                    gitlabUpdateCheckRequest
                )
        }
    }

    private fun setThemeFromPreferences(prefs: SharedPreferences) {
        when (prefs.getString("preference_theme", "site")) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            "site" -> when (prefs.getString("current_site_theme", null)) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }
}