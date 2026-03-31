package ru.zis.prompting

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        maybeRequestNotificationPermission()

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(AppScreen.Chat) }

                when (screen) {
                    AppScreen.Chat -> ChatScreen(
                        onOpenProfiles = { screen = AppScreen.Profiles },
                        onOpenMcp = { screen = AppScreen.Mcp },
                        onOpenWeatherPipeline = { screen = AppScreen.WeatherPipeline },
                        onOpenWeatherHistory = { screen = AppScreen.WeatherHistory }
                    )

                    AppScreen.Profiles -> ProfileScreen(
                        onBack = { screen = AppScreen.Chat }
                    )

                    AppScreen.Mcp -> McpScreen(
                        onBack = { screen = AppScreen.Chat }
                    )

                    AppScreen.WeatherHistory -> WeatherHistoryScreen(
                        onBack = { screen = AppScreen.Chat }
                    )

                    AppScreen.WeatherPipeline -> WeatherPipelineScreen(
                        onBack = { screen = AppScreen.Chat }
                    )
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class AppScreen {
    Chat,
    Profiles,
    Mcp,
    WeatherHistory,
    WeatherPipeline
}
