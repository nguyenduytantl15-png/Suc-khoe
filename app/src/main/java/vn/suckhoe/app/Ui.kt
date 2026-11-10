package vn.suckhoe.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object C {
    val PRIMARY = 0xFFE53950.toInt()
    val BG = 0xFFF4F6FA.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val TEXT = 0xFF1F2333.toInt()
    val SUB = 0xFF6B7280.toInt()
    val LINE = 0xFFE5E7EB.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val ORANGE = 0xFFEA8A00.toInt()
    val RED = 0xFFDC2626.toInt()
    val BLUE = 0xFF2563EB.toInt()
    val PURPLE = 0xFF7C3AED.toInt()
    val TEAL = 0xFF0D9488.toInt()
}

/** Hệ số phóng to chữ toàn app (Cài đặt → Cỡ chữ). */
object Scale {
    var f = 1.2f
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeW: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radius
    if (strokeW > 0) g.setStroke(strokeW, strokeColor)
    return g
}

fun Context.text(s: String, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false, scaled: Boolean = true): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = if (scaled) size * Scale.f else size
    t.setTextColor(color)
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    return t
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun Context.card(): LinearLayout {
    val l = vbox()
    l.background = rounded(C.CARD, dpf(16f))
    l.elevation = dpf(2f)
    l.setPadding(dp(16), dp(16), dp(16), dp(16))
    return l
}

fun Context.button(label: String, color: Int = C.PRIMARY, onClick: (View) -> Unit): Button {
    val b = Button(this)
    b.text = label
    b.isAllCaps = false
    b.textSize = 16f * Scale.f
    b.setTextColor(0xFFFFFFFF.toInt())
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    b.background = rounded(color, dpf(12f))
    b.stateListAnimator = null
    b.minHeight = dp(52)
    b.setOnClickListener(onClick)
    return b
}

fun Context.input(hint: String, decimal: Boolean = false, plainText: Boolean = false): EditText {
    val e = EditText(this)
    e.hint = hint
    e.textSize = 17f * Scale.f
    e.setTextColor(C.TEXT)
    e.setHintTextColor(0xFF9CA3AF.toInt())
    e.inputType = when {
        plainText -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else -> InputType.TYPE_CLASS_NUMBER
    }
    e.background = rounded(0xFFF9FAFB.toInt(), dpf(10f), C.LINE, dp(1))
    e.setPadding(dp(12), dp(10), dp(12), dp(10))
    e.isSingleLine = true
    return e
}

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.m(l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
    setMargins(l, t, r, b)
    return this
}

/** Thẻ nhỏ hiển thị trạng thái màu. */
fun Context.badge(s: Status): TextView {
    val t = text(s.label, 12f, s.color, true)
    t.background = rounded((s.color and 0x00FFFFFF) or 0x22000000, dpf(8f))
    t.setPadding(dp(8), dp(3), dp(8), dp(3))
    return t
}
