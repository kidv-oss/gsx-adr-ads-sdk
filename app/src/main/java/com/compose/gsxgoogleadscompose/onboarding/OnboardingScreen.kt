package com.compose.gsxgoogleadscompose.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNativeSequence
import com.gsx.googleadcompose.GoogleAds.nativead.AdmNativeSequenceState
import com.gsx.googleadcompose.GoogleAds.nativead.NativeLayout
import kotlinx.coroutines.launch

/** 1 trang = nội dung onboard, hoặc native full (để test preload tuần tự). */
private sealed interface OnbPage {
    data class Intro(val title: String, val desc: String) : OnbPage
    data object NativeAd : OnbPage
}

private val PAGES = listOf(
    OnbPage.Intro("Chào mừng", "Trang onboarding số 1"),
    OnbPage.NativeAd,                                           // native full #0
    OnbPage.Intro("Tính năng", "Trang onboarding số 2"),
    OnbPage.Intro("Bắt đầu", "Trang onboarding số 3"),
    OnbPage.Intro("Bắt đầu", "Trang onboarding số 3"),
)

/**
 * Onboarding 3 trang + 2 trang native full xen giữa (HorizontalPager). Test [AdmNativeSequence]:
 * init list index 1 lần -> mỗi trang native consume id kế + preload tiếp.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit = {}) {
    // Init sequence: 2 native page -> cần >= 2 index. Dùng index 0,1 của listNativeAdUnitID.

    // Rời hẳn onboarding -> dọn buffer sequence (destroy ad chưa consume).
    DisposableEffect(Unit) { onDispose { AdmNativeSequenceState.release() } }

    val pager = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.lastIndex
    val nextIsNative = PAGES.getOrNull(pager.currentPage + 1) is OnbPage.NativeAd
    // Trang kế là native -> chỉ cho "Tiếp" khi lib báo ad kế sẵn/timeout (isNextReady).
    val canNext = !nextIsNative || AdmNativeSequenceState.isNextReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120726))
            .safeDrawingPadding(),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            val active = page == pager.currentPage          // chỉ trang đang xem mới consume ad
            when (val p = PAGES[page]) {
                is OnbPage.Intro -> IntroPage(p, active)
                OnbPage.NativeAd -> AdmNativeSequence(
                    modifier = Modifier.fillMaxSize(),
                    layout = NativeLayout.FULL,
                    active = active,
                ) {
                    ctaColor = Color(0xFFFF6D00)
                    ctaTextColor = Color.White
                }
            }
        }

        // ----- Dots + nút -----
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier
                            .size(if (on) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (on) Color(0xFF52DE11) else Color(0xFF4A3F66)),
                    )
                }
            }
            Box(Modifier.weight(1f))
            if (last) {
                Button(onClick = onDone) { Text("Xong") }
            } else {
                TextButton(onClick = onDone) { Text("Bỏ qua", color = Color.White) }
                Button(
                    enabled = canNext,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                ) { Text(if (canNext) "Tiếp" else "Đang tải…") }
            }
        }
    }
}

@Composable
private fun IntroPage(p: OnbPage.Intro, active: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(p.title, color = Color(0xFF52DE11), fontSize = 30.sp, fontWeight = FontWeight.W700)
        Text(
            p.desc,
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        AdmNativeSequence(layout = NativeLayout.COLLAPSIBLE, modifier = Modifier.fillMaxWidth(), active = active) {  }
    }
}
