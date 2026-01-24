package com.example.familysafety.invite

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QrCodeGenerator @Inject constructor() {

    fun generateQrCode(
        content: String,
        width: Int = 512,
        height: Int = 512
    ): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }
        
        return bitmap
    }
}
