package ani.dantotsu.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.AutoTransition
import androidx.transition.Transition
import androidx.transition.TransitionManager
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AppUpdateNotice
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.px
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.Logger
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import nl.joery.animatedbottombar.AnimatedBottomBar
import com.google.android.material.R as MaterialR

/**
 * Debug-only playground for the design modernisation audit: every section puts what the app does
 * today next to the proposed replacement (tier 1: Views-only wins, tier 2: structural changes), so
 * the feel can be judged on a real device before anything is migrated. Each section is built on
 * its own, and one that fails to inflate (an Expressive style missing a theme attribute, say) shows
 * its error in place instead of taking the whole screen down.
 */
class DesignLabActivity : AppCompatActivity() {

    companion object {
        /**
         * Local debug builds only. Not BuildConfig.DEBUG: the `debug` build type sets
         * `debuggable false`, which turns that flag off for exactly the builds this is for.
         */
        val enabled get() = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "debug"
    }

    private lateinit var root: CoordinatorLayout
    private lateinit var column: LinearLayout
    private var insetsText: TextView? = null
    private var liveInsets = "not received yet"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!enabled) {
            finish()
            return
        }
        ThemeManager(this).applyTheme()
        initActivity(this)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16f.px, 16f.px, 16f.px, 16f.px)
        }
        val scroll = NestedScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root = CoordinatorLayout(this).apply {
            setBackgroundColor(getThemeColor(android.R.attr.colorBackground))
            addView(scroll, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        // Own insets rather than the statusBarHeight/navBarHeight globals — comparing the two is
        // one of the demos below.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            column.setPadding(16f.px + bars.left, 16f.px + bars.top, 16f.px + bars.right, 16f.px + bars.bottom)
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            liveInsets = "status $status px · nav $nav px · cutout $cutout px"
            updateInsetsText()
            insets
        }

        addHeader()

        // TEMP: a way into the app update screen without a real update being out.
        section("App update screen", "For a made-up version. Downloading it will fail.") {
            // By name: the screen is only in the google flavour.
            fun updateIntent() = Intent().setClassName(this@DesignLabActivity, "ani.dantotsu.others.AppUpdateActivity")
                .putExtra("version", "99.0.0")
                .putExtra("repo", getString(R.string.repo))
                .putExtra("releasedAt", System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
                .putExtra(
                    "changelog",
                    "- **Changes:**\n  - Added a thing\n  - Changed another thing\n" +
                        "- **Fixes:**\n  - Fixed a bug\n  - Fixed a much longer bug whose description wraps onto a second line"
                )
            buttonRow(
                button("Notify") {
                    AppUpdater.notifyUpdate(
                        this@DesignLabActivity,
                        AppUpdater.Update(
                            "99.0.0",
                            updateIntent().getStringExtra("changelog").orEmpty(),
                            getString(R.string.repo),
                            System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L,
                        )
                    )
                },
                button("Show notice") {
                    AppUpdateNotice.offer("99.0.0", again = true) { it.startActivity(updateIntent()) }
                    AppNotices.showPending(this@DesignLabActivity)
                },
                // Two notices at once, to see them stack: the update offer behind a reload prompt.
                button("Stack") {
                    AppUpdateNotice.offer("99.0.0", again = true) { it.startActivity(updateIntent()) }
                    ani.dantotsu.connections.sync.SyncReloadNotice.raise()
                    AppNotices.showPending(this@DesignLabActivity)
                },
                button("Open", filled = true) { startActivity(updateIntent()) },
            )
        }

        tierLabel("Tier 1 — Views-only wins")
        section(
            "Loading indicators",
            "The app has 91 platform ProgressBars. Material 3 indicators, then the 1.14 Expressive ones."
        ) { progressDemo() }
        section(
            "Show, hide and resize",
            "Today views pop in and out. TransitionManager animates the same change with one line."
        ) { showHideDemo() }
        section(
            "Tab switching",
            "The media page swaps tabs with no motion. Pick a transition, then switch tabs."
        ) { tabsDemo() }
        section(
            "Springs vs overshoot",
            "Press and hold each tile. Left: ObjectAnimator with @anim/over_shoot, as today. Right: SpringAnimation."
        ) { springDemo() }
        section(
            "Cards",
            "183 androidx CardViews set corners and colours per layout. M3 cards take them from the theme."
        ) { addView(layoutInflater.inflate(R.layout.design_lab_cards, this, false)) }
        section(
            "Buttons (Expressive)",
            "Material 1.14 adds shape-morphing buttons and button groups."
        ) { addView(layoutInflater.inflate(R.layout.design_lab_buttons, this, false)) }

        tierLabel("Tier 2 — Structural")
        section(
            "Bottom bar",
            "The main screen's AnimatedBottomBar against Material 3 navigation and the floating toolbar."
        ) { barsDemo() }
        section(
            "System-bar insets",
            "190 places read the global heights. Rotate, resize the WSA window or open this screen first thing to see them drift from the live values."
        ) { insetsDemo() }
        section(
            "Snackbar vs toast",
            "On WSA a toast becomes a Windows notification; a snackbar stays inside this window."
        ) { feedbackDemo() }
    }

    override fun onResume() {
        super.onResume()
        updateInsetsText()
    }

    // ---- page scaffolding --------------------------------------------------------------------

    private fun addHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16f.px)
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_round_arrow_back_ios_new_24)
            setColorFilter(getThemeColor(MaterialR.attr.colorOnBackground))
            contentDescription = "Back"
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(32f.px, 32f.px))
        row.addView(text("Design Lab", 20f, MaterialR.attr.colorOnBackground).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(12f.px, 0, 0, 0)
        })
        column.addView(row)
    }

    private fun tierLabel(label: String) {
        column.addView(text(label, 13f, androidx.appcompat.R.attr.colorPrimary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4f.px, 8f.px, 0, 8f.px)
        })
    }

    private fun section(title: String, desc: String, build: LinearLayout.() -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16f.px, 16f.px, 16f.px, 16f.px)
        }
        body.addView(text(title, 16f, MaterialR.attr.colorOnSurface).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(text(desc, 12f, MaterialR.attr.colorOnSurfaceVariant).apply {
            setPadding(0, 2f.px, 0, 12f.px)
        })
        try {
            body.build()
        } catch (e: Throwable) {
            Logger.log(e)
            body.addView(text("Couldn't build this demo: ${e.message}", 12f, androidx.appcompat.R.attr.colorError))
        }
        val card = MaterialCardView(this, null, MaterialR.attr.materialCardViewFilledStyle).apply {
            addView(body)
        }
        column.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 16f.px
        })
    }

    private fun text(s: CharSequence, sizeSp: Float = 14f, colorAttr: Int = MaterialR.attr.colorOnSurface) =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(getThemeColor(colorAttr))
        }

    private fun button(label: String, filled: Boolean = false, onClick: (View) -> Unit) =
        MaterialButton(
            this, null,
            if (filled) MaterialR.attr.materialButtonStyle else MaterialR.attr.materialButtonOutlinedStyle
        ).apply {
            text = label
            setOnClickListener(onClick)
        }

    private fun LinearLayout.buttonRow(vararg buttons: MaterialButton) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = 8f.px
            })
        }
        addView(row)
    }

    // ---- tier 1 ------------------------------------------------------------------------------

    private fun LinearLayout.progressDemo() {
        val v = layoutInflater.inflate(R.layout.design_lab_progress, this, false)
        addView(v)
        val linear = v.findViewById<LinearProgressIndicator>(R.id.designLabLinear)
        val wavy = v.findViewById<LinearProgressIndicator>(R.id.designLabLinearWavy)
        // The floating badges: the real download FAB and rate-limit layouts against wavy copies.
        val downloads = listOf(
            v.findViewById<View>(R.id.designLabDownloadFab)
                .findViewById<CircularProgressIndicator>(R.id.downloadFabProgress),
            v.findViewById(R.id.designLabDownloadWavy),
            v.findViewById(R.id.designLabDownloadWavySmall),
        )
        val rateLimits = listOf(
            v.findViewById<View>(R.id.designLabRateLimit)
                .findViewById<CircularProgressIndicator>(R.id.anilistRateLimitProgress),
            v.findViewById(R.id.designLabRateLimitWavy),
            v.findViewById(R.id.designLabRateLimitWavySmall),
        )
        val countdowns = listOf(
            v.findViewById<View>(R.id.designLabRateLimit).findViewById<TextView>(R.id.anilistRateLimitCountdown),
            v.findViewById<TextView>(R.id.designLabRateLimitWavyCountdown),
            v.findViewById<TextView>(R.id.designLabRateLimitWavySmallCountdown),
        )
        // Same hide → flip → show dance DownloadFab does when switching modes.
        fun CircularProgressIndicator.setMode(indeterminate: Boolean) {
            if (isIndeterminate == indeterminate) return
            hide()
            isIndeterminate = indeterminate
            show()
        }
        fun setBadges(progress: Int, animate: Boolean) {
            downloads.forEach {
                it.setMode(indeterminate = false)
                it.setProgressCompat(progress, animate)
            }
            // The rate-limit ring drains as the wait passes, out of a 60 s wait here.
            rateLimits.forEach { it.setProgressCompat(100 - progress, animate) }
            val secs = (100 - progress) * 60 / 100
            countdowns.forEach { it.text = String.format(java.util.Locale.US, "0:%02d", secs) }
        }
        setBadges(40, animate = false)

        v.findViewById<View>(R.id.designLabProgressAnimate).setOnClickListener {
            val next = if (linear.progress >= 90) 10 else linear.progress + 25
            linear.setProgressCompat(next, true)
            wavy.setProgressCompat(next, true)
            setBadges(next, animate = true)
        }
        buttonRow(button("Downloads: sizes not known yet") {
            downloads.forEach { it.setMode(indeterminate = true) }
        })
    }

    private fun LinearLayout.showHideDemo() {
        val description = text(
            "Description — a long synopsis that starts collapsed to three lines, the way the media " +
                "page shows it. Expanding it reveals the rest of the text and the genre chips " +
                "below, which today appear in a single frame and push everything under them down " +
                "at once. With a delayed transition the card grows, the chips fade in and the " +
                "sections below slide to their new place."
        ).apply { maxLines = 3 }
        val chips = ChipGroup(context).apply {
            listOf("Action", "Drama", "Fantasy", "Isekai").forEach { addView(Chip(context).apply { text = it }) }
            isVisible = false
        }
        addView(description)
        addView(chips)

        fun toggle() {
            val expand = !chips.isVisible
            description.maxLines = if (expand) 100 else 3
            chips.isVisible = expand
        }
        buttonRow(
            button("Instant (current)") { toggle() },
            button("Animated", filled = true) {
                TransitionManager.beginDelayedTransition(column, AutoTransition().apply {
                    duration = 300
                    interpolator = FastOutSlowInInterpolator()
                })
                toggle()
            },
        )

        // Loading → content, the step every media page and info tab goes through.
        val frame = FrameLayout(context).apply { minimumHeight = 96f.px }
        val spinner = ProgressBar(context).apply { isVisible = false }
        val content = text("Loaded content: title, score, synopsis…", 14f).apply {
            setPadding(0, 32f.px, 0, 32f.px)
        }
        frame.addView(spinner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        addView(text("Loading → content", 12f, MaterialR.attr.colorOnSurfaceVariant).apply {
            setPadding(0, 16f.px, 0, 0)
        })
        addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        fun simulateLoad(transition: Transition?) {
            content.isVisible = false
            spinner.isVisible = true
            frame.postDelayed({
                transition?.let { TransitionManager.beginDelayedTransition(frame, it) }
                spinner.isVisible = false
                content.isVisible = true
            }, 1200)
        }
        buttonRow(
            button("Instant (current)") { simulateLoad(null) },
            button("Fade through", filled = true) { simulateLoad(MaterialFadeThrough()) },
        )
    }

    private fun LinearLayout.tabsDemo() {
        val mode = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val modes = listOf("None (current)", "Fade through", "Shared axis")
        val modeIds = modes.map { label ->
            MaterialButton(context, null, MaterialR.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                textSize = 12f
                mode.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }.id
        }
        mode.check(modeIds[0])
        addView(mode, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tabs = TabLayout(context)
        val names = listOf("Info", "Watch", "Comments")
        names.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        addView(tabs)

        val frame = FrameLayout(context)
        addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 140f.px))
        val pageColors = listOf(
            MaterialR.attr.colorPrimaryContainer,
            MaterialR.attr.colorSecondaryContainer,
            MaterialR.attr.colorTertiaryContainer,
        )
        val pageText = listOf(
            MaterialR.attr.colorOnPrimaryContainer,
            MaterialR.attr.colorOnSecondaryContainer,
            MaterialR.attr.colorOnTertiaryContainer,
        )
        fun page(i: Int) = text("${names[i]} tab content", 16f, pageText[i]).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(getThemeColor(pageColors[i]))
        }
        frame.addView(page(0), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        var current = 0
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val next = tab.position
                val transition: Transition? = when (mode.checkedButtonId) {
                    modeIds[1] -> MaterialFadeThrough()
                    modeIds[2] -> MaterialSharedAxis(MaterialSharedAxis.X, next > current)
                    else -> null
                }
                transition?.let { TransitionManager.beginDelayedTransition(frame, it) }
                frame.removeAllViews()
                frame.addView(page(next), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                current = next
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun LinearLayout.springDemo() {
        val overshoot = AnimationUtils.loadInterpolator(context, R.anim.over_shoot)
        fun tile(label: String) = text(label, 13f, MaterialR.attr.colorOnPrimaryContainer).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(getThemeColor(MaterialR.attr.colorPrimaryContainer))
        }
        val current = tile("ObjectAnimator\n(current)")
        val spring = tile("Spring")
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(current, LinearLayout.LayoutParams(0, 96f.px, 1f).apply { marginEnd = 8f.px })
        row.addView(spring, LinearLayout.LayoutParams(0, 96f.px, 1f).apply { marginStart = 8f.px })
        addView(row)

        fun animateCurrent(to: Float) {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(current, View.SCALE_X, to),
                    ObjectAnimator.ofFloat(current, View.SCALE_Y, to),
                )
                duration = 300
                interpolator = overshoot
                start()
            }
        }
        val springX = SpringAnimation(spring, DynamicAnimation.SCALE_X, 1f)
        val springY = SpringAnimation(spring, DynamicAnimation.SCALE_Y, 1f)
        listOf(springX, springY).forEach {
            it.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            it.spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        }
        fun animateSpring(to: Float) {
            springX.animateToFinalPosition(to)
            springY.animateToFinalPosition(to)
        }

        current.setOnTouchListener { _, e -> pressScale(e, ::animateCurrent) }
        spring.setOnTouchListener { _, e -> pressScale(e, ::animateSpring) }

        buttonRow(button("Pop both", filled = true) {
            current.scaleX = 0.6f; current.scaleY = 0.6f
            spring.scaleX = 0.6f; spring.scaleY = 0.6f
            animateCurrent(1f)
            animateSpring(1f)
        })
    }

    private fun pressScale(e: MotionEvent, animate: (Float) -> Unit): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate(0.88f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate(1f)
        }
        return true
    }

    // ---- tier 2 ------------------------------------------------------------------------------

    private fun LinearLayout.barsDemo() {
        val v = layoutInflater.inflate(R.layout.design_lab_bars, this, false)
        addView(v)
        // Same tabs, icons and ids as MediaDetailsActivity's manga page.
        val tabs = listOf(
            Triple(R.drawable.ic_round_info_24, R.string.info, R.id.info),
            Triple(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read),
            Triple(R.drawable.ic_round_comment_24, R.string.comments, R.id.comment),
        )
        val mediaBar = v.findViewById<AnimatedBottomBar>(R.id.designLabMediaBar)
        tabs.forEach { (icon, title, id) -> mediaBar.addTab(mediaBar.createTab(icon, title, id)) }
        mediaBar.selectTabAt(1, false)

        // BottomNavigationView pads itself by the navigation-bar inset, which is right at the
        // bottom of a screen but leaves these mid-page samples a nav bar taller on a phone than
        // on WSA (which has none). Swallowing the insets keeps the samples comparable.
        listOf(R.id.designLabBottomNav, R.id.designLabMediaNav, R.id.designLabMediaNavSelected).forEach {
            ViewCompat.setOnApplyWindowInsetsListener(v.findViewById(it)) { _, insets -> insets }
        }
        listOf(R.id.designLabMediaNav, R.id.designLabMediaNavSelected).forEach { navId ->
            val mediaNav = v.findViewById<BottomNavigationView>(navId)
            tabs.forEachIndexed { i, (icon, title, id) ->
                mediaNav.menu.add(0, id, i, title).setIcon(icon)
            }
            mediaNav.selectedItemId = R.id.read
        }
    }

    private fun LinearLayout.insetsDemo() {
        val t = text("", 13f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        insetsText = t
        addView(t)
        updateInsetsText()
        buttonRow(button("Refresh") { updateInsetsText() })
    }

    private fun updateInsetsText() {
        insetsText?.text = "Globals: status $statusBarHeight px · nav $navBarHeight px\n" +
            "Live:    $liveInsets"
    }

    private fun LinearLayout.feedbackDemo() {
        buttonRow(
            button("Toast") {
                Toast.makeText(this@DesignLabActivity, "This is a toast", Toast.LENGTH_SHORT).show()
            },
            button("snackString") { snackString("This is snackString (current)", this@DesignLabActivity) },
        )
        buttonRow(
            button("M3 snackbar", filled = true) {
                Snackbar.make(root, "Removed from Planning", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {}
                    .show()
            },
            button("Toast + snackbar", filled = true) {
                Toast.makeText(this@DesignLabActivity, "Toast", Toast.LENGTH_SHORT).show()
                Snackbar.make(root, "Snackbar", Snackbar.LENGTH_SHORT).show()
            },
        )
    }
}
