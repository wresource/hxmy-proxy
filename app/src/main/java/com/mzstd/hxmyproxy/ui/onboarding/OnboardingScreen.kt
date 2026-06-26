package com.mzstd.hxmyproxy.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import kotlinx.coroutines.launch

/**
 * 首次引导（5 屏，可左右滑 + 跳过）：欢迎 / 给谁用 / 三步用起来 / 为什么选 / 权限说明。
 *
 * D3 硬门：本地网络权限**仅在最后一屏用户主动点「允许」后**才请求系统弹窗——先讲清再请求，
 * 绝不一进 App 就弹。走完或跳过都回调 [onFinish]（置首启完成标志，进主界面）。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    // 权限结果无论授予与否都进主界面：被拒后主界面「开始共享」仍会再次请求（兜底）。
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onFinish() }

    fun requestPermsThenFinish() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (perms.isEmpty()) onFinish() else permLauncher.launch(perms.toTypedArray())
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onFinish) { Text(stringResource(R.string.ob_skip)) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> WhoForPage()
                    2 -> ThreeStepsPage()
                    3 -> WhyChoosePage()
                    else -> PermissionPage(onAllow = ::requestPermsThenFinish, onLater = onFinish)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dots(pager.currentPage)
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < PAGES - 1) {
                Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Text(stringResource(R.string.ob_next))
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val on = i == current
            Box(
                Modifier
                    .size(if (on) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun Title(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun InfoCard(titleRes: Int, bodyRes: Int) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WelcomePage() {
    Title(R.string.ob1_title)
    Body(R.string.ob1_body)
}

@Composable
private fun WhoForPage() {
    Title(R.string.ob2_title)
    InfoCard(R.string.ob2_c1_title, R.string.ob2_c1_body)
    InfoCard(R.string.ob2_c2_title, R.string.ob2_c2_body)
    InfoCard(R.string.ob2_c3_title, R.string.ob2_c3_body)
}

@Composable
private fun ThreeStepsPage() {
    Title(R.string.ob3_title)
    StepRow("1", R.string.ob3_s1_title, R.string.ob3_s1_body)
    StepRow("2", R.string.ob3_s2_title, R.string.ob3_s2_body)
    StepRow("3", R.string.ob3_s3_title, R.string.ob3_s3_body)
    Text(
        stringResource(R.string.ob3_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepRow(n: String, titleRes: Int, bodyRes: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(n, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WhyChoosePage() {
    Title(R.string.why_title)
    InfoCard(R.string.why1_title, R.string.why1_body)
    InfoCard(R.string.why2_title, R.string.why2_body)
    InfoCard(R.string.why3_title, R.string.why3_body)
    InfoCard(R.string.why4_title, R.string.why4_body)
    InfoCard(R.string.why5_title, R.string.why5_body)
}

@Composable
private fun PermissionPage(onAllow: () -> Unit, onLater: () -> Unit) {
    Title(R.string.ob5_title)
    Body(R.string.ob5_body)
    Text(
        stringResource(R.string.ob5_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ob5_allow)) }
    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ob5_later)) }
}

private const val PAGES = 5
