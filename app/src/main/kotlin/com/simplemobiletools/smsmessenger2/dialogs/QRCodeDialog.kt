import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.simplemobiletools.smsmessenger2.R
import java.util.*

class QRDialog(context: Context, private val text: String) : Dialog(context) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.dialog_qr)

            // Generate QR code bitmap
            val size = Point()
            window!!.windowManager.defaultDisplay.getSize(size)
            val qrCode: Bitmap? = generateQRCode(text, size)

            // Set QR code bitmap in ImageView
            findViewById<ImageView>(R.id.qr_code_image).setImageBitmap(qrCode)
        }

        private fun generateQRCode(text: String, size: Point): Bitmap? {
            try {
                // Set QR code encoding parameters
                val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
                hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size.x, size.y, hints)

                // Create bitmap from BitMatrix
                val width: Int = matrix.width
                val height: Int = matrix.height
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    val offset = y * width
                    for (x in 0 until width) {
                        pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                    }
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                return bitmap
            } catch (e: WriterException) {
                Log.e("generateQRCode", "Error generating QR code", e)
            }
            return null
        }
    }
