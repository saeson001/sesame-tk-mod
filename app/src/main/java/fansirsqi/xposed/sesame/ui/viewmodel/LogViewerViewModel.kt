package fansirsqi.xposed.sesame.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.util.LruCache
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fansirsqi.xposed.sesame.R
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong


/**
 * 日志 UI 状态
 */data class LogUiState(
    val mappingList: List<Int> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val totalCount: Int = 0,
    val autoScroll: Boolean = true,
    /** 分类过滤：空=全部；"森林"/"庄园"/"海洋"/"抽抽乐"/"错误" 等 */
    val category: String = ""
)

/**
 * 日志查看器 ViewModel
 * ✨ 使用防抖 + 原子操作彻底解决重复问题
 */
class LogViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "LogViewerViewModel"

    private val prefs = application.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    private val logFontSizeKey = "pref_font_size"

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState = _uiState.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat(logFontSizeKey, 12f))
    val fontSize = _fontSize.asStateFlow()

    private val _scrollEvent = Channel<Int>(Channel.BUFFERED)
    val scrollEvent = _scrollEvent.receiveAsFlow()

    // 新增：文件更新信号通道 (CONFLATED 表示如果处理不过来，只保留最新的信号)
    private val fileUpdateChannel = Channel<Unit>(Channel.CONFLATED)
    private var fileObserver: FileObserver? = null
    private var currentFilePath: String? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var updateJob: Job? = null // ✅ 新增:文件更新任务

    // --- 核心数据结构 ---
    private var raf: RandomAccessFile? = null
    private val allLineOffsets = ArrayList<Long>()
    private var displayLineOffsets: List<Long> = emptyList()
    private val lineCache = LruCache<Long, String>(200)

    // ✅ 使用 AtomicLong 保证线程安全
    private val lastKnownFileSize = AtomicLong(0L)
    private val maxLines = 200_000

    // ✅ 用于防抖的互斥锁
    private val updateMutex = Mutex()

    @OptIn(FlowPreview::class)
    fun loadLogs(path: String) {
        if (currentFilePath == path && loadJob?.isActive == true) return
        currentFilePath = path

        loadJob?.cancel()
        updateJob?.cancel()

        updateJob = viewModelScope.launch {
            fileUpdateChannel.receiveAsFlow()
                .debounce(200)
                .collectLatest {
                    handleFileUpdate()
                }
        }

        loadJob = viewModelScope.launch {
            closeFile()
            _uiState.update { it.copy(isLoading = true, mappingList = emptyList(), totalCount = 0) }

            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            indexFileContent(file)
            startFileObserver(path)
        }
    }



    private suspend fun indexFileContent(file: File) = withContext(Dispatchers.IO) {
        try {
            val localRaf = RandomAccessFile(file, "r")
            raf = localRaf

            val fileSize = localRaf.length()
            lastKnownFileSize.set(fileSize)
            // ✅ 如果文件大小为 0，直接清空并返回
            if (fileSize == 0L) {
                synchronized(allLineOffsets) { allLineOffsets.clear() }
                lineCache.evictAll()
                refreshList()
                return@withContext
            }

            // ✅ 优化:一次扫描同时完成计数和索引
            val readBuffer = ByteArray(8192)
            var currentOffset = 0L
            var totalLines = 0L

            localRaf.seek(0)

            // 第一遍:快速扫描,只记录换行符位置
            val allOffsets = mutableListOf<Long>()
            allOffsets.add(0L) // 第一行从 0 开始

            while (true) {
                ensureActive()
                val bytesRead = localRaf.read(readBuffer)
                if (bytesRead == -1) break

                for (i in 0 until bytesRead) {
                    currentOffset++
                    if (readBuffer[i] == '\n'.code.toByte()) {
                        totalLines++
                        // 记录下一行的起始位置
                        if (currentOffset < fileSize) {
                            allOffsets.add(currentOffset)
                        }
                    }
                }
            }

            // ✅ 根据总行数决定保留哪些行
            val finalOffsets = if (totalLines > maxLines) {
                // 只保留最后 maxLines 行
                allOffsets.takeLast(maxLines)
            } else {
                allOffsets
            }

            synchronized(allLineOffsets) {
                allLineOffsets.clear()
                allLineOffsets.addAll(finalOffsets)
            }

            lineCache.evictAll()
            refreshList()

        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMsg = "索引失败: ${e.message}"
            Log.error(tag, errorMsg)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
                ToastUtil.showToast(getApplication(), errorMsg)
            }
        }
    }

    private suspend fun refreshList() {
        val query = _uiState.value.searchQuery.trim()
        val cat = _uiState.value.category

        val resultOffsets = withContext(Dispatchers.IO) {
            synchronized(allLineOffsets) {
                if (query.isEmpty() && cat.isEmpty()) {
                    ArrayList(allLineOffsets)
                } else {
                    allLineOffsets.filter { offset ->
                        ensureActive()
                        val line = readLineAt(offset)
                        if (line == null) return@filter false
                        if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) {
                            return@filter false
                        }
                        if (cat.isNotEmpty() && !matchCategory(line, cat)) {
                            return@filter false
                        }
                        true
                    }
                }
            }
        }

        displayLineOffsets = resultOffsets
        val newMapping = List(resultOffsets.size) { it }

        _uiState.update {
            it.copy(
                mappingList = newMapping,
                totalCount = resultOffsets.size,
                isLoading = false,
                isSearching = false
            )
        }

        if (_uiState.value.autoScroll && resultOffsets.isNotEmpty()) {
            _scrollEvent.send(resultOffsets.size - 1)
        }
    }

    /** 分类关键字：把一行日志归到某个设置分类 */
    private fun matchCategory(line: String, category: String): Boolean {
        return when (category) {
            "错误" -> ERROR_PATTERNS.any { line.contains(it, ignoreCase = true) }
            "全部" -> true
            else -> {
                val keys = CATEGORY_KEYWORDS[category]
                if (keys == null) true
                else keys.any { line.contains(it, ignoreCase = true) }
            }
        }
    }

    /** 切换分类（再点一次取消） */
    fun setCategory(category: String) {
        val newCat = if (_uiState.value.category == category) "" else category
        _uiState.update { it.copy(category = newCat, isSearching = true) }
        viewModelScope.launch {
            delay(150)
            refreshList()
        }
    }

    /** 各分类的匹配关键字（与设置里的分类对应） */
    companion object {        /** 分类名 -> 日志中出现的关键字（任一命中即算该分类） */
        val CATEGORY_KEYWORDS = linkedMapOf(
            "森林" to listOf("AntForest", "森林", "forest", "能量", "种树"),
            "庄园" to listOf("AntFarm", "庄园", "farm", "小鸡", "饲料", " manure", "orchardSign"),
            "海洋" to listOf("AntOcean", "海洋", "ocean", "oceanS"),
            "果园" to listOf("AntOrchard", "果园", "orchard", "种果"),
            "运动" to listOf("AntSports", "运动", "sports", "走路", "步数"),
            "会员" to listOf("AntMember", "会员", "member", "玩乐豆", "乐豆", "游戏中心"),
            "抽抽乐" to listOf("ChouChouLe", "chouchoule", "抽抽乐", "drawMachine", "drawLotteryPlus", "ipDraw", "IP抽抽乐"),
            "其他" to listOf("GameCenter", "WelfareCenter", "SesameCredit", "ConfigTask", "游戏中心", "福利中心", "芝麻信用", "配置任务")
        )

        /** 错误日志特征 */
        val ERROR_PATTERNS = listOf(
            "异常", "失败", "错误", "Error", "Exception", "error:", "超时", "timeout"
        )

        /** 分类下拉菜单的显示顺序 */
        val CATEGORY_ORDER = listOf("森林", "庄园", "海洋", "果园", "运动", "会员", "抽抽乐", "其他", "错误")
    }

    fun getLineContent(position: Int): String {
        if (position !in displayLineOffsets.indices) return ""
        val offset = displayLineOffsets[position]

        val cachedLine = lineCache.get(offset)
        if (cachedLine != null) {
            return cachedLine
        }

        val line = readLineAt(offset) ?: " [读取错误]"
        lineCache.put(offset, line)
        return line
    }

    private fun readLineAt(offset: Long): String? {
        val localRaf = raf ?: return null

        return try {
            synchronized(localRaf) {
                localRaf.seek(offset)
                val lineBytes = localRaf.readLine()?.toByteArray(StandardCharsets.ISO_8859_1)
                lineBytes?.let { bytes -> String(bytes, StandardCharsets.UTF_8) }
            }
        } catch (e: Exception) {
            Log.printStackTrace(tag, "readLineAt failed at offset $offset", e)
            null
        }
    }

    private fun startFileObserver(path: String) {
        val file = File(path)
        val parentPath = file.parent ?: return
        fileObserver?.stopWatching()
        val eventMask = FileObserver.MODIFY or FileObserver.CREATE
        val observerFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) file else File(parentPath)

        val onFileEvent: (String?) -> Unit = { p ->
            val eventFileName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else p
            if (eventFileName == null || eventFileName == file.name) {
                // ✅ 触发防抖更新
                triggerDebouncedUpdate()
            }
        }

        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(observerFile, eventMask) {
                override fun onEvent(event: Int, p: String?) { onFileEvent(p) }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(observerFile.absolutePath, eventMask) {
                override fun onEvent(event: Int, p: String?) { onFileEvent(p) }
            }
        }
        fileObserver?.startWatching()
    }

    private fun triggerDebouncedUpdate() {
        fileUpdateChannel.trySend(Unit)
    }

    private suspend fun handleFileUpdate() = withContext(Dispatchers.IO) {
        val path = currentFilePath ?: return@withContext
        val file = File(path)
        if (!file.exists()) return@withContext

        // ✅ 使用互斥锁确保同一时刻只有一个更新在执行
        try {
            updateMutex.withLock {
                ensureActive() // 在获取锁后立即检查协程状态
                
                val currentSize = file.length()
                val lastSize = lastKnownFileSize.get()

                when {
                    currentSize > lastSize -> appendNewLines(currentSize)
                    currentSize < lastSize -> {
                        withContext(Dispatchers.Main) { loadLogs(path) }
                    }
                }
            }
        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            // ✅ 只记录真正的异常
            Log.printStackTrace(tag, "handleFileUpdate failed", e)
        }
    }

    private suspend fun appendNewLines(currentFileSize: Long) = withContext(Dispatchers.IO) {
        val localRaf = raf ?: return@withContext
        try {
            val startPosition = lastKnownFileSize.get()

            // ✅ 再次验证,防止并发问题
            if (currentFileSize <= startPosition) {
                return@withContext
            }

            val newOffsets = mutableListOf<Long>()
            val readBuffer = ByteArray(8192) // 8KB 缓冲区

            synchronized(localRaf) {
                localRaf.seek(startPosition)
                var currentOffset = startPosition
                // ✅ 读取新增的内容，找到所有换行符位置
                while (currentOffset < currentFileSize) {
                    ensureActive()
                    val remainingBytes = (currentFileSize - currentOffset).toInt()
                    val bytesToRead = minOf(readBuffer.size, remainingBytes)
                    if (bytesToRead <= 0) break
                    val bytesRead = localRaf.read(readBuffer, 0, bytesToRead)
                    if (bytesRead == -1) break
                    // ✅ 遍历读取的字节，找到换行符
                    for (i in 0 until bytesRead) {
                        currentOffset++
                        if (readBuffer[i] == '\n'.code.toByte()) {
                            // 记录下一行的起始位置
                            if (currentOffset < currentFileSize) {
                                newOffsets.add(currentOffset)
                            }
                        }
                    }
                }
            }
            
            if (newOffsets.isNotEmpty()) {
                // ✅ 先更新文件大小,再修改列表
                lastKnownFileSize.set(currentFileSize)
                synchronized(allLineOffsets) {
                    allLineOffsets.addAll(newOffsets)
                    while (allLineOffsets.size > maxLines) {
                        allLineOffsets.removeAt(0)
                    }
                }
                refreshList()
            }
        } catch (e: CancellationException) {
            // ✅ 协程取消异常不记录日志，直接静默处理
            // 这是正常的协程生命周期管理，不需要打印错误
            throw e // 重新抛出让协程框架处理
        } catch (e: Exception) {
            Log.printStackTrace(tag, "appendNewLines failed", e)
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) {
                delay(300)
            }
            refreshList()
        }
    }

    fun clearLogFile(context: Context) {
        val path = currentFilePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (Files.clearFile(File(path))) {
                    withContext(Dispatchers.Main) {
                        ToastUtil.showToast(context, "文件已清空")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        ToastUtil.showToast(context, "清空失败")
                    }
                }
            } catch (e: Exception) {
                Log.printStackTrace(tag, "Clear error", e)
                withContext(Dispatchers.Main) {
                    ToastUtil.showToast(context, "清空异常: ${e.message}")
                }
            }
        }
    }

    fun exportLogFile(context: Context) {
        val path = currentFilePath ?: return
        try {
            val file = File(path)
            if (!file.exists()) {
                ToastUtil.showToast(context, "源文件不存在")
                return
            }
            val exportFile = Files.exportFile(file, true)
            if (exportFile != null && exportFile.exists()) {
                val msg = "${context.getString(R.string.file_exported)} ${exportFile.path}"
                ToastUtil.showToast(context, msg)
            } else {
                ToastUtil.showToast(context, "导出失败")
            }
        } catch (e: Exception) {
            Log.printStackTrace(tag, "Export error", e)
            ToastUtil.showToast(context, "导出异常: ${e.message}")
        }
    }

    private fun saveFontSize(size: Float) {
        prefs.edit { putFloat(logFontSizeKey, size) }
    }

    fun increaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current + 2f).coerceAtMost(30f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun decreaseFontSize() {
        _fontSize.update { current ->
            val newValue = (current - 2f).coerceAtLeast(8f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun scaleFontSize(factor: Float) {
        _fontSize.update { current ->
            val newValue = (current * factor).coerceIn(8f, 50f)
            saveFontSize(newValue)
            newValue
        }
    }

    fun resetFontSize() {
        _fontSize.value = 12f
        saveFontSize(12f)
    }

    fun toggleAutoScroll(enabled: Boolean) {
        if (_uiState.value.autoScroll == enabled) return
        _uiState.update { it.copy(autoScroll = enabled) }
        if (enabled) viewModelScope.launch {
            val size = _uiState.value.mappingList.size
            if (size > 0) _scrollEvent.send(size - 1)
        }
    }

    /**
     * 把当前日志发送到电脑（POST 到 receive_rpc.py 的 /upload）
     * @param url 形如 http://192.168.1.10:8765
     * @return 成功返回服务器响应，失败返回错误信息
     */
    suspend fun sendLogToPc(url: String): String = withContext(Dispatchers.IO) {
        val path = currentFilePath
        if (path.isNullOrEmpty()) return@withContext "未打开日志文件"
        val file = File(path)
        if (!file.exists()) return@withContext "日志文件不存在"

        // 读取当前筛选后的内容（限制体积，避免超大文件传输失败）
        val sb = StringBuilder()
        var total = 0
        for (offset in displayLineOffsets) {
            if (total > 512 * 1024) break
            val line = readLineAt(offset) ?: continue
            sb.append(line).append('\n')
            total += line.length
        }
        if (sb.isEmpty()) return@withContext "当前筛选结果为空，无内容可发送"

        try {
            // 脚本支持的 JSON 格式：{"name":..., "content":...}
            val json = org.json.JSONObject().apply {
                put("name", file.name)
                put("content", sb.toString())
            }.toString()

            val sep = if (url.contains("://")) "" else "://"
            val full = "$url$sep/upload?token=sesame&name=${java.net.URLEncoder.encode(file.name, "UTF-8")}"
            val conn = (java.net.URL(full).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val resp = runCatching { conn.inputStream.bufferedReader().readText() }.getOrDefault("")
            if (code == 200) {
                "已发送 ${sb.length / 1024} KB 到电脑\n\n$resp"
            } else {
                "电脑返回 HTTP $code"
            }
        } catch (e: Throwable) {
            "发送失败: ${e.message}"
        }
    }

    fun closeFile_() = Unit

    private fun closeFile() {
        try {
            // updateJob?.cancel()
            raf?.close()
            raf = null
            fileObserver?.stopWatching()
            fileObserver = null
        } catch (e: Exception) {
            Log.printStackTrace(tag, "closeFile failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeFile()
        loadJob?.cancel()
        searchJob?.cancel()
        updateJob?.cancel()
    }
}