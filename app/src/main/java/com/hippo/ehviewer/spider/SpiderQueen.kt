/*
 * Copyright 2016 Hippo Seven
 * Rewrite with Kotlin coroutines, Tarsin Norbin 2023
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.util.Log
import androidx.annotation.IntDef
import androidx.collection.LongSparseArray
import androidx.collection.set
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUrl.getGalleryDetailUrl
import com.hippo.ehviewer.client.EhUrl.getGalleryMultiPageViewerUrl
import com.hippo.ehviewer.client.EhUrl.referer
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.exception.QuotaExceededException
import com.hippo.ehviewer.client.parser.GalleryDetailParser.parsePages
import com.hippo.ehviewer.client.parser.GalleryDetailParser.parsePreviewPages
import com.hippo.ehviewer.client.parser.GalleryDetailParser.parsePreviewSet
import com.hippo.ehviewer.client.parser.GalleryMultiPageViewerPTokenParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.image.Image
import com.hippo.unifile.UniFile
import com.hippo.util.ExceptionUtils
import com.hippo.util.launchIO
import com.hippo.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.executeAsync
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import com.hippo.ehviewer.EhApplication.Companion.okHttpClient as plainTextOkHttpClient

class SpiderQueen private constructor(val galleryInfo: GalleryInfo) : CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    @Volatile
    lateinit var mPageStateArray: IntArray
    lateinit var mSpiderInfo: SpiderInfo

    val mSpiderDen: SpiderDen = SpiderDen(galleryInfo)
    private val mPageStateLock = Any()
    private val mDownloadedPages = AtomicInteger(0)
    private val mFinishedPages = AtomicInteger(0)
    private val mSpiderListeners: MutableList<OnSpiderListener> = ArrayList()

    private var mReadReference = 0
    private var mDownloadReference = 0

    fun addOnSpiderListener(listener: OnSpiderListener) {
        synchronized(mSpiderListeners) { mSpiderListeners.add(listener) }
    }

    fun removeOnSpiderListener(listener: OnSpiderListener) {
        synchronized(mSpiderListeners) { mSpiderListeners.remove(listener) }
    }

    private fun notifyGetPages(pages: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach { it.onGetPages(pages) }
        }
    }

    fun notifyGet509(index: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach { it.onGet509(index) }
        }
    }

    fun notifyPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onPageDownload(
                    index,
                    contentLength,
                    receivedSize,
                    bytesRead,
                )
            }
        }
    }

    private fun notifyPageSuccess(index: Int) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onPageSuccess(
                    index,
                    mFinishedPages.get(),
                    mDownloadedPages.get(),
                    mPageStateArray.size,
                )
            }
        }
    }

    private fun notifyPageFailure(index: Int, error: String?) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onPageFailure(
                    index,
                    error,
                    mFinishedPages.get(),
                    mDownloadedPages.get(),
                    mPageStateArray.size,
                )
            }
        }
    }

    private fun notifyAllPageDownloaded() {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onFinish(
                    mFinishedPages.get(),
                    mDownloadedPages.get(),
                    mPageStateArray.size,
                )
            }
        }
    }

    fun notifyGetImageSuccess(index: Int, image: Image) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onGetImageSuccess(index, image)
            }
        }
    }

    fun notifyGetImageFailure(index: Int, error: String) {
        synchronized(mSpiderListeners) {
            mSpiderListeners.forEach {
                it.onGetImageFailure(index, error)
            }
        }
    }

    private var downloadMode = false
    val isReady
        get() = this::mSpiderInfo.isInitialized && this::mPageStateArray.isInitialized

    @Synchronized
    private fun updateMode() {
        if (!isReady) return
        val mode: Int = if (mDownloadReference > 0) {
            MODE_DOWNLOAD
        } else {
            MODE_READ
        }
        mSpiderDen.mode = mode

        // Update download page
        val intoDownloadMode = mode == MODE_DOWNLOAD
        if (intoDownloadMode && !downloadMode) {
            // Clear download state
            synchronized(mPageStateLock) {
                val temp: IntArray = mPageStateArray
                var i = 0
                val n = temp.size
                while (i < n) {
                    val oldState = temp[i]
                    if (STATE_DOWNLOADING != oldState) {
                        temp[i] = STATE_NONE
                    }
                    i++
                }
                mDownloadedPages.lazySet(0)
                mFinishedPages.lazySet(0)
            }
            mWorkerScope.enterDownloadMode()
        }
        downloadMode = intoDownloadMode
    }

    private fun setMode(@Mode mode: Int) {
        when (mode) {
            MODE_READ -> mReadReference++
            MODE_DOWNLOAD -> mDownloadReference++
        }
        check(mDownloadReference <= 1) { "mDownloadReference can't more than 1" }
    }

    private fun clearMode(@Mode mode: Int) {
        when (mode) {
            MODE_READ -> mReadReference--
            MODE_DOWNLOAD -> mDownloadReference--
        }
        check(!(mReadReference < 0 || mDownloadReference < 0)) { "Mode reference < 0" }
    }

    private val prepareJob = launchIO { doPrepare() }

    private suspend fun doPrepare() {
        mSpiderInfo = readSpiderInfoFromLocal() ?: readSpiderInfoFromInternet() ?: return
        mPageStateArray = IntArray(mSpiderInfo.pages)
        notifyGetPages(mSpiderInfo.pages)
    }

    suspend fun awaitReady(): Boolean {
        prepareJob.join()
        return isReady
    }

    suspend fun awaitStartPage(): Int {
        prepareJob.join()
        if (!isReady) return 0
        return mSpiderInfo.startPage
    }

    private fun stop() {
        val queenScope = this
        launchIO {
            queenScope.cancel()
            runCatching {
                writeSpiderInfoToLocal()
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    val size
        get() = mPageStateArray.size

    fun forceRequest(index: Int) {
        request(index, true)
    }

    fun request(index: Int) {
        request(index, false)
    }

    private fun getPageState(index: Int): Int {
        synchronized(mPageStateLock) {
            return if (index >= 0 && index < mPageStateArray.size) {
                mPageStateArray[index]
            } else {
                STATE_NONE
            }
        }
    }

    fun cancelRequest(index: Int) {
        mWorkerScope.cancelDecode(index)
    }

    fun preloadPages(pages: List<Int>, pair: Pair<Int, Int>) {
        mWorkerScope.updateRAList(pages, pair)
    }

    private fun request(index: Int, force: Boolean) {
        // Get page state
        val state = getPageState(index)

        // Fix state for force
        if (force && (state == STATE_FINISHED || state == STATE_FAILED) || state == STATE_FAILED) {
            // Update state to none at once
            updatePageState(index, STATE_NONE)
        }
        mWorkerScope.launch(index, force)
    }

    fun save(index: Int, file: UniFile): Boolean {
        val state = getPageState(index)
        return if (STATE_FINISHED != state) {
            false
        } else {
            mSpiderDen.saveToUniFile(index, file)
        }
    }

    fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val state = getPageState(index)
        if (STATE_FINISHED != state) {
            return null
        }
        val ext = mSpiderDen.getExtension(index)
        val dst = dir.subFile(if (null != ext) "$filename.$ext" else filename) ?: return null
        return if (!mSpiderDen.saveToUniFile(index, dst)) null else dst
    }

    fun getExtension(index: Int): String? {
        val state = getPageState(index)
        return if (STATE_FINISHED != state) {
            null
        } else {
            mSpiderDen.getExtension(index)
        }
    }

    val startPage: Int
        get() = mSpiderInfo.startPage

    fun putStartPage(page: Int) {
        mSpiderInfo.startPage = page
    }

    private fun readSpiderInfoFromLocal(): SpiderInfo? {
        return mSpiderDen.downloadDir?.run {
            findFile(SPIDER_INFO_FILENAME)?.let { file ->
                SpiderInfo.readCompatFromUniFile(file)?.takeIf {
                    it.gid == galleryInfo.gid && it.token == galleryInfo.token
                }
            }
        }
            ?: SpiderInfo.readFromCache(galleryInfo.gid)?.takeIf { it.gid == galleryInfo.gid && it.token == galleryInfo.token }
    }

    private fun readPreviews(body: String, index: Int, spiderInfo: SpiderInfo) {
        spiderInfo.previewPages = parsePreviewPages(body)
        val previewSet = parsePreviewSet(body)
        if (previewSet.size() > 0) {
            if (index == 0) {
                spiderInfo.previewPerPage = previewSet.size()
            } else {
                spiderInfo.previewPerPage = previewSet.getPosition(0) / index
            }
        }
        var i = 0
        val n = previewSet.size()
        while (i < n) {
            val result = GalleryPageUrlParser.parse(previewSet.getPageUrlAt(i))
            if (result != null) {
                spiderInfo.pTokenMap[result.page] = result.pToken
            }
            i++
        }
    }

    private suspend fun readSpiderInfoFromInternet(): SpiderInfo? {
        val request = EhRequestBuilder(
            getGalleryDetailUrl(
                galleryInfo.gid,
                galleryInfo.token,
                0,
                false,
            ),
            referer,
        ).build()
        return runSuspendCatching {
            plainTextOkHttpClient.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                val pages = parsePages(body)
                val spiderInfo = SpiderInfo(galleryInfo.gid, pages)
                spiderInfo.token = galleryInfo.token
                readPreviews(body, 0, spiderInfo)
                spiderInfo
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    suspend fun getPTokenFromMultiPageViewer(index: Int): String? {
        val spiderInfo = mSpiderInfo
        val url = getGalleryMultiPageViewerUrl(
            galleryInfo.gid,
            galleryInfo.token!!,
        )
        val referer = referer
        val request = EhRequestBuilder(url, referer).build()
        try {
            plainTextOkHttpClient.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                val list = GalleryMultiPageViewerPTokenParser.parse(body)
                for (i in list.indices) {
                    spiderInfo.pTokenMap[i] = list[i]
                }
                return spiderInfo.pTokenMap[index]
            }
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            return null
        }
    }

    suspend fun getPTokenFromInternet(index: Int): String? {
        val spiderInfo = mSpiderInfo

        // Check previewIndex
        var previewIndex: Int
        previewIndex = if (spiderInfo.previewPerPage >= 0) {
            index / spiderInfo.previewPerPage
        } else {
            0
        }
        if (spiderInfo.previewPages > 0) {
            previewIndex = previewIndex.coerceAtMost(spiderInfo.previewPages - 1)
        }
        val url = getGalleryDetailUrl(
            galleryInfo.gid,
            galleryInfo.token,
            previewIndex,
            false,
        )
        val referer = referer
        val request = EhRequestBuilder(url, referer).build()
        try {
            plainTextOkHttpClient.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                readPreviews(body, previewIndex, spiderInfo)
                return spiderInfo.pTokenMap[index]
            }
        } catch (e: Throwable) {
            ExceptionUtils.throwIfFatal(e)
            return null
        }
    }

    @Synchronized
    private fun writeSpiderInfoToLocal() {
        if (!isReady) return
        mSpiderDen.downloadDir?.run { createFile(SPIDER_INFO_FILENAME).also { mSpiderInfo.write(it) } }
        mSpiderInfo.saveToCache()
    }

    private fun isStateDone(state: Int): Boolean {
        return state == STATE_FINISHED || state == STATE_FAILED
    }

    @JvmOverloads
    fun updatePageState(index: Int, @State state: Int, error: String? = null) {
        var oldState: Int
        synchronized<Unit>(mPageStateLock) {
            oldState = mPageStateArray[index]
            mPageStateArray[index] = state
            if (!isStateDone(oldState) && isStateDone(state)) {
                mDownloadedPages.incrementAndGet()
            } else if (isStateDone(oldState) && !isStateDone(state)) {
                mDownloadedPages.decrementAndGet()
            }
            if (oldState != STATE_FINISHED && state == STATE_FINISHED) {
                mFinishedPages.incrementAndGet()
            } else if (oldState == STATE_FINISHED && state != STATE_FINISHED) {
                mFinishedPages.decrementAndGet()
            }
        }

        // Notify listeners
        if (state == STATE_FAILED) {
            notifyPageFailure(index, error)
        } else if (state == STATE_FINISHED) {
            notifyPageSuccess(index)
        }
        if (mDownloadedPages.get() == size) notifyAllPageDownloaded()
    }

    @IntDef(MODE_READ, MODE_DOWNLOAD)
    @Retention
    annotation class Mode

    @IntDef(STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED)
    @Retention(AnnotationRetention.SOURCE)
    annotation class State
    interface OnSpiderListener {
        fun onGetPages(pages: Int)
        fun onGet509(index: Int)
        fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int)
        fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int)
        fun onPageFailure(index: Int, error: String?, finished: Int, downloaded: Int, total: Int)
        fun onFinish(finished: Int, downloaded: Int, total: Int)
        fun onGetImageSuccess(index: Int, image: Image?)
        fun onGetImageFailure(index: Int, error: String?)
    }

    private val mWorkerScope = object {
        private val mFetcherJobMap = hashMapOf<Int, Job>()
        private val mSemaphore = Semaphore(Settings.downloadThreadCount)
        private val pTokenLock = Mutex()
        private var showKey: String? = null
        private val showKeyLock = Mutex()
        private val mDownloadDelay = Settings.downloadDelay.toLong()
        private val downloadTimeout = Settings.downloadTimeout.seconds
        private val delayLock = Mutex()
        private var delayedTime = 0L
        private var isDownloadMode = false

        fun cancelDecode(index: Int) {
            decoder.cancel(index)
        }

        @Synchronized
        fun enterDownloadMode() {
            if (isDownloadMode) return
            updateRAList((0 until size).toList())
            isDownloadMode = true
        }

        fun updateRAList(list: List<Int>, cancelBounds: Pair<Int, Int> = 0 to Int.MAX_VALUE) {
            if (isDownloadMode) return
            synchronized(mFetcherJobMap) {
                mFetcherJobMap.forEach { (i, job) ->
                    if (i < cancelBounds.first || i > cancelBounds.second) {
                        job.cancel()
                    }
                }
                list.forEach {
                    if (mFetcherJobMap[it]?.isActive != true) {
                        doLaunchDownloadJob(it, false)
                    }
                }
            }
        }

        private fun doLaunchDownloadJob(index: Int, force: Boolean) {
            val state = mPageStateArray[index]
            if (!force && state == STATE_FINISHED) return
            val currentJob = mFetcherJobMap[index]
            if (force) currentJob?.cancel()
            if (currentJob?.isActive != true) {
                mFetcherJobMap[index] = launch {
                    mSemaphore.withPermit {
                        doInJob(index, force)
                    }
                }
            }
        }

        fun launch(index: Int, force: Boolean = false) {
            check(index in 0 until size)
            if (!isDownloadMode) synchronized(mFetcherJobMap) { doLaunchDownloadJob(index, force) }
            if (force) decoder.cancel(index)
            decoder.launch(index)
        }

        private suspend fun doInJob(index: Int, force: Boolean) {
            suspend fun getPToken(index: Int): String? {
                if (index !in 0 until size) return null
                return mSpiderInfo.pTokenMap[index].takeIf { it != SpiderInfo.TOKEN_FAILED }
                    ?: getPTokenFromInternet(index)
                    ?: getPTokenFromInternet(index)
                    ?: getPTokenFromMultiPageViewer(index)
            }
            updatePageState(index, STATE_DOWNLOADING)
            if (!force && index in mSpiderDen) {
                return updatePageState(index, STATE_FINISHED)
            }
            if (force) {
                pTokenLock.withLock {
                    val pToken = mSpiderInfo.pTokenMap[index]
                    if (pToken == SpiderInfo.TOKEN_FAILED) mSpiderInfo.pTokenMap.remove(index)
                }
            }
            val previousPToken: String?
            val pToken: String

            pTokenLock.withLock {
                pToken = getPToken(index) ?: return updatePageState(
                    index,
                    STATE_FAILED,
                    PTOKEN_FAILED_MESSAGE,
                ).also {
                    mSpiderInfo.pTokenMap[index] = SpiderInfo.TOKEN_FAILED
                }
                previousPToken = getPToken(index - 1)
            }

            delayLock.withLock {
                val curTime = Instant.now().toEpochMilli()
                delayedTime = (delayedTime + mDownloadDelay).coerceAtLeast(curTime)
                delay(delayedTime - curTime)
            }

            var skipHathKey: String? = null
            var originImageUrl: String? = null
            var error: String? = null
            var forceHtml = false
            runCatching {
                repeat(2) {
                    var imageUrl: String? = null
                    var localShowKey: String?

                    showKeyLock.withLock {
                        localShowKey = showKey
                        if (localShowKey == null || forceHtml) {
                            var pageUrl = EhUrl.getPageUrl(mSpiderInfo.gid, index, pToken)
                            // Skipping H@H costs 50 points, only use it as last resort
                            if (skipHathKey != null) {
                                pageUrl += if ("?" in pageUrl) {
                                    "&nl=$skipHathKey"
                                } else {
                                    "?nl=$skipHathKey"
                                }
                            }
                            EhEngine.getGalleryPage(pageUrl, mSpiderInfo.gid, mSpiderInfo.token)
                                .let { result ->
                                    check509(result.imageUrl)
                                    imageUrl = result.imageUrl
                                    skipHathKey = result.skipHathKey
                                    originImageUrl = result.originImageUrl
                                    localShowKey = result.showKey
                                    showKey = result.showKey
                                }
                        }
                    }

                    if (imageUrl == null) {
                        runSuspendCatching {
                            EhEngine.getGalleryPageApi(
                                mSpiderInfo.gid,
                                index,
                                pToken,
                                localShowKey,
                                previousPToken,
                            ).let {
                                check509(it.imageUrl)
                                imageUrl = it.imageUrl
                                skipHathKey = it.skipHathKey
                                originImageUrl = it.originImageUrl
                            }
                        }.onFailure {
                            forceHtml = true
                            return@repeat
                        }
                    }

                    val targetImageUrl: String?
                    val referer: String?

                    if (Settings.getDownloadOriginImage(mSpiderDen.downloadDir != null) && !originImageUrl.isNullOrBlank()) {
                        val pageUrl = EhUrl.getPageUrl(mSpiderInfo.gid, index, pToken)
                        targetImageUrl = EhEngine.getOriginalImageUrl(originImageUrl!!, pageUrl)
                        referer = EhUrl.referer
                    } else {
                        // Original image url won't change, so only set forceHtml in this case
                        forceHtml = true
                        targetImageUrl = imageUrl
                        referer = null
                    }
                    checkNotNull(targetImageUrl)
                    Log.d(WORKER_DEBUG_TAG, targetImageUrl)

                    repeat(3) { times ->
                        runCatching {
                            Log.d(WORKER_DEBUG_TAG, "Start download image $index attempt #$times")
                            val success = withTimeout(downloadTimeout) {
                                mSpiderDen.makeHttpCallAndSaveImage(
                                    index,
                                    targetImageUrl,
                                    referer,
                                ) { contentLength: Long, receivedSize: Long, bytesRead: Int ->
                                    notifyPageDownload(index, contentLength, receivedSize, bytesRead)
                                }
                            }

                            check(success)
                            Log.d(WORKER_DEBUG_TAG, "Download image $index succeed")
                            updatePageState(index, STATE_FINISHED)
                            return
                        }.onFailure {
                            mSpiderDen.remove(index)
                            Log.d(WORKER_DEBUG_TAG, "Download image $index attempt #$times failed")
                            error = when (it) {
                                is TimeoutCancellationException -> ERROR_TIMEOUT
                                is CancellationException -> throw it
                                else -> ExceptionUtils.getReadableString(it)
                            }
                        }
                    }
                }
            }.onFailure {
                when (it) {
                    is CancellationException -> {
                        Log.d(WORKER_DEBUG_TAG, "Download image $index cancelled")
                        error = "Cancelled"
                        updatePageState(index, STATE_FAILED, error)
                        throw it
                    }

                    is QuotaExceededException -> notifyGet509(index)
                }
                error = ExceptionUtils.getReadableString(it)
            }
            updatePageState(index, STATE_FAILED, error)
        }

        private val decoder = object {
            private val mSemaphore = Semaphore(4)
            private val mDecodeJobMap = hashMapOf<Int, Job>()

            fun cancel(index: Int) {
                synchronized(mDecodeJobMap) {
                    mDecodeJobMap.remove(index)?.cancel()
                }
            }

            fun launch(index: Int) {
                synchronized(mDecodeJobMap) {
                    val currentJob = mDecodeJobMap[index]
                    if (currentJob?.isActive != true) {
                        mDecodeJobMap[index] = launch {
                            doInJob(index)
                        }
                    }
                }
            }

            private suspend fun doInJob(index: Int) {
                mFetcherJobMap[index]?.takeIf { it.isActive }?.join()
                val src = mSpiderDen.getImageSource(index) ?: return
                val image = mSemaphore.withPermit { Image.decode(src) }
                runCatching {
                    currentCoroutineContext().ensureActive()
                }.onFailure {
                    image?.recycle()
                    throw it
                }
                if (image == null) {
                    notifyGetImageFailure(index, DECODE_ERROR)
                } else {
                    notifyGetImageSuccess(index, image)
                }
            }
        }
    }

    companion object {
        const val MODE_READ = 0
        const val MODE_DOWNLOAD = 1
        const val STATE_NONE = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_FINISHED = 2
        const val STATE_FAILED = 3
        const val SPIDER_INFO_FILENAME = ".ehviewer"
        private val sQueenMap = LongSparseArray<SpiderQueen>()
        private val PTOKEN_FAILED_MESSAGE = GetText.getString(R.string.error_get_ptoken_error)
        private val ERROR_TIMEOUT = GetText.getString(R.string.error_timeout)
        private val DECODE_ERROR = GetText.getString(R.string.error_decoding_failed)
        private val URL_509_ARRAY = arrayOf(
            "https://ehgt.org/g/509.gif",
            "https://ehgt.org/g/509s.gif",
            "https://exhentai.org/img/509.gif",
            "https://exhentai.org/img/509s.gif",
        )
        private const val WORKER_DEBUG_TAG = "SpiderQueenWorker"

        private fun check509(url: String) {
            if (url in URL_509_ARRAY) throw QuotaExceededException()
        }

        @JvmStatic
        fun obtainSpiderQueen(galleryInfo: GalleryInfo, @Mode mode: Int): SpiderQueen {
            val gid = galleryInfo.gid
            return (sQueenMap[gid] ?: SpiderQueen(galleryInfo).also { sQueenMap[gid] = it }).apply {
                setMode(mode)
                launchIO { if (awaitReady()) updateMode() }
            }
        }

        @JvmStatic
        fun releaseSpiderQueen(queen: SpiderQueen, @Mode mode: Int) {
            queen.run {
                clearMode(mode)
                if (mReadReference == 0 && mDownloadReference == 0) {
                    stop()
                    sQueenMap.remove(galleryInfo.gid)
                } else {
                    launchIO { if (awaitReady()) updateMode() }
                }
            }
        }
    }
}
