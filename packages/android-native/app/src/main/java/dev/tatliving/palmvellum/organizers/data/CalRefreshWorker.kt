package dev.tatliving.palmvellum.organizers.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background periodic refresh of subscribed calendars (WorkManager). */
class CalRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        CalendarSync.refresh(applicationContext).fold({ Result.success() }, { Result.retry() })

    companion object {
        private const val WORK = "cal-refresh"

        /** (Re)schedule the periodic refresh to the user's chosen interval, or
         *  cancel it when set to Off (0 hours). Call on launch and on change. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val hours = CalSubStore(app).intervalHours()
            val wm = WorkManager.getInstance(app)
            if (hours <= 0) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<CalRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
