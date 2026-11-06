package com.yadown.feature.player

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.yadown.core.player.DownloadingDataSourceFactory
import com.yadown.core.repo.DownloadRepository
import com.yadown.core.theme.YadownTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
@OptIn(ExperimentalMaterial3Api::class)
class PlayerActivity : ComponentActivity() {

    @Inject lateinit var repository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        val intentId = intent.getStringExtra(EXTRA_INTENT_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "播放"

        // 先预热覆盖再创建播放器，避免跨进程重启后对裂解子块无限等待。
        scope.launch {
            runCatching { repository.warmCoverage(intentId) }
            setContent {
                YadownTheme {
                    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
                        PlayerScreen(
                            path = path,
                            intentId = intentId,
                            repository = repository,
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "player.path"
        const val EXTRA_TITLE = "player.title"
        const val EXTRA_INTENT_ID = "player.intentId"
    }
}

@Composable
private fun PlayerScreen(
    path: String,
    intentId: String,
    repository: DownloadRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(path, intentId) {
        val file = File(path)
        val factory = DownloadingDataSourceFactory(path, intentId, repository::isRangeDownloaded)
        val mediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = modifier
    )
}