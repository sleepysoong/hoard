package com.sleepysoong.hoard.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

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

        val placeholder = ChatMessage(id = messageId, role = MessageRole.Assistant, text = "", modelId = modelId, isStreaming = true)
        if (runAttemptCount == 0) {
            // Placeholder streaming bubble owned by the worker.
            repo.appendMessage(sessionId, placeholder)
        } else if (repo.messagesOf(sessionId).none { it.id == messageId }) {
            // The failed bubble was deleted or regenerated during backoff: the user
            // no longer wants this reply, so don't bring it back.
            return Result.success()
        } else {
            // Retry streams into the same bubble instead of appending a duplicate.
            repo.updateMessage(sessionId, messageId) { placeholder.copy(createdAt = it.createdAt) }
        }

        return try {
            promote("Hoard가 생각 중…")
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
                if (!ev.done) promote("Hoard가 답변 중…")
            }
            notifyDone(sessionId)
            Result.success()
        } catch (e: CancellationException) {
            // Cancelled or stopped by the system: never leave a spinning bubble,
            // and let the coroutine machinery see the cancellation.
            repo.updateMessage(sessionId, messageId) {
                it.copy(text = it.text.ifBlank { "(목업) 답변이 중단됐습니다." }, isStreaming = false)
            }
            throw e
        } catch (e: Exception) {
            val willRetry = runAttemptCount + 1 < MAX_ATTEMPTS
            repo.updateMessage(sessionId, messageId) {
                it.copy(
                    text = if (willRetry) "(목업) 연결이 끊겨 곧 다시 시도합니다…" else "(목업) 답변이 중단됐습니다.",
                    isStreaming = false
                )
            }
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    /**
     * Foreground promotion is best-effort. Some devices/OEMs refuse dataSync FGS
     * (or the platform drops the type declaration), and a reply must never be
     * lost because of a notification — WorkManager keeps running as background work.
     */
    private suspend fun promote(text: String) {
        try {
            setForeground(foregroundInfo(text))
        } catch (_: Throwable) {
            // Intentionally ignored: continue as regular background work.
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "hoard-replies")
            .setContentTitle("Hoard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setSilent(true)
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
            .setContentText("답변이 준비됐습니다 (목업).")
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

        /** First run + 2 retries; a dead backend must not spin forever. */
        const val MAX_ATTEMPTS = 3

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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("hoard-reply-$sessionId")
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
