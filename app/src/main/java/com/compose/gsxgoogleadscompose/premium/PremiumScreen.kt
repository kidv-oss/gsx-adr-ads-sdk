package com.compose.gsxgoogleadscompose.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsx.googleadcompose.GoogleIAP.BillingAction
import com.gsx.googleadcompose.GoogleIAP.BillingClient
import com.gsx.googleadcompose.GoogleIAP.BillingEvents
import com.gsx.googleadcompose.GoogleIAP.rememberPremiumState
import com.gsx.googleadcompose.ui.PremiumBase
import com.gsx.googleadcompose.utils.PreferencesManager

private data class PlanUi(
    val id: String,
    val title: String,
    val price: String,
    val hasTrial: Boolean,
    val trial: String?,
    val time: String,
    val discountPercent: Int = 0,    // % rẻ hơn so với mua lẻ theo tháng
    val originalPrice: String? = null, // giá gốc gạch ngang (Monthly×12)
)

private fun buildPlans(): List<PlanUi> = MyProducts.all.map { id ->
    // Ưu tiên offer có discount (intro rẻ hơn base, cùng kỳ); không thì offer đầu.
    val offers = BillingClient.getOfferPricing(id)
    val offer = offers.firstOrNull { it.hasDiscount } ?: offers.firstOrNull()

    PlanUi(
        id = id,
        title = MyProducts.title(id),
        // Có discount -> hiện giá sale; không -> giá thường.
        price = offer?.takeIf { it.hasDiscount }?.salePrice
            ?: BillingClient.getPrice(id) ?: "",
        hasTrial = BillingClient.hasFreeTrial(id),
        trial = BillingClient.getFreeTrialPeriod(id),
        time = MyProducts.Time(id),
        discountPercent = offer?.discountPercent ?: 0,
        originalPrice = offer?.takeIf { it.hasDiscount }?.basePrice,
    )
}
@Composable
fun PremiumScreen(
    onClose: () -> Unit = {},
    onTerms: () -> Unit = {},
    onPrivacy: () -> Unit = {},
) {
    com.gsx.googleadcompose.ui.HideSystemBars()
    val context = androidx.compose.ui.platform.LocalContext.current
    val billing = BillingAction()
    val premium  by rememberPremiumState()

    var plans by remember { mutableStateOf(buildPlans()) }
    var selectedId by remember { mutableStateOf(MyProducts.all.first()) }

    BillingEvents(
        onProductsFetched = { plans = buildPlans() },
        onPurchased = { onClose() },
        onRestoreFinished = { has ->
            val msg = if (has) "Purchase restored" else "No purchases to restore"
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // Background (thay @drawable/bg_premium bằng gradient tím).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF6A2C91), Color(0xFF2A0E45), Color(0xFF120726))
                    )
                )
        )

        PremiumBase(
            onContinue = { billing.purchase(selectedId) },
            onRestore = { billing.restore() },
            onTerms = onTerms,
            onPrivacy = onPrivacy,
            continueText = if (premium) "Premium active" else "Continue",
            restoreText = "Restore",
            termsText = "Terms of Use",
            privacyText = "Privacy Policy",
            showLifetimeSwitch = true
        ) {
            Spacer(Modifier.height(80.dp))

            Text(
                text = if (premium) "Premium - ${MyProducts.title(PreferencesManager.getInstance().ownedProduct())}" else "Premium",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF52DE11),
                fontSize = 32.sp,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Unlock all features",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 25.dp, vertical = 24.dp),
            ) {
                FeatureRow("Ad-free experience")
                FeatureRow("Stable & private connection")
                FeatureRow("HD streaming quality")
                FeatureRow("Access to future updates")
            }

            // Các gói.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                plans.forEach { plan ->
                    PlanCard(plan, plan.id == selectedId) { selectedId = plan.id }
                }
            }

            Text(
                text = "Payment will be charged to your Google account. Subscriptions renew automatically unless canceled.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                color = Color(0xFF797979),
                fontSize = 14.sp,
            )
        }

        // Nút đóng (góc trên trái).
        Text(
            text = "✕",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 16.dp)
                .clickable { onClose() },
            color = Color.White,
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", color = Color(0xFF52DE11), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.W500)
    }
}

@Composable
private fun PlanCard(plan: PlanUi, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .then(
                if (selected) Modifier.border(2.dp, Color(0xFF52DE11), RoundedCornerShape(12.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plan.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (plan.discountPercent > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "-${plan.discountPercent}%",
                            color = Color(0xFF062100),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(Color(0xFF52DE11), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (plan.hasTrial) {
                    Text(
                        "Free trial ${plan.trial ?: ""}",
                        color = Color(0xFF52DE11),
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                if (plan.originalPrice != null) {
                    Text(
                        plan.originalPrice,
                        color = Color(0xFFB9AED0),
                        fontSize = 13.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    )
                }
                Row {
                    Text(plan.price, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (plan.time != "") {
                        Text(plan.time, color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
