package com.moonaligner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.moonaligner.app.ui.ar.ArTrackerScreen
import com.moonaligner.app.ui.calculator.CalculatorScreen
import com.moonaligner.app.ui.planner.PlannerSharedState
import com.moonaligner.app.ui.planner.ShotPlannerScreen
import com.moonaligner.app.ui.theme.*
import com.moonaligner.app.ui.tracker.SunMoonTrackerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoonAlignerTheme {
                val sharedState = remember { PlannerSharedState() }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = NightSkyBackground,
                    bottomBar = {
                        NavigationBar(
                            containerColor = SurfaceDark,
                            contentColor = TextPrimary
                        ) {
                            NavigationBarItem(
                                selected = sharedState.selectedTab == 0,
                                onClick = { sharedState.selectedTab = 0 },
                                icon = { Icon(Icons.Default.DateRange, contentDescription = "달 정렬 플래너") },
                                label = { Text("정렬 플래너") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NightSkyBackground,
                                    selectedTextColor = MoonGlow,
                                    indicatorColor = SunGold,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                            NavigationBarItem(
                                selected = sharedState.selectedTab == 1,
                                onClick = { sharedState.selectedTab = 1 },
                                icon = { Icon(Icons.Default.Info, contentDescription = "초망원 거리 계산기") },
                                label = { Text("화각 계산기") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NightSkyBackground,
                                    selectedTextColor = MoonGlow,
                                    indicatorColor = SunGold,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = NightSkyBackground
                    ) {
                        when (sharedState.selectedTab) {
                            0 -> ShotPlannerScreen(sharedState = sharedState)
                            1 -> CalculatorScreen(sharedState = sharedState)
                        }
                    }
                }
            }
        }
    }
}
