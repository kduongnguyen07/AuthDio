package com.example.mdownload.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object HiFiShapes {
    val dockCapsule = RoundedCornerShape(32.dp)
    val miniPlayer = RoundedCornerShape(26.dp)
    val sheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val card = RoundedCornerShape(20.dp)
    val input = RoundedCornerShape(16.dp)
    val artworkRow = RoundedCornerShape(14.dp)
    val artworkMini = RoundedCornerShape(12.dp)
    val artworkGrid = RoundedCornerShape(18.dp)
    val badge = RoundedCornerShape(8.dp)
    val pill = CircleShape

    val materialShapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp)
    )
}
