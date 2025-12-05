package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 火山引擎 ASR 2.0 录音文件识别（标准版，submit/query）引擎。
 * - stop() 后先提交任务，再轮询查询结果，最终仅回调 onFinal。
 */
class VolcStandardFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration), PcmBatchRecognizer {

    companion object {
        private const val TAG = "VolcStandardFileAsr"
        private const val RESOURCE_ID_STANDARD = "volc.seedasr.auc"
        private const val SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
        private const val QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
        private const val MAX_POLL_ATTEMPTS = 120
        private const val POLL_INTERVAL_MS = 1000L
    }

    // 火山录音文件识别：服务端上限 2h，本地限制 1h
    override val maxRecordDurationMillis: Int = 60 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(pcm: ByteArray) {
        val requestId = UUID.randomUUID().toString()
        val t0 = System.nanoTime()
        try {
            val wav = pcmToWav(pcm)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val submitJson = buildSubmitRequestJson(b64)
            val submitReq = Request.Builder()
                .url(SUBMIT_URL)
                .addHeader("X-Api-App-Key", prefs.appKey)
                .addHeader("X-Api-Access-Key", prefs.accessKey)
                .addHeader("X-Api-Resource-Id", RESOURCE_ID_STANDARD)
                .addHeader("X-Api-Request-Id", requestId)
                .addHeader("X-Api-Sequence", "-1")
                .post(submitJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(submitReq).execute().use { resp ->
                val status = parseStatusCode(resp)
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
                if (status != 20000000L) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
            }

            val text = pollResult(requestId)
            if (text.isNullOrBlank()) {
                listener.onError(context.getString(R.string.error_asr_empty_result))
                return
            }
            val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            try {
                onRequestDuration?.invoke(dt)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to report request duration", t)
            }
            listener.onFinal(text)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to recognize with Volc standard file API", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun buildSubmitRequestJson(base64Audio: String): String {
        val user = JSONObject().apply { put("uid", prefs.appKey) }
        val audio = JSONObject().apply {
            put("data", base64Audio)
            put("format", "wav")
            put("rate", sampleRate)
            put("bits", 16)
            put("channel", 1)
            val lang = prefs.volcLanguage
            if (lang.isNotBlank()) put("language", lang)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", prefs.volcDdcEnabled)
        }
        return JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }.toString()
    }

    private suspend fun pollResult(requestId: String): String? {
        repeat(MAX_POLL_ATTEMPTS) {
            val queryReq = Request.Builder()
                .url(QUERY_URL)
                .addHeader("X-Api-App-Key", prefs.appKey)
                .addHeader("X-Api-Access-Key", prefs.accessKey)
                .addHeader("X-Api-Resource-Id", RESOURCE_ID_STANDARD)
                .addHeader("X-Api-Request-Id", requestId)
                .addHeader("X-Api-Sequence", "-1")
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(queryReq).execute().use { resp ->
                val status = parseStatusCode(resp)
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return null
                }
                val message = resp.header("X-Api-Message") ?: resp.message
                val bodyStr = resp.body?.string().orEmpty()
                when (status) {
                    20000000L -> {
                        val text = extractText(bodyStr)
                        if (text.isNotBlank()) return text
                        // 成功但未返回文本，按空结果处理
                        return null
                    }
                    20000001L, 20000002L -> { /* 正在处理或排队，继续轮询 */ }
                    20000003L -> {
                        listener.onError(context.getString(R.string.error_asr_empty_result))
                        return null
                    }
                    else -> {
                        val detail = formatHttpDetail(message, "status=${status ?: "unknown"}")
                        listener.onError(
                            context.getString(R.string.error_request_failed_http, resp.code, detail)
                        )
                        return null
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun parseStatusCode(resp: okhttp3.Response): Long? {
        val header = resp.header("X-Api-Status-Code") ?: return null
        return header.toLongOrNull()
    }

    private fun extractText(bodyStr: String): String {
        return try {
            val obj = JSONObject(bodyStr)
            if (obj.has("result")) {
                obj.getJSONObject("result").optString("text", "")
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse standard file ASR result", t)
            ""
        }
    }
}
