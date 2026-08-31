package com.imagedge.camera.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.core.permission.AppPermissions
import com.imagedge.camera.core.permission.PermissionGate
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.PageHeader

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 权限使用说明二级页——诚实列出应用声明的全部权限、用途与当前授权状态。
 *              未授予的项可点击跳转系统设置开启；从设置返回后状态自动刷新。
 *     version: 1.0
 * </pre>
 */
@Composable
fun PermissionScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从系统设置返回时刷新授权状态（RESUMED 时重新采样一次）
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshTick++
        }
    }

    val runtimePermissions = remember(refreshTick) {
        AppPermissions.dangerous
            .filter { it.appliesTo(Build.VERSION.SDK_INT) }
            .map { it to PermissionGate.isGranted(context, it.permission) }
    }
    val normalPermissions = remember(refreshTick) {
        AppPermissions.normal.filter { it.appliesTo(Build.VERSION.SDK_INT) }
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.permission_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionSectionTitle(stringResource(R.string.permission_section_runtime))
            runtimePermissions.forEach { (entry, granted) ->
                val openSettings: () -> Unit = { openAppSettings(context) }
                PermissionRow(
                    label = stringResource(entry.labelRes),
                    purpose = stringResource(entry.purposeRes),
                    granted = granted,
                    onClick = if (granted) null else openSettings
                )
            }

            HorizontalDivider()

            PermissionSectionTitle(stringResource(R.string.permission_section_normal))
            normalPermissions.forEach { entry ->
                PermissionRow(
                    label = stringResource(entry.labelRes),
                    purpose = stringResource(entry.purposeRes),
                    granted = true,
                    onClick = null
                )
            }
        }
    }
}

/** 跳转到本应用的系统设置页（用户在那里开启/关闭权限） */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
private fun PermissionSectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 单条权限：名称 + 用途 + 授权状态（未授予可点击去设置） */
@Composable
private fun PermissionRow(
    label: String,
    purpose: String,
    granted: Boolean,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(36.dp)
                .background(
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            LucideIcon(
                if (granted) Lucide.ShieldCheck else Lucide.TriangleAlert,
                contentDescription = null,
                size = 18.dp,
                tint = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = stringResource(
                        if (granted) R.string.permission_status_granted
                        else R.string.permission_status_denied
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                text = purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted && onClick != null) {
                Text(
                    text = stringResource(R.string.permission_open_settings),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
