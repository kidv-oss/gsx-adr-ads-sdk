package com.gsx.googleadcompose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsx.googleadcompose.utils.PreferencesManager

/**
 * Khung màn Premium: phần [content] cuộn được ở trên, thanh dưới CỐ ĐỊNH gồm nút CTA bo tròn +
 * hàng Terms / Restore / Privacy. App chỉ truyền nội dung phía trên + các callback.
 *
 * ```
 * PremiumBase(
 *     continueText = stringResource(R.string.coutinuetxt),
 *     onContinue = { BillingClient.purchase(activity, selectedId) },
 *     onRestore = { BillingClient.restorePurchases() },
 *     onTerms = { openTerms() },
 *     onPrivacy = { openPrivacy() },
 * ) {
 *     // nội dung cuộn: logo, feature, các gói...
 *     Image(...)
 *     Text("Mở khóa Premium")
 *     PlanCards(...)
 * }
 * ```
 */
@Composable
fun PremiumBase(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    continueText: String = "Continue",
    restoreText: String = "Restore",
    termsText: String = "Terms of Use",
    privacyText: String = "Privacy Policy",
    onRestore: () -> Unit = {},
    onTerms: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    ctaColor: Color = Color(0xFF52DE11),
    ctaTextColor: Color = Color.White,
    termsColor: Color = Color.White,
    restoreColor: Color = Color.White,
    privacyColor: Color = Color.White,
    continueModifier: Modifier = Modifier,
    showLifetimeSwitch: Boolean = false,
    onLifetimeToggle: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Full screen edge-to-edge; chừa status/nav bar cho content + thanh dưới.
    Column(modifier.fillMaxSize()) {

        // Nội dung cuộn được (chiếm phần còn lại) — chừa status bar trên cùng.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState()),
            content = content,
        )

        // Thanh dưới cố định: CTA + Terms/Restore/Privacy — chừa nav bar dưới.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // Float slide (Switch): gạt ON = mua Lifetime. Ẩn mặc định.
            if (showLifetimeSwitch) {
                var lifetimeOn by remember { mutableStateOf(PreferencesManager.getInstance().isLifetime()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = lifetimeOn,
                        onCheckedChange = {
                            lifetimeOn = it
                            onLifetimeToggle(it)
                            if(lifetimeOn){
                                PreferencesManager.getInstance().purchaseLifetime()
                            }else{
                                PreferencesManager.getInstance().removeLifetime()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = ctaColor),
                        modifier = Modifier.fillMaxWidth().padding(end = 10.dp)
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContinue() }
                    .then(continueModifier),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = ctaColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = continueText,
                        color = ctaTextColor,
                        fontWeight = FontWeight.W700,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = termsText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTerms() },
                    color = termsColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                Text(
                    text = restoreText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onRestore() },
                    color = restoreColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = privacyText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPrivacy() },
                    color = privacyColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
