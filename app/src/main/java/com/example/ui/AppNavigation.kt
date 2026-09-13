package com.example.ui

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.theme.AppCanvasBackground
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.DeepNavyDisplay
import com.example.ui.theme.LightSlateCaption
import com.example.ui.theme.PastelMintCardBg

private sealed class Screen(val route: String, val label: String) {
    data object Converter : Screen("converter", "Converter")
    data object History : Screen("history", "Riwayat")
}

private val BottomOverlayClearance = 128.dp

@Composable
fun AppNavigation(
    incomingUri: Uri? = null,
    onIncomingUriHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
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
                val converterViewModel: MainViewModel = viewModel()
                LaunchedEffect(incomingUri) {
                    incomingUri?.let {
                        converterViewModel.handleIncomingUri(it)
                        onIncomingUriHandled()
                    }
                }
                MainScreen(
                    viewModel = converterViewModel,
                    bottomOverlayClearance = BottomOverlayClearance
                )
            }
            composable(Screen.History.route) {
                val historyViewModel: HistoryViewModel = viewModel()
                HistoryScreen(
                    viewModel = historyViewModel,
                    onStartConversion = {
                        navController.navigate(Screen.Converter.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    bottomOverlayClearance = BottomOverlayClearance
                )
            }
        }
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
