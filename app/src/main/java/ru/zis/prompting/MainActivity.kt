package ru.zis.prompting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(AppScreen.Chat) }

                when (screen) {
                    AppScreen.Chat -> ChatScreen(
                        onOpenProfiles = { screen = AppScreen.Profiles },
                        onOpenTaskAgent = { screen = AppScreen.TaskAgent }
                    )

                    AppScreen.Profiles -> ProfileScreen(
                        onBack = { screen = AppScreen.Chat }
                    )

                    AppScreen.TaskAgent -> TaskAgentScreen(
                        onBack = { screen = AppScreen.Chat }
                    )
                }
            }
        }
    }
}

private enum class AppScreen {
    Chat,
    Profiles,
    TaskAgent
}
