package ua.gardenapple.itchupdater

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import ua.gardenapple.itchupdater.client.web.DownloadRequester
import ua.gardenapple.itchupdater.client.web.UpdateCheckWebTask
import android.content.IntentFilter
import androidx.core.content.ContextCompat.getSystemService
import android.icu.lang.UCharacter.GraphemeClusterBreak.T



const val LOGGING_TAG: String = "Mitch"

const val PERMISSION_REQUEST_CODE_DOWNLOAD = 1

const val NOTIFICATION_CHANNEL_ID_UPDATES = "updates"

const val NOTIFICATION_ID_DOWNLOAD = 20000

class App : Application(),
        ActivityCompat.OnRequestPermissionsResultCallback {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_install)
            val descriptionText = getString(R.string.notification_channel_install_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID_UPDATES, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST_CODE_DOWNLOAD -> {
                if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    DownloadRequester.resumeDownload(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                }
            }
        }
    }
}