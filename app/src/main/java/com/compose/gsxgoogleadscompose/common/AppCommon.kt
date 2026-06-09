package com.compose.gsxgoogleadscompose.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsx.googleadcompose.GoogleIAP.rememberPremiumState

/** Dòng chữ "đã Premium hay chưa" — dùng chung mọi view. Tự cập nhật theo trạng thái mua. */
@Composable
fun PremiumStatusText(modifier: Modifier = Modifier) {
    val premium by rememberPremiumState()
    Text(
        text = if (premium) "Premium: Đã mua ✅" else "Premium: Chưa ✗",
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        color = if (premium) Color(0xFF52DE11) else Color(0xFFBDBDBD),
        fontSize = 16.sp,
        fontWeight = FontWeight.W600,
        textAlign = TextAlign.Center,
    )
}
