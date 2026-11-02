package space.linuxct.nothing.icongen

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.Hct
import java.io.File
import kotlin.math.min

private const val LUCENT_SPRING_STIFFNESS = 400f

/** Shared View tokens, drawing and interaction for the app's Lucent surfaces. */
internal class LucentUi(val context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val dynamic = DynamicColors.wrapContextIfAvailable(context)
    val accent = MaterialColors.getColor(dynamic, com.google.android.material.R.attr.colorPrimary, Color.GRAY)
    val onAccent = MaterialColors.getColor(dynamic, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK)
    private val seed = Hct.fromInt(accent)
    private val hue = (seed.hue + 337.0) % 360.0
    private val monochrome = seed.chroma < 6.0
    private fun tone(chroma: Double, tone: Double) = Hct.from(hue, if (monochrome) 0.0 else chroma, tone).toInt()
    private val extraDark = dark && Settings.Secure.getInt(context.contentResolver, "theme_extra_darkmode", 0) != 0
    val page = tone(if (dark) 16.5 else 8.0, if (extraDark) 0.0 else if (dark) 8.9 else 94.0)
    val surface = tone(if (dark) 31.0 else 5.0, if (dark) 20.6 else 99.0)
    val bright = tone(if (dark) 31.0 else 5.0, if (dark) 30.0 else 100.0)
    val ink = tone(if (dark) 17.0 else 10.0, if (dark) 86.0 else 12.0)
    val muted = tone(if (dark) 19.0 else 12.0, if (dark) 74.0 else 40.0)
    val pressed = tone(if (dark) 31.0 else 5.0, if (dark) 35.0 else if (monochrome) 86.0 else 92.0)
    val body: Typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.geist_variable))
    val headline: Typeface = headlineFont
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun alpha(color: Int, value: Float) = ColorUtils.setAlphaComponent(color, (255 * value).toInt())
    fun shape(color: Int, radius: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1), alpha(ink, .1f))
    }
    fun touch(color: Int, radius: Int = 20, pressedColor: Int = pressed): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor, radius))
        addState(intArrayOf(android.R.attr.state_focused), shape(pressedColor, radius))
        addState(intArrayOf(), shape(color, radius))
        setEnterFadeDuration(80)
        setExitFadeDuration(140)
    }

    private fun groupTouch(first: Boolean, last: Boolean): Drawable {
        fun fill(color: Int) = shape(color, 8).apply {
            val top = dp(if (first) 28 else 8).toFloat()
            val bottom = dp(if (last) 28 else 8).toFloat()
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), fill(pressed))
            addState(intArrayOf(android.R.attr.state_focused), fill(pressed))
            addState(intArrayOf(), fill(alpha(surface, .82f)))
            setEnterFadeDuration(80)
            setExitFadeDuration(140)
        }
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun text(value: CharSequence, size: Float = 14f, muted: Boolean = false, heading: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        typeface = if (heading) headline else body
        setTextColor(if (muted) this@LucentUi.muted else ink)
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun card(padding: Int = 16) = column(padding).apply { background = shape(alpha(surface, .82f), 28) }
    fun add(parent: LinearLayout, child: View, top: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create(body, 500, false)
        isAllCaps = false
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        stateListAnimator = null
        backgroundTintList = null
        background = touch(if (primary) accent else alpha(surface, .86f), 36, if (primary) ColorUtils.blendARGB(accent, onAccent, .12f) else pressed)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(alpha(if (primary) onAccent else ink, .45f), if (primary) onAccent else ink)))
        setOnClickListener { action() }
    }
    fun row(title: String, detail: String? = null, icon: String = "chevron", action: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = touch(Color.TRANSPARENT)
            isFocusable = true
            setOnClickListener { action() }
        }
        val labels = column()
        add(labels, text(title, 15f))
        if (!detail.isNullOrBlank()) add(labels, text(detail, 12f, muted = true), 4)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(context).apply {
            setImageDrawable(LucentIcon(icon, ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        return row
    }
    fun toggle(parent: LinearLayout, title: String, detail: String, checked: Boolean, first: Boolean = false, last: Boolean = false, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(9), dp(8), dp(9)); minimumHeight = dp(68) }
        val labels = column()
        add(labels, text(title, 15f))
        add(labels, text(detail, 12f, muted = true), 3)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = LucentToggle(this, false).apply { contentDescription = title; isChecked = checked; setOnCheckedChangeListener { _, value -> onChange(value) } }
        row.addView(toggle, LinearLayout.LayoutParams(dp(56), dp(48)))
        row.background = groupTouch(first, last)
        row.setOnClickListener { toggle.toggle() }
        add(parent, row, if (first) 0 else 2)
    }
    fun radio(parent: LinearLayout, title: String, checked: Boolean, action: () -> Unit): LucentToggle {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); background = touch(Color.TRANSPARENT) }
        val radio = LucentToggle(this, true).apply { contentDescription = title; isChecked = checked; setOnClickListener { action() } }
        row.addView(radio, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(text(title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { action() }
        add(parent, row)
        return radio
    }
    fun divider(parent: LinearLayout) = parent.addView(View(context).apply { setBackgroundColor(alpha(ink, .08f)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
    fun screen(activity: Activity): LinearLayout {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = page
        activity.window.navigationBarColor = page
        activity.window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val content = column()
        val scroll = ScrollView(context).apply {
            setBackgroundColor(page)
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(20), bars.top + dp(16), bars.right + dp(20), bars.bottom + dp(16))
            insets
        }
        activity.setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        return content
    }
    fun header(parent: LinearLayout, title: String, subtitle: String? = null, back: (() -> Unit)? = null, menu: (() -> Unit)? = null) {
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (back != null) line.addView(iconButton("back", "Back", back), LinearLayout.LayoutParams(dp(48), dp(48)))
        line.addView(text(title, 36f, heading = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (menu != null) line.addView(iconButton("more", "More options", menu), LinearLayout.LayoutParams(dp(48), dp(48)))
        add(parent, line)
        if (subtitle != null) add(parent, text(subtitle, 13f, muted = true), 6)
    }
    private fun iconButton(icon: String, description: String, action: () -> Unit) = ImageView(context).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setImageDrawable(LucentIcon(icon, ink))
        contentDescription = description
        background = touch(Color.TRANSPARENT, 28)
        isFocusable = true
        setOnClickListener { action() }
    }
    fun dialog(activity: AppCompatActivity, title: String, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = LucentDialog(activity)
        if (activity.isFinishing || activity.isDestroyed) return dialog
        val content = column(24).apply { background = shape(surface, 32, border = true) }
        add(content, text(title, 26f, heading = true))
        gap(content, 16)
        build(content, dialog)
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .84f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; background = shape(surface, 32, border = true); clipToOutline = true; addView(content) }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(min(dp(560), context.resources.displayMetrics.widthPixels - dp(48)), -2)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.also { it.dimAmount = .4f; it.windowAnimations = 0 }
            if (Build.VERSION.SDK_INT >= 31 && context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.also { it.blurBehindRadius = dp(28) }
            }
        }
        content.scaleX = .85f; content.scaleY = .85f; content.alpha = 0f
        content.animate().alpha(1f).setDuration(180).start()
        spring(content, SpringAnimation.SCALE_X, 1f)
        spring(content, SpringAnimation.SCALE_Y, 1f)
        return dialog
    }
    companion object {
        private val headlineFont: Typeface by lazy {
            sequenceOf("/system/fonts", "/product/fonts", "/system_ext/fonts")
                .flatMap { dir -> sequenceOf("otf", "ttf").map { File(dir, "NType82-Regular.$it") } }
                .firstOrNull { it.isFile }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                ?: Typeface.SERIF
        }
        fun spring(view: View, property: androidx.dynamicanimation.animation.FloatPropertyCompat<View>, target: Float) = SpringAnimation(view, property, target).apply {
            spring.dampingRatio = .55f
            spring.stiffness = LUCENT_SPRING_STIFFNESS
            start()
        }
    }
}

private class LucentDialog(activity: AppCompatActivity) : Dialog(activity) {
    private val ownerLifecycle = activity.lifecycle
    private val destroyObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismissImmediately()
    }
    private var exiting = false

    init { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }

    override fun show() {
        if (ownerLifecycle.currentState == Lifecycle.State.DESTROYED) return
        exiting = false
        ownerLifecycle.addObserver(destroyObserver)
        super.show()
    }

    override fun dismiss() {
        val content = window?.decorView?.findViewById<View>(android.R.id.content)
        if (content == null || !isShowing) { dismissImmediately(); return }
        if (exiting) return
        exiting = true
        content.animate().alpha(0f).scaleX(.85f).scaleY(.85f).setDuration(160)
            .withEndAction { dismissImmediately() }.start()
    }

    private fun dismissImmediately() {
        ownerLifecycle.removeObserver(destroyObserver)
        window?.decorView?.let(::cancelAnimations)
        exiting = false
        super.dismiss()
    }

    private fun cancelAnimations(view: View) {
        view.animate().withEndAction(null).cancel()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) cancelAnimations(view.getChildAt(index))
        }
    }
}

/** Fixed geometry; the whole 48dp control remains the accessible touch target. */
internal class LucentToggle(private val ui: LucentUi, private val radio: Boolean) : CompoundButton(ui.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var position = 0f
    private val property = object : FloatPropertyCompat<LucentToggle>("position") {
        override fun getValue(view: LucentToggle) = view.position
        override fun setValue(view: LucentToggle, value: Float) { view.position = value; view.invalidate() }
    }
    private val motion = SpringAnimation(this, property).apply {
        spring = SpringForce(0f).apply { dampingRatio = .55f; stiffness = LUCENT_SPRING_STIFFNESS }
    }
    init { buttonDrawable = null; background = ui.touch(Color.TRANSPARENT, 28); isFocusable = true }
    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        if (!isAttachedToWindow) { position = if (checked) 1f else 0f; invalidate() }
        else motion.animateToFinalPosition(if (checked) 1f else 0f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val x = width / 2f; val y = height / 2f
        val fill = position.coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        if (radio) {
            if (fill < 1f) {
                paint.color = ui.alpha(ui.ink, .25f * (1 - fill)); paint.style = Paint.Style.STROKE; paint.strokeWidth = density
                canvas.drawCircle(x, y, 9.5f * density, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = ui.alpha(ui.ink, fill); canvas.drawCircle(x, y, 10f * density, paint)
            paint.color = ui.surface; canvas.drawCircle(x, y, 3f * density * fill, paint)
        } else {
            paint.color = ui.alpha(ui.ink, .45f + .55f * fill)
            rect.set(x - 20 * density, y - 14 * density, x + 20 * density, y + 14 * density)
            canvas.drawRoundRect(rect, 14 * density, 14 * density, paint)
            paint.color = ui.surface
            canvas.drawCircle(x - 6 * density + 12 * density * position, y, 12 * density, paint)
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = if (radio) "android.widget.RadioButton" else "android.widget.Switch"
        info.isCheckable = true; info.isChecked = isChecked
    }
    override fun onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow() }
}

internal class LucentIcon(private val kind: String, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color; path.reset()
        when (kind) {
            "more" -> {
                canvas.drawCircle(12f, 5f, .7f, paint)
                canvas.drawCircle(12f, 12f, .7f, paint)
                canvas.drawCircle(12f, 19f, .7f, paint)
            }
            "back" -> { path.moveTo(14f, 5f); path.lineTo(7f, 12f); path.lineTo(14f, 19f) }
            else -> { path.moveTo(9f, 6f); path.lineTo(15f, 12f); path.lineTo(9f, 18f) }
        }
        canvas.drawPath(path, paint); canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
