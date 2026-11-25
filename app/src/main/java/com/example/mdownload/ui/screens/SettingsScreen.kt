package com.example.mdownload.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mdownload.AppTab
import com.example.mdownload.MainViewModel
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.AudioFormat
import com.example.mdownload.core.EngineState
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.player.ui.ReplayGainSettingsContent
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassPanel
import com.example.mdownload.ui.components.HiFiGlowProgressBar
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.HiFiTopChrome
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun bi(en: String, vi: String): String =
    if (LocalConfiguration.current.locales[0].language == "vi") vi else en

@Composable
private fun SettingsGroup(
    id: String,
    title: String,
    summary: String,
    initial: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = HiFiTheme.colors
    var expanded by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("settings-section-$id")
                .clickable { expanded = !expanded }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.inkPrimary)
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
            }
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = if (expanded) bi("Collapse", "Thu gọn") else bi("Expand", "Mở rộng"),
                tint = tokens.inkSecondary
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
        HorizontalDivider(thickness = 0.65.dp, color = tokens.divider)
    }
}

@Composable
private fun Toggle(
    title: String,
    description: String,
    value: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit
) {
    val tokens = HiFiTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.inkPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
        }
        Switch(
            checked = value,
            onCheckedChange = change,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.onAccent,
                checkedTrackColor = tokens.accent,
                uncheckedThumbColor = tokens.inkSecondary,
                uncheckedTrackColor = tokens.glassElevated,
                uncheckedBorderColor = tokens.glassRimTop
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(
    label: String,
    choices: List<Pair<String, String>>,
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    val tokens = HiFiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = tokens.inkPrimary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { (key, title) ->
                HiFiGlassChip(
                    label = title,
                    selected = value == key,
                    onClick = { if (enabled) onChange(key) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    bottomContentPadding: Dp = HiFiSpacing.dockWithMiniPlayerClearance
) {
    val tokens = HiFiTheme.colors
    val s by viewModel.settings.collectAsState()
    val gainState by viewModel.gainState.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val running by viewModel.isQueueRunning.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val storage by viewModel.storageState.collectAsState()
    val busy by viewModel.settingsBusy.collectAsState()
    val syncing by viewModel.isLibrarySyncing.collectAsState()
    val policy by viewModel.policyMessage.collectAsState()
    val context = LocalContext.current
    val idle = !running && jobs.none { it.status.isActive } && !busy && engine !is EngineState.Updating
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.selectStorageFolder(uri)
        }
    }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var licenses by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshStorage() }

    fun openSystem(action: String) {
        runCatching {
            context.startActivity(Intent(action).apply { data = Uri.parse("package:${context.packageName}") })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = bi("Settings", "Cài đặt"),
            subtitle = bi("Your music, your preferences", "Tùy chỉnh cách bạn tải nhạc")
        )

        Column(
            Modifier
                .fillMaxSize()
                .testTag("settings-list")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HiFiSpacing.lg)
        ) {
            Text(
                bi(
                    "Changes apply to newly queued tracks. Existing tracks keep their profile.",
                    "Thay đổi áp dụng cho bài thêm mới. Bài đã xếp hàng giữ cấu hình riêng."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            SettingsGroup("audio", bi("Audio & files", "Âm thanh và tệp"), TextUtils.formatLabel(s.toJobOptions()), true) {
                Choices(
                    bi("Format", "Định dạng"),
                    AudioFormat.entries.map { it.id to it.displayName },
                    s.audioFormat.id
                ) { viewModel.setAudioFormat(AudioFormat.fromId(it)) }
                if (s.audioFormat.bitrates.isNotEmpty()) {
                    Choices(
                        bi("Bitrate", "Bitrate"),
                        s.audioFormat.bitrates.map { it.toString() to "$it kbps" },
                        s.audioQuality.toString()
                    ) { viewModel.setAudioQuality(it.toInt()) }
                }
                Text(
                    bi(
                        "Higher bitrate cannot improve the original source. Original keeps source audio without re-encoding.",
                        "Bitrate cao hơn không cải thiện nguồn gốc. Original giữ audio nguồn, không mã hóa lại."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                Toggle(
                    bi("Embed artwork", "Nhúng ảnh bìa"),
                    bi("Save the video thumbnail in the audio file.", "Lưu ảnh thu nhỏ của video vào tệp nhạc."),
                    s.embedArtwork,
                    change = viewModel::setEmbedArtwork
                )
                Toggle(
                    bi("ASCII filenames", "Tên tệp không dấu"),
                    bi(
                        "Use simple filenames for older USB players. Source tags are preserved.",
                        "Bỏ dấu tên tệp cho đầu phát USB cũ. Giữ nguyên tag từ nguồn."
                    ),
                    s.asciiNames,
                    change = viewModel::setAsciiNames
                )
                Toggle(
                    bi("Block duplicate downloads", "Chặn tải trùng bài"),
                    bi(
                        "Differentiates tracks by YouTube video code and hidden metadata inside saved files.",
                        "Phân biệt trùng qua mã video YouTube và metadata ẩn trong tệp nhạc đã lưu."
                    ),
                    s.skipDuplicates,
                    change = viewModel::setSkipDuplicates
                )
                Toggle(
                    bi("Instant download on Share", "Tải ngay khi bấm Chia sẻ"),
                    bi(
                        "When sharing a track via Share → AuthDio, start downloading immediately in the background without showing the popup sheet.",
                        "Khi chọn Chia sẻ → AuthDio cho một bài hát, tự động tải nền ngay lập tức mà không cần mở bảng xác nhận."
                    ),
                    s.instantShareDownload,
                    change = viewModel::setInstantShareDownload
                )
            }

            val playbackSummary = buildString {
                append(if (s.scanAllDeviceMusic) bi("All local music", "Toàn bộ nhạc trên máy") else bi("AuthDio folder only", "Chỉ thư mục AuthDio"))
                append(" · ")
                append(String.format(java.util.Locale.US, "%.2gx", s.defaultPlaybackSpeed))
            }
            SettingsGroup("playback", bi("Music player & library", "Trình phát nhạc và thư viện"), playbackSummary) {
                Toggle(
                    bi("Show all local audio on device", "Hiển thị toàn bộ nhạc trên máy"),
                    bi(
                        "Browse MP3, M4A, Opus, FLAC, and OGG files across MediaStore in addition to AuthDio downloads.",
                        "Duyệt các tệp MP3, M4A, Opus, FLAC và OGG trong MediaStore bên cạnh nhạc tải từ AuthDio."
                    ),
                    s.scanAllDeviceMusic,
                    change = viewModel::setScanAllDeviceMusic
                )
                Toggle(
                    bi("Restore playback session on launch", "Khôi phục phiên phát khi mở ứng dụng"),
                    bi(
                        "Remember your last queue, track, and position for instant offline resumption.",
                        "Lưu lại hàng đợi phát, bài đang nghe và vị trí để tiếp tục nghe ngoại tuyến."
                    ),
                    s.restorePlaybackOnLaunch,
                    change = viewModel::setRestorePlaybackOnLaunch
                )
                Toggle(
                    bi("Automatically fetch lyrics after download", "Tự tìm lời bài hát sau khi tải"),
                    bi(
                        "Looks up synchronized lyrics from LRCLIB in the background after each track saves, without delaying downloads.",
                        "Tìm lời bài hát đồng bộ từ LRCLIB ở chế độ nền sau khi tải xong, không làm chậm quá trình tải nhạc."
                    ),
                    s.autoFetchLyricsAfterDownload,
                    change = viewModel::setAutoFetchLyricsAfterDownload
                )
                Toggle(
                    bi("Embed fetched lyrics into audio files", "Nhúng lời bài hát vào tệp nhạc"),
                    bi(
                        "Optional: safely writes high-confidence lyrics into MP3 (ID3v2), FLAC, M4A, or Ogg/Opus tags after download.",
                        "Tùy chọn: ghi an toàn lời bài hát khớp độ tin cậy cao vào thẻ MP3 (ID3v2), FLAC, M4A hoặc Ogg/Opus sau khi tải."
                    ),
                    s.embedLyricsAfterDownload,
                    enabled = s.autoFetchLyricsAfterDownload,
                    change = viewModel::setEmbedLyricsAfterDownload
                )
                Choices(
                    bi("Default playback speed", "Tốc độ phát mặc định"),
                    listOf("0.75" to "0.75×", "1.0" to "1.0×", "1.25" to "1.25×", "1.5" to "1.5×"),
                    String.format(java.util.Locale.US, "%.2f", s.defaultPlaybackSpeed)
                        .trimEnd('0').trimEnd('.').let { if (it == "1") "1.0" else it }
                ) { viewModel.setDefaultPlaybackSpeed(it.toFloat()) }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = bi("Rescan library", "Quét lại thư viện"),
                        onClick = { viewModel.syncStorageFolder(showFeedback = true) },
                        enabled = !syncing,
                        primary = false
                    )
                    HiFiPillActionButton(
                        label = bi("Clear artwork cache", "Xóa bộ nhớ đệm ảnh bìa"),
                        onClick = viewModel::clearArtworkCache,
                        primary = false
                    )
                }
                Text(
                    bi(
                        "Local playback uses AndroidX Media3 ExoPlayer and MediaSessionService directly with standard system, lock-screen, and Bluetooth controls—no Python runtime is needed to play music.",
                        "Phát nhạc ngoại tuyến dùng trực tiếp AndroidX Media3 ExoPlayer và MediaSessionService với điều khiển hệ thống, màn hình khóa và Bluetooth chuẩn Android — không cần chạy Python khi nghe nhạc."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }

            val rgMode = ReplayGainMode.fromId(s.replayGainMode)
            val rgSummary = when (rgMode) {
                ReplayGainMode.OFF -> bi("Off", "Tắt")
                ReplayGainMode.TRACK -> bi("Track", "Bài hát") + " · ${ReplayGainCalculator.formatDb(s.replayGainPreampDb)}"
                ReplayGainMode.ALBUM -> bi("Album", "Album") + " · ${ReplayGainCalculator.formatDb(s.replayGainPreampDb)}"
            }
            SettingsGroup(
                id = "replaygain",
                title = bi("ReplayGain & loudness", "ReplayGain & cân bằng âm lượng"),
                summary = rgSummary
            ) {
                val isVi = LocalConfiguration.current.locales[0].language == "vi"
                ReplayGainSettingsContent(
                    settings = s,
                    gainState = gainState,
                    bi = { en, vi -> if (isVi) vi else en },
                    onSelectMode = viewModel::setReplayGainMode,
                    onChangePreampDb = viewModel::setReplayGainPreampDb,
                    onSelectMissingBehavior = viewModel::setReplayGainMissingBehavior,
                    onChangeFallbackPreampDb = viewModel::setReplayGainFallbackPreampDb,
                    onTogglePreventClipping = viewModel::setReplayGainPreventClipping
                )
            }

            SettingsGroup(
                "storage",
                bi("Storage & recovery", "Lưu trữ và khôi phục"),
                "${s.effectiveStorageLabel} · " + bi("Free: ", "Trống: ") + TextUtils.formatFileSize(storage.freeBytes)
            ) {
                val isCustomFolder = s.storageTreeUri.isNotBlank()
                Text(
                    if (isCustomFolder) {
                        bi(
                            "Destination: ${s.effectiveStorageLabel} (custom folder). New downloads are saved here, and Library syncs tracks & duplicate checks from this folder.",
                            "Nơi lưu: ${s.effectiveStorageLabel} (thư mục tùy chọn). Nhạc tải mới sẽ lưu tại đây, đồng thời Thư viện và kiểm tra trùng sẽ đồng bộ trực tiếp từ thư mục này."
                        )
                    } else {
                        bi(
                            "Destination: internal shared storage / ${s.effectiveStorageLabel}. You can pick another folder to manage and sync tracks from it.",
                            "Nơi lưu: bộ nhớ dùng chung / ${s.effectiveStorageLabel}. Bạn có thể chọn thư mục khác để lưu và đồng bộ quản lý bài nhạc từ đó."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = bi("Choose storage folder", "Chọn thư mục lưu"),
                        onClick = { folderPicker.launch(null) },
                        enabled = idle,
                        primary = true
                    )
                    HiFiPillActionButton(
                        label = bi("Sync folder now", "Đồng bộ thư mục"),
                        onClick = { viewModel.syncStorageFolder(showFeedback = true) },
                        enabled = !syncing,
                        primary = false
                    )
                    if (isCustomFolder) {
                        TextButton(onClick = viewModel::resetStorageFolderToDefault, enabled = idle) {
                            Text(bi("Use default (Music/MDownload)", "Dùng mặc định (Music/MDownload)"), color = tokens.accent)
                        }
                    }
                    HiFiPillActionButton(
                        label = bi("Open audio library", "Mở thư viện âm thanh"),
                        onClick = { viewModel.selectTab(AppTab.LIBRARY) },
                        primary = false
                    )
                }
                Text(
                    bi("Temporary files: ", "Tệp tạm: ") + TextUtils.formatFileSize(storage.temporaryBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkPrimary
                )
                Text(
                    bi("Recoverable audio: ", "Audio có thể khôi phục: ") + TextUtils.formatFileSize(storage.recoveryBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HiFiPillActionButton(
                        label = bi("Clear temporary files", "Dọn tệp tạm"),
                        onClick = { confirm = "clear" to "" },
                        enabled = idle && storage.temporaryBytes > 0,
                        primary = false
                    )
                    TextButton(onClick = viewModel::refreshStorage) {
                        Text(bi("Refresh storage", "Làm mới dung lượng"), color = tokens.accent)
                    }
                }
                Text(
                    bi(
                        "Saved music and pending audio are kept. Stop downloads before cleaning or retrying exports.",
                        "Giữ nhạc đã lưu và audio chờ khôi phục. Dừng tải trước khi dọn hoặc thử lưu lại."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                if (storage.pending.isEmpty()) {
                    Text(bi("No pending exports.", "Không có audio chờ lưu."), style = MaterialTheme.typography.bodySmall, color = tokens.inkTertiary)
                }
                storage.pending.forEach { item ->
                    HiFiGlassPanel(
                        modifier = Modifier.testTag("pending-${item.id}"),
                        contentPadding = 12.dp
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = tokens.inkPrimary)
                        Text(TextUtils.formatFileSize(item.bytes), style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HiFiPillActionButton(
                                label = bi("Retry save", "Thử lưu lại"),
                                onClick = { viewModel.storageAction("retry", item.id) },
                                enabled = idle,
                                primary = true
                            )
                            TextButton(onClick = { confirm = "discard" to item.id }, enabled = idle) {
                                Text(bi("Remove copy", "Xóa bản tạm"), color = tokens.error)
                            }
                        }
                    }
                }
                if (busy || syncing) HiFiGlowProgressBar(progress = null, height = 3.dp)
            }

            val networkSummary = buildString {
                append(if (s.wifiOnly) bi("Wi-Fi only", "Chỉ Wi-Fi") else bi("Wi-Fi & mobile data", "Wi-Fi và dữ liệu di động"))
                append(" · ")
                if (s.gentleRequests) {
                    append(bi("Gentle (1×1)", "Nhẹ (1×1)"))
                } else {
                    append(
                        bi(
                            "${s.effectiveParallelDownloads} files · ${s.effectiveDownloadThreads} threads/file",
                            "${s.effectiveParallelDownloads} file · ${s.effectiveDownloadThreads} luồng/file"
                        )
                    )
                }
            }
            SettingsGroup("network", bi("Network & background", "Mạng và tải nền"), networkSummary) {
                Toggle(
                    bi("Wi-Fi only", "Chỉ tải qua Wi-Fi"),
                    bi(
                        "Pause and keep tracks queued when Wi-Fi disconnects. Resume automatically on Wi-Fi.",
                        "Tạm dừng và giữ hàng đợi khi mất Wi-Fi. Tự tiếp tục khi có Wi-Fi."
                    ),
                    s.wifiOnly
                ) { enabled -> viewModel.updatePreferences { it.copy(wifiOnly = enabled) } }
                Toggle(
                    bi("Allow background downloads", "Cho phép tải nền"),
                    bi("Continue with a progress notification when you leave the app.", "Tiếp tục tải kèm thông báo tiến trình khi rời ứng dụng."),
                    s.allowBackground
                ) { enabled -> viewModel.updatePreferences { it.copy(allowBackground = enabled) } }
                Choices(
                    bi("Simultaneous downloads (files)", "Số bài tải đồng thời"),
                    (1..AppSettings.MAX_PARALLEL_DOWNLOADS).map { it.toString() to "$it" },
                    s.effectiveParallelDownloads.toString()
                ) { viewModel.setParallelDownloads(it.toInt()) }
                Text(
                    bi(
                        "Download multiple songs at once (1–6). Higher values finish playlists much faster.",
                        "Tải nhiều bài hát cùng lúc (1–6). Số cao hơn giúp tải danh sách phát nhanh hơn rõ rệt."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                Choices(
                    bi("Threads per file", "Số luồng tải mỗi tệp"),
                    listOf(1, 2, 4, 6, 8).map { it.toString() to "$it" },
                    s.effectiveDownloadThreads.toString()
                ) { viewModel.setDownloadThreads(it.toInt()) }
                Text(
                    bi(
                        "Splits each track into concurrent fragment streams with 10 MB HTTP chunks to avoid single-stream throttling.",
                        "Chia mỗi tệp nhạc thành nhiều luồng tải song song kèm chunk HTTP 10 MB để tăng tốc từng bài."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                Toggle(
                    bi("Gentle requests", "Yêu cầu nhẹ (chống giới hạn)"),
                    bi(
                        "Forces 1 file × 1 thread and waits 7 seconds between tracks if YouTube rate-limits your network.",
                        "Ép xuống 1 bài × 1 luồng và chờ 7 giây giữa các bài nếu YouTube giới hạn mạng của bạn."
                    ),
                    s.gentleRequests,
                    change = viewModel::setGentleRequests
                )
                if (policy.isNotBlank()) {
                    Text(com.example.mdownload.ui.localText(policy), style = MaterialTheme.typography.bodySmall, color = tokens.accentBright)
                }
                val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val powerManager = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
                val batteryUnrestricted = remember(context, policy) {
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                }
                Text(
                    bi("Notifications: ", "Thông báo: ") + if (permitted) bi("Allowed", "Đã cho phép") else bi("Not allowed", "Chưa cho phép"),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkPrimary
                )
                Text(
                    bi("Background battery: ", "Pin chạy nền: ") + if (batteryUnrestricted) {
                        bi("Unrestricted (screen-off ready)", "Không giới hạn (sẵn sàng tải khi tắt màn hình)")
                    } else {
                        bi("Restricted (OriginOS/Android may stop screen-off downloads)", "Bị giới hạn (OriginOS/Android có thể dừng tải khi tắt màn hình)")
                    },
                    color = if (batteryUnrestricted) tokens.success else tokens.inkSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = bi("Unrestricted battery (OriginOS)", "Cho phép chạy nền (OriginOS)"),
                        onClick = {
                            val opened = runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                true
                            }.getOrDefault(false)
                            if (!opened) openSystem(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        },
                        primary = false
                    )
                    HiFiPillActionButton(
                        label = bi("Notification settings", "Cài đặt thông báo"),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        },
                        primary = false
                    )
                    TextButton(onClick = { openSystem(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) {
                        Text(bi("App permissions", "Quyền ứng dụng"), color = tokens.accent)
                    }
                }
                Text(
                    bi(
                        "Uses a foreground service with WakeLock & Wi-Fi lock while downloading. On OriginOS, set Battery → High background power usage for uninterrupted screen-off downloads.",
                        "Dùng Foreground Service kèm WakeLock và khóa Wi-Fi khi tải. Trên OriginOS, bật Pin → Mức tiêu thụ năng lượng nền cao để tải mượt khi tắt màn hình."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }

            SettingsGroup("appearance", bi("Appearance & language", "Giao diện và ngôn ngữ"), bi("Personalize the app", "Cá nhân hóa ứng dụng")) {
                Choices(
                    bi("Theme", "Giao diện"),
                    listOf("system" to bi("System", "Theo hệ thống"), "light" to bi("Light", "Sáng"), "dark" to bi("Dark", "Tối")),
                    s.themeMode
                ) { value -> viewModel.updatePreferences { it.copy(themeMode = value) } }
                Choices(
                    bi("App language", "Ngôn ngữ ứng dụng"),
                    listOf("system" to bi("System", "Theo hệ thống"), "vi" to "Tiếng Việt", "en" to "English"),
                    s.language,
                    onChange = viewModel::setLanguage
                )
                Text(
                    bi(
                        "Video titles, YouTube pages and original diagnostic messages keep their source language.",
                        "Tên video, trang YouTube và log chẩn đoán giữ ngôn ngữ gốc."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }

            SettingsGroup(
                "updates",
                bi("Updates & connection", "Cập nhật và kết nối"),
                when (engine) {
                    is EngineState.Ready -> "yt-dlp " + (engine as EngineState.Ready).ytDlpVersion
                    is EngineState.Updating -> bi("Updating…", "Đang cập nhật…")
                    is EngineState.Error -> bi("Engine error", "Lỗi bộ tải")
                    else -> bi("Starting…", "Đang khởi động…")
                }
            ) {
                Text(
                    bi("Stable channel is recommended. Updates require an internet connection.", "Khuyên dùng kênh Stable. Cần Internet để cập nhật."),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                Toggle(
                    bi("Automatic engine updates", "Tự cập nhật bộ tải"),
                    bi("Check Stable on Wi-Fi at app startup, at most once per day.", "Kiểm tra Stable khi mở app qua Wi-Fi, tối đa một lần mỗi ngày."),
                    s.autoUpdate,
                    enabled = idle
                ) { v -> viewModel.updatePreferences { it.copy(autoUpdate = v) } }
                val date = remember(s.lastUpdateCheck) {
                    runCatching {
                        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(s.lastUpdateCheck))
                    }.getOrDefault("—")
                }
                Text(bi("Last check: ", "Lần kiểm tra gần nhất: ") + date, style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
                if (s.lastUpdateResult.isNotBlank()) {
                    Text(s.lastUpdateResult, style = MaterialTheme.typography.bodySmall, color = tokens.inkSecondary)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = bi("Check Stable update", "Kiểm tra cập nhật Stable"),
                        onClick = { viewModel.updateYtDlp(false) },
                        enabled = idle && engine is EngineState.Ready,
                        primary = true
                    )
                    HiFiPillActionButton(
                        label = bi("Test real MP3 download", "Kiểm tra tải MP3 thực tế"),
                        onClick = viewModel::testRealDownload,
                        enabled = idle && engine is EngineState.Ready,
                        primary = false
                    )
                }
                Text(
                    bi(
                        "The test downloads audio to temporary storage without adding it to your music library.",
                        "Phép thử tải audio vào bộ nhớ tạm, không thêm vào thư viện nhạc."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                var advanced by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(bi("Advanced engine options", "Tùy chọn bộ tải nâng cao"), color = tokens.accent)
                }
                if (advanced) {
                    Text(
                        bi(
                            "Nightly contains newer changes and may be less stable. Switch back using Stable update.",
                            "Nightly có thay đổi mới, có thể kém ổn định. Chuyển lại bằng cập nhật Stable."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                    HiFiPillActionButton(
                        label = bi("Update Nightly", "Cập nhật Nightly"),
                        onClick = { viewModel.updateYtDlp(true) },
                        enabled = idle && engine is EngineState.Ready,
                        primary = false
                    )
                }
                if (engine is EngineState.Updating) HiFiGlowProgressBar(progress = null, height = 3.dp)
                if (engine is EngineState.Error) {
                    Text((engine as EngineState.Error).message, color = tokens.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsGroup("support", bi("Support & diagnostics", "Hỗ trợ và chẩn đoán"), bi("Logs, reset and troubleshooting", "Log, đặt lại và xử lý lỗi")) {
                Text(
                    bi(
                        "Logs can contain video titles, links and local paths. Review before sharing.",
                        "Log có thể chứa tên video, liên kết và đường dẫn. Kiểm tra trước khi chia sẻ."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                HiFiGlassPanel(contentPadding = 12.dp) {
                    SelectionContainer {
                        Text(
                            logs.takeLast(30).joinToString("\n").ifBlank { bi("No diagnostic entries.", "Chưa có log chẩn đoán.") },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.inkSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HiFiPillActionButton(
                        label = bi("Share diagnostics", "Chia sẻ chẩn đoán"),
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "AuthDio diagnostics")
                                .putExtra(Intent.EXTRA_TEXT, viewModel.diagnosticsReport())
                            context.startActivity(Intent.createChooser(share, "AuthDio diagnostics"))
                        },
                        primary = false
                    )
                    TextButton(onClick = { confirm = "logs" to "" }) {
                        Text(bi("Clear logs", "Xóa log"), color = tokens.accent)
                    }
                    TextButton(onClick = { confirm = "reset" to "" }, enabled = idle) {
                        Text(bi("Reset preferences", "Đặt lại cài đặt"), color = tokens.error)
                    }
                }
            }

            SettingsGroup("about", bi("About AuthDio", "Giới thiệu AuthDio"), "AuthDio 1.2 · Obsidian Orchid") {
                Text(bi("An offline audio library for Android.", "Thư viện nhạc ngoại tuyến trên Android."), color = tokens.inkPrimary)
                Text(
                    bi(
                        "Downloads are stored on your device. YouTube and engine updates connect to their respective services. No account is required for supported public videos.",
                        "Nhạc được lưu trên thiết bị. YouTube và cập nhật bộ tải kết nối tới dịch vụ tương ứng. Video công khai được hỗ trợ không yêu cầu tài khoản."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                Text(
                    bi(
                        "Save audio only when you have permission. Access restrictions may prevent some downloads.",
                        "Chỉ lưu audio khi bạn có quyền. Một số video có giới hạn truy cập sẽ không tải được."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
                HiFiPillActionButton(
                    label = bi("Open-source licenses", "Giấy phép nguồn mở"),
                    onClick = { licenses = true },
                    primary = false
                )
            }

            Spacer(Modifier.height(bottomContentPadding))
        }
    }

    confirm?.let { (action, id) ->
        val title = when (action) {
            "clear" -> bi("Clear temporary files?", "Dọn tệp tạm?")
            "discard" -> bi("Remove pending audio?", "Xóa audio chờ lưu?")
            "logs" -> bi("Clear diagnostic logs?", "Xóa log chẩn đoán?")
            else -> bi("Reset preferences?", "Đặt lại cài đặt?")
        }
        val detail = when (action) {
            "clear" -> bi(
                "Clear ${TextUtils.formatFileSize(storage.temporaryBytes)} of temporary data. Saved music, pending audio and history are kept.",
                "Dọn ${TextUtils.formatFileSize(storage.temporaryBytes)} dữ liệu tạm. Giữ nhạc đã lưu, audio chờ lưu và lịch sử."
            )
            "discard" -> bi(
                "Delete this local recovery copy. Saved music is kept. You will need to download again if no saved copy exists.",
                "Xóa bản khôi phục này. Giữ nhạc đã lưu. Cần tải lại nếu chưa có bản đã lưu."
            )
            "logs" -> bi("Only diagnostic messages are removed. Download history and music are kept.", "Chỉ xóa thông tin chẩn đoán. Giữ lịch sử tải và nhạc.")
            else -> bi(
                "Restore audio, network, theme and language defaults. Saved music, history and queued tracks are kept.",
                "Khôi phục cài đặt âm thanh, mạng, giao diện và ngôn ngữ. Giữ nhạc, lịch sử và bài đang chờ."
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text(title, color = tokens.inkPrimary) },
            text = { Text(detail, color = tokens.inkSecondary) },
            confirmButton = {
                TextButton(
                    enabled = idle || action == "logs",
                    onClick = {
                        confirm = null
                        when (action) {
                            "reset" -> viewModel.resetPreferences()
                            "logs" -> viewModel.clearLogs()
                            else -> viewModel.storageAction(action, id)
                        }
                    }
                ) { Text(bi("Confirm", "Xác nhận"), color = tokens.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(bi("Cancel", "Hủy"), color = tokens.inkSecondary)
                }
            }
        )
    }

    if (licenses) {
        AlertDialog(
            onDismissRequest = { licenses = false },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text(bi("Open-source licenses", "Giấy phép nguồn mở"), color = tokens.inkPrimary) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "yt-dlp: Unlicense\nyoutubedl-android: GPL-3.0\nFFmpeg: LGPL/GPL, according to bundled components\nQuickJS: MIT\nPython: PSF License\nAndroidX, Compose, Media3 (ExoPlayer & MediaSession) and Coil: Apache-2.0\nKotlin: Apache-2.0",
                        color = tokens.inkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JunkFood02/youtubedl-android")))
                    }) { Text(bi("Runtime source & licenses", "Mã nguồn và giấy phép runtime"), color = tokens.accent) }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/androidx/media")))
                    }) { Text("AndroidX Media3", color = tokens.accent) }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yt-dlp/yt-dlp")))
                    }) { Text("yt-dlp", color = tokens.accent) }
                }
            },
            confirmButton = {
                TextButton(onClick = { licenses = false }) {
                    Text(bi("Close", "Đóng"), color = tokens.accent)
                }
            }
        )
    }
}
