package com.mariocannon.pacetrainer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0E7C66)
private val TealLight = Color(0xFF5FD3B5)

@Composable
fun PaceTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = TealLight)
    } else {
        lightColorScheme(primary = Teal)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
