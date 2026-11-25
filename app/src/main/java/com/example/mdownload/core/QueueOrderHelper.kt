package com.example.mdownload.core

/**
 * Pure queue ordering operations that keep active downloads at the top while allowing
 * waiting (QUEUED) jobs to be moved up, moved down, or prioritized to the front of the queue.
 */
object QueueOrderHelper {

    fun moveQueuedJob(jobs: List<DownloadJob>, jobId: String, delta: Int): List<DownloadJob> {
        if (delta == 0) return jobs
        val idx = jobs.indexOfFirst { it.id == jobId }
        if (idx < 0 || jobs[idx].status != JobStatus.QUEUED) return jobs

        val step = if (delta < 0) -1 else 1
        var target = idx + step
        while (target in jobs.indices && jobs[target].status != JobStatus.QUEUED) {
            target += step
        }
        if (target !in jobs.indices) return jobs

        val mutable = jobs.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(target, item)
        return mutable
    }

    fun moveQueuedJobToNext(jobs: List<DownloadJob>, jobId: String): List<DownloadJob> {
        val idx = jobs.indexOfFirst { it.id == jobId }
        if (idx < 0 || jobs[idx].status != JobStatus.QUEUED) return jobs

        val firstQueuedIdx = jobs.indexOfFirst { it.status == JobStatus.QUEUED }
        if (firstQueuedIdx < 0 || firstQueuedIdx == idx) return jobs

        val mutable = jobs.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(firstQueuedIdx, item)
        return mutable
    }

    fun canMoveUp(jobs: List<DownloadJob>, jobId: String): Boolean {
        val idx = jobs.indexOfFirst { it.id == jobId }
        if (idx <= 0 || jobs[idx].status != JobStatus.QUEUED) return false
        return jobs.subList(0, idx).any { it.status == JobStatus.QUEUED }
    }

    fun canMoveDown(jobs: List<DownloadJob>, jobId: String): Boolean {
        val idx = jobs.indexOfFirst { it.id == jobId }
        if (idx < 0 || idx >= jobs.lastIndex || jobs[idx].status != JobStatus.QUEUED) return false
        return jobs.subList(idx + 1, jobs.size).any { it.status == JobStatus.QUEUED }
    }
}
