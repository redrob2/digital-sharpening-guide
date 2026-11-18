package io.github.redrob2.sharpguide.firmware

import io.github.redrob2.sharpguide.protocol.Release
import io.github.redrob2.sharpguide.protocol.ReleaseAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object GitHub {
    const val DEFAULT_REPO = "redrob2/digital-sharpening-guide"
    private const val UA = "SharpeningGuide-Android"
    private const val MAX_IMAGE = 8 * 1024 * 1024

    suspend fun latestRelease(repo: String): Release = withContext(Dispatchers.IO) {
        val clean = repo.trim().trim('/')
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(clean)) { "Repo must look like owner/name" }
        val conn = open("https://api.github.com/repos/$clean/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("No published release found for $clean")
            if (code != 200) throw IOException("GitHub returned HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.optJSONArray("assets")
            val assets = (0 until (arr?.length() ?: 0)).map {
                val a = arr!!.getJSONObject(it)
                ReleaseAsset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
            }
            Release(json.optString("tag_name"), json.optString("name"), assets)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(url: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = open(url)
            conn.setRequestProperty("Accept", "application/octet-stream")
            try {
                val code = conn.responseCode
                if (code != 200) throw IOException("Download failed: HTTP $code")
                val total = conn.contentLengthLong
                if (total > MAX_IMAGE) throw IOException("File is too large to be firmware ($total bytes)")
                val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 1 shl 20)
                val buf = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_IMAGE) throw IOException("File is too large to be firmware")
                        onProgress(out.size().toLong(), total)
                    }
                }
                out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
}
