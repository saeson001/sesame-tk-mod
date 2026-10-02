package fansirsqi.xposed.sesame.task.backupSync

import fansirsqi.xposed.sesame.hook.RequestManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV 备份同步（PUT/GET 配置到坚果云等 WebDAV 服务）
 * 配置备份不经过支付宝 RPC，直接走 OkHttp
 */
object BackupSyncClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 上传文本到 WebDAV */
    fun put(url: String, username: String, password: String, content: String): Pair<Boolean, String> {
        return try {
            val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", okhttp3.Credentials.basic(username, password))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) Pair(true, "HTTP ${resp.code}")
                else Pair(false, "HTTP ${resp.code} ${resp.message}")
            }
        } catch (t: Throwable) {
            Pair(false, t.message ?: "unknown error")
        }
    }

    /** 从 WebDAV 下载文本 */
    fun get(url: String, username: String, password: String): Pair<Boolean, String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", okhttp3.Credentials.basic(username, password))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Pair(true, resp.body?.string() ?: "")
                } else {
                    Pair(false, "HTTP ${resp.code} ${resp.message}")
                }
            }
        } catch (t: Throwable) {
            Pair(false, t.message ?: "unknown error")
        }
    }
}
