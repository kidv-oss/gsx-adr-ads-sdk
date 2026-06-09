package com.compose.gsxgoogleadscompose.open

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compose.gsxgoogleadscompose.common.PremiumStatusText
import com.gsx.googleadcompose.GlobalVariables
import com.gsx.googleadcompose.GoogleAds.AdmOpen

/** Màn test AdmOpenAd: Load + Show thủ công. (Resume tự động đã chạy ở Application.) */
@Composable
fun OpenScreen() {
    var status by remember { mutableStateOf("Chưa load") }

    val open = AdmOpen {
        onAvailable = { status = "Ad sẵn sàng ✅" }
        onExhausted = { status = "Hết ad (exhausted)" }
        onShowed = { status = "Đang show" }
        onClicked = { status = "User bấm ad" }
        onDismissed = { status = "Ad đóng" }
        onError = { type -> status = "Lỗi: $type" }
    }
    GlobalVariables.canShowOpenAd = true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120726))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("App Open", color = Color(0xFF52DE11), fontSize = 32.sp, fontWeight = FontWeight.W700)
        PremiumStatusText()
        Text(
            text = status,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
        )

        // load + show (giống splash). open.load()/show() là private — chỉ resumeAd/showFromSplash công khai.
        Button(
            onClick = { status = "Đang load + show..."; open.showFromSplash { status = "Đóng/lỗi/timeout" } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Load + Show") }
    }
}
