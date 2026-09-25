package com.sleepysoong.hoard.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sleepysoong.hoard.R
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.engine.MockAiEngine
import java.util.UUID

/**
 * Continues a mock reply in the background so leaving the app after send
 * still finishes the response. The foreground service type is dataSync.
 */
class ChatResponseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val prompt = inputData.getString(KEY_PROMPT) ?: return Result.failure()
        val modelId = inputData.getString(KEY_MODEL) ?: "hoard-1-pro"
        val messageId = inputData.getString(KEY_MESSAGE) ?: ("msg-" + UUID.randomUUID().toString().take(8))
        val hasAttachments = inputData.getBoolean(KEY_ATTACH, false)
        val repo = HoardRepository.get()

        setForeground(foregroundInfo("Hoard is thinking…"))
        // Placeholder streaming bubble owned by the worker.
        repo.appendMessage(
            sessionId,
            ChatMessage(id = messageId, role = MessageRole.Assistant, text = "", modelId = modelId, isStreaming = true)
        )
        return try {
            MockAiEngine.streamReply(prompt, modelId, hasAttachments) { ev ->
                repo.updateMessage(sessionId, messageId) {
                    it.copy(
                        text = ev.deltaText,
                        thinking = ev.thinking,
                        elapsedMs = ev.elapsedMs,
                        promptTokens = ev.promptTokens,
                        completionTokens = ev.completionTokens,
                        isStreaming = !ev.done
                    )
                }
                if (!ev.done) setForeground(foregroundInfo("Hoard is replying…"))
            }
            notifyDone(sessionId)
            Result.success()
        } catch (e: Exception) {
            repo.updateMessage(sessionId, messageId) {
                it.copy(text = it.text.ifBlank { "(mock) reply interrupted." }, isStreaming = false)
            }
            Result.retry()
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "hoard-replies")
            .setContentTitle("Hoard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, notification)
        }
    }

    private fun notifyDone(sessionId: String) {
        val session = HoardRepository.get().sessionOf(sessionId) ?: return
        val notification = NotificationCompat.Builder(applicationContext, "hoard-replies")
            .setContentTitle("Hoard — ${session.name}")
            .setContentText("Your reply is ready (mock).")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.notify(sessionId.hashCode(), notification)
    }

    companion object {
        const val KEY_SESSION = "session_id"
        const val KEY_PROMPT = "prompt"
        const val KEY_MODEL = "model_id"
        const val KEY_MESSAGE = "message_id"
        const val KEY_ATTACH = "has_attachments"

        fun enqueue(
            ctx: Context,
            sessionId: String,
            prompt: String,
            modelId: String,
            messageId: String,
            hasAttachments: Boolean
        ) {
            val req = OneTimeWorkRequestBuilder<ChatResponseWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION to sessionId,
                        KEY_PROMPT to prompt,
                        KEY_MODEL to modelId,
                        KEY_MESSAGE to messageId,
                        KEY_ATTACH to hasAttachments
                    )
                )
                .addTag("hoard-reply-$sessionId")
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
