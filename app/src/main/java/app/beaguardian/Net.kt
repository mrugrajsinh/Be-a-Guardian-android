package app.beaguardian

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Small helpers shared by the activity and the duty service. */
object Net {
    fun request(method: String, path: String, token: String, body: String? = null): Pair<Int, String> {
        val c = URL(BuildConfig.BASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            c.disconnect()
        }
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("bag", Context.MODE_PRIVATE)
    fun token(c: Context): String = p(c).getString("token", "") ?: ""
    fun onDuty(c: Context): Boolean = p(c).getBoolean("onDuty", false)
    fun set(c: Context, token: String, onDuty: Boolean) {
        p(c).edit().putString("token", token).putBoolean("onDuty", onDuty).apply()
    }
    fun clear(c: Context) {
        p(c).edit().clear().apply()
    }
}
