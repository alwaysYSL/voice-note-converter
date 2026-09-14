package com.aistudio.voicenote.cvtr.ui
import android.app.Application

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.CardSurfaceWhite
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import com.aistudio.voicenote.cvtr.ui.theme.LightSlateCaption
import com.aistudio.voicenote.cvtr.ui.theme.PastelMintCardBg
import com.aistudio.voicenote.cvtr.editor.ui.EditorLaunchSource
import com.aistudio.voicenote.cvtr.editor.ui.EditorViewModel
import com.aistudio.voicenote.cvtr.editor.ui.EditorUiState

private sealed class Screen(val route: String, val label: String) {
    data object Converter : Screen("converter", "Converter")
    data object History : Screen("history", "Riwayat")
}

private const val EditorRoute = "editor?historyId={historyId}"
private const val EditorRouteBase = "editor"

private val BottomOverlayClearance = 128.dp

@Composable
fun AppNavigation(
    incomingUri: Uri? = null,
    onIncomingUriHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModelFactory = remember(application) { AppViewModelFactory(application) }
    val navController = rememberNavController()
    var pendingEditorLaunch by remember { androidx.compose.runtime.mutableStateOf<EditorLaunchSource?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val screens = listOf(Screen.Converter, Screen.History)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppCanvasBackground)
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Converter.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Converter.route) {
                val converterViewModel: MainViewModel = viewModel(factory = viewModelFactory)
                LaunchedEffect(incomingUri) {
                    incomingUri?.let {
                        converterViewModel.handleIncomingUri(it)
                        onIncomingUriHandled()
                    }
                }
                MainScreen(
                    viewModel = converterViewModel,
                    bottomOverlayClearance = BottomOverlayClearance,
                    onEditConverted = {
                        val state = converterViewModel.uiState.value
                        val resultUri = state.convertedUri
                        if (resultUri != null) {
                            pendingEditorLaunch = EditorLaunchSource.Converted(
                                resultUri = resultUri,
                                originalUri = state.selectedFileUri,
                                displayName = state.outputFileName.ifBlank { state.fileName.orEmpty() }
                            )
                            navController.navigate(EditorRouteBase)
                        }
                    }
                )
            }
            composable(Screen.History.route) {
                val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)
                HistoryScreen(
                    viewModel = historyViewModel,
                    onStartConversion = {
                        navController.navigate(Screen.Converter.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onEditHistory = { historyId ->
                        pendingEditorLaunch = null
                        navController.navigate("editor?historyId=$historyId")
                    },
                    bottomOverlayClearance = BottomOverlayClearance
                )
            }
            composable(EditorRoute) { entry ->
                val historyId = entry.arguments?.getString("historyId")?.toLongOrNull()
                val launchSource = remember(historyId, pendingEditorLaunch) {
                    historyId?.let(EditorLaunchSource::History) ?: pendingEditorLaunch
                }
                if (launchSource == null) {
                    LaunchedEffect(Unit) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Screen.Converter.route) {
                                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text("Returning to converter…")
                    }
                } else {
                    val editorFactory = remember(application, launchSource) {
                        AppViewModelFactory(
                            application = application,
                            editorLaunchSource = launchSource
                        )
                    }
                    val editorViewModel: EditorViewModel = viewModel(factory = editorFactory)
                    val editorState by editorViewModel.uiState.collectAsStateWithLifecycle()
                    val audioPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        uri?.let(editorViewModel::importTrack)
                    }
                    EditorEntryScreen(
                        state = editorState,
                        onIntent = editorViewModel::dispatch,
                        onPickTrack = {
                            audioPicker.launch(arrayOf("audio/*"))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
        if (shouldShowFloatingBottomNavigation(currentDestination?.route)) {
            FloatingBottomNavigation(
                screens = screens,
                currentDestination = currentDestination,
                onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

internal fun shouldShowFloatingBottomNavigation(route: String?): Boolean =
    route?.startsWith("editor") != true

@Composable
private fun EditorEntryScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onIntent: (com.aistudio.voicenote.cvtr.editor.ui.EditorIntent) -> Unit = {},
    onPickTrack: () -> Unit = {},
) {
    com.aistudio.voicenote.cvtr.editor.ui.EditorScreen(
        state = state,
        onIntent = onIntent,
        onPickTrack = onPickTrack,
        onBack = onBack,
    )
}

@Composable
private fun FloatingBottomNavigation(
    screens: List<Screen>,
    currentDestination: androidx.navigation.NavDestination?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.testTag("floating_bottom_navigation"),
            shape = RoundedCornerShape(30.dp),
            color = CardSurfaceWhite,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    IconButton(
                        onClick = { onNavigate(screen) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (selected) PastelMintCardBg else androidx.compose.ui.graphics.Color.Transparent)
                            .semantics(mergeDescendants = true) { this.selected = selected }
                    ) {
                        when (screen) {
                            Screen.Converter -> Icon(
                                if (selected) Icons.Filled.Mic else Icons.Outlined.Mic,
                                contentDescription = screen.label,
                                modifier = Modifier.size(26.dp),
                                tint = if (selected) DeepNavyDisplay else LightSlateCaption
                            )
                            Screen.History -> Icon(
                                if (selected) Icons.Filled.History else Icons.Outlined.History,
                                contentDescription = screen.label,
                                modifier = Modifier.size(26.dp),
                                tint = if (selected) DeepNavyDisplay else LightSlateCaption
                            )
                        }
                    }
                }
            }
        }
    }
}
