package com.example.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Scaffold(
        modifier = modifier,
        containerColor = AppCanvasBackground,
        bottomBar = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppCanvasBackground)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp)),
                    containerColor = CardSurfaceWhite,
                    tonalElevation = 0.dp
                ) {
                    screens.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                when (screen) {
                                    Screen.Converter -> Icon(if (selected) Icons.Filled.Mic else Icons.Outlined.Mic, screen.label)
                                    Screen.History -> Icon(if (selected) Icons.Filled.History else Icons.Outlined.History, screen.label)
                                }
                            },
                            label = { Text(screen.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = DeepNavyDisplay,
                                selectedTextColor = DeepNavyDisplay,
                                indicatorColor = PastelMintCardBg,
                                unselectedIconColor = LightSlateCaption,
                                unselectedTextColor = LightSlateCaption
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Converter.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Converter.route) {
                val converterViewModel: MainViewModel = viewModel()
                LaunchedEffect(incomingUri) {
                    incomingUri?.let {
                        converterViewModel.handleIncomingUri(it)
                        onIncomingUriHandled()
                    }
                }
                MainScreen(converterViewModel)
            }
            composable(Screen.History.route) {
                val historyViewModel: HistoryViewModel = viewModel()
                HistoryScreen(historyViewModel)
            }
        }
    }
}
