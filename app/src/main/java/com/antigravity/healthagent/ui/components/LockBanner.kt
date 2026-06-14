package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class LockBannerStyle {
    COMPACT,
    FULL,
}

@Composable
fun LockBanner(
    enabled: Boolean,
    isTeamworkProtection: Boolean = false,
    isHomologated: Boolean = false,
    style: LockBannerStyle = LockBannerStyle.COMPACT,
    modifier: Modifier = Modifier,
) {
    val icon = when {
        isTeamworkProtection -> Icons.Default.People
        else -> Icons.Default.Lock
    }
    val backgroundColor = when {
        isTeamworkProtection -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
        isHomologated -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    }
    val contentColor = when {
        isTeamworkProtection -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        isHomologated -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    }
    val bannerText = when {
        isTeamworkProtection -> "CASA DO COLEGA - SOMENTE LEITURA"
        isHomologated -> "VISITA HOMOLOGADA - SOMENTE LEITURA"
        else -> "DIA BLOQUEADO - SOMENTE LEITURA"
    }

    when (style) {
        LockBannerStyle.COMPACT -> {
            Surface(
                color = backgroundColor,
                modifier = modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        bannerText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = contentColor,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
            HorizontalDivider(
                color = when {
                    isTeamworkProtection -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                    isHomologated -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    else -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                }
            )
        }

        LockBannerStyle.FULL -> {
            Surface(
                color = MaterialTheme.colorScheme.error,
                modifier = modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        bannerText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}
