package ua.gardenapple.itchupdater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

class Utils {
    companion object {
        const val LOG_LIMIT: Int = 1000

        /**
         * Logcat normally has a limit of 1000 characters.
         * This function splits long strings into multiple log entries.
         */
        fun logPrintLong(priority: Int, tag: String, string: String) {
            for (i in string.indices step LOG_LIMIT) {
                Log.d(tag, string.substring(i, min(string.length, i + LOG_LIMIT)))
            }
        }

        /**
         * logPrintLong with Debug priority
         */
        fun logLongD(tag: String, string: String) {
            logPrintLong(Log.DEBUG, tag, string)
        }

        fun getCurrentUnixTime(): Long {
            return System.currentTimeMillis() / 1000
        }

        suspend fun copy(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
            val BUFFER_SIZE = 1024 * 1024

            val buffer = ByteArray(BUFFER_SIZE)
            var n = 0
            while(true) {
                n = input.read(buffer)
                if(n == -1)
                    break
                output.write(buffer, 0, n)
            }
        }

        fun Int.hasFlag(flag: Int): Boolean {
            return this and flag == flag
        }
    }
}