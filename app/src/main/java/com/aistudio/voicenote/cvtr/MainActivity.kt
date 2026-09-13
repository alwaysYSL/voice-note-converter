package com.aistudio.voicenote.cvtr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.aistudio.voicenote.cvtr.ui.AppNavigation
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val incomingUri = mutableStateOf<Uri?>(null)
    private var importJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        incomingUri = incomingUri.value,
                        onIncomingUriHandled = { incomingUri.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val declaredType = intent.type
        val streamUri = when {
            Intent.ACTION_SEND == action -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data ?: intent.clipData?.getItemAt(0)?.uri
            }
            Intent.ACTION_VIEW == action -> intent.data
            else -> null
        }
        if (streamUri != null && isSupportedMediaUri(streamUri, declaredType)) {
            persistAndSetUri(streamUri, intent.flags)
        }
    }

    private fun isSupportedMediaUri(uri: Uri, declaredType: String?): Boolean {
        val resolverType = runCatching { contentResolver.getType(uri) }.getOrNull()
        val fallbackType = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val mimeType = resolverType ?: declaredType ?: fallbackType ?: return false
        return mimeType.startsWith("audio/", ignoreCase = true) ||
            mimeType.startsWith("video/", ignoreCase = true)
    }

    private fun persistAndSetUri(uri: Uri, intentFlags: Int) {
        val canPersist = intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        val persisted = if (canPersist) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            }.getOrElse {
                Log.w("MainActivity", "URI tidak dapat dibuat persistable: $uri", it)
                false
            }
        } else {
            false
        }

        if (persisted || uri.scheme != "content") {
            incomingUri.value = uri
            return
        }

        importJob?.cancel()
        importJob = lifecycleScope.launch(Dispatchers.IO) {
            val cachedUri = copyUriToPrivateCache(uri)
            withContext(Dispatchers.Main.immediate) {
                cachedUri?.let { incomingUri.value = it }
            }
        }
    }

    private fun copyUriToPrivateCache(uri: Uri): Uri? {
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..8 }
            ?.let { ".$it" }
            ?: ".media"
        val target = File(cacheDir, "import_${UUID.randomUUID()}$extension")
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(target)
        } catch (error: Exception) {
            target.delete()
            Log.w("MainActivity", "Gagal menyalin URI media ke cache privat", error)
            null
        }
    }
}
