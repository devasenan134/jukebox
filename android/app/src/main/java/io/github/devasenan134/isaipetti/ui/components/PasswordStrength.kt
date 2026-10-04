package io.github.devasenan134.isaipetti.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.PasswordRules

/** A bar under a new-password field: red "too weak" with the reason, amber "okay", green "strong". */
@Composable
fun PasswordStrength(check: PasswordRules.Check, modifier: Modifier = Modifier) {
    val (progress, color, label) = when (check.strength) {
        PasswordRules.Strength.TooWeak -> Triple(0.25f, MaterialTheme.colorScheme.error, "Too weak: ${check.problem}")
        PasswordRules.Strength.Okay -> Triple(0.6f, Color(0xFFE0A33A), "Okay. A longer passphrase would be stronger")
        PasswordRules.Strength.Strong -> Triple(1f, Color(0xFF4CC38A), "Strong")
    }
    Column(modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            color = color,
            modifier = Modifier.fillMaxWidth().height(4.dp),
            drawStopIndicator = {},
        )
        Text(label, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}
