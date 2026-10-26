package ani.dantotsu.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.client
import ani.dantotsu.databinding.ActivitySettingsAboutBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsAboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAboutBinding
    private lateinit var settings: ArrayList<Settings>

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        binding = ActivitySettingsAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)

        binding.apply {
            settingsAboutLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            SettingsSearchButton.attach(this@SettingsAboutActivity, binding.aboutSettingsBack)
            aboutSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.faq),
                        desc = getString(R.string.faq_desc),
                        icon = R.drawable.ic_round_quiz_24,
                        onClick = {
                            SettingsPageTransitions.open(
                                this@SettingsAboutActivity, it, FAQActivity::class.java
                            )
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.check_app_updates),
                        startsGroup = true,
                        desc = getString(R.string.check_app_updates_desc),
                        icon = R.drawable.ic_round_new_releases_24,
                        isChecked = PrefManager.getVal(PrefName.CheckUpdate),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.CheckUpdate, isChecked)
                            AppUpdater.scheduleBackgroundCheck(this@SettingsAboutActivity)
                            setBackgroundCheckEnabled(isChecked)
                        },
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.check_app_updates_background),
                        desc = getString(R.string.check_app_updates_background_desc),
                        icon = R.drawable.ic_round_notifications_active_24,
                        anchorKey = BACKGROUND_CHECK_KEY,
                        isChecked = PrefManager.getVal(PrefName.CheckUpdateBackground),
                        // Only a refinement of the switch above: nothing to check without it.
                        isEnabled = PrefManager.getVal(PrefName.CheckUpdate),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.CheckUpdateBackground, isChecked)
                            AppUpdater.scheduleBackgroundCheck(this@SettingsAboutActivity)
                        },
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.share_username_in_crash_reports),
                        desc = getString(R.string.share_username_in_crash_reports_desc),
                        icon = R.drawable.ic_round_badge_24,
                        isChecked = PrefManager.getVal(PrefName.SharedUserID),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SharedUserID, isChecked)
                        },
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.disable_crash_reports),
                        desc = getString(R.string.disable_crash_reports_desc),
                        icon = R.drawable.ic_round_bug_report_24,
                        isChecked = PrefManager.getVal(PrefName.DisableCrashReports),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.DisableCrashReports, isChecked)
                            restartApp()
                        },
                        isVisible = !BuildConfig.FLAVOR.contains("fdroid")
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.log_to_file),
                        desc = getString(R.string.logging_warning),
                        icon = R.drawable.ic_round_description_24,
                        isChecked = PrefManager.getVal(PrefName.LogToFile),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.LogToFile, isChecked)
                            Logger.clearLog()
                            restartApp()
                        },
                        attachToSwitch = {
                            it.settingsExtraIcon.visibility = View.VISIBLE
                            it.settingsExtraIcon.setImageResource(R.drawable.ic_round_share_24)
                            it.settingsExtraIcon.setOnClickListener {
                                Logger.shareLog(context)
                            }

                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.devs),
                        startsGroup = true,
                        desc = getString(R.string.devs_desc),
                        icon = R.drawable.ic_round_group_24,
                        onClick = {
                            DevelopersDialogFragment().show(supportFragmentManager, "dialog")
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.disclaimer),
                        desc = getString(R.string.disclaimer_desc),
                        icon = R.drawable.ic_round_gavel_24,
                        onClick = {
                            val text = TextView(context)
                            text.setText(R.string.full_disclaimer)

                            CustomBottomDialog.newInstance().apply {
                                setTitleText(context.getString(R.string.disclaimer))
                                addView(text)
                                setNegativeButton(context.getString(R.string.close)) {
                                    dismiss()
                                }
                                show(supportFragmentManager, "dialog")
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.privacy_policy),
                        desc = getString(R.string.privacy_policy_desc),
                        icon = R.drawable.ic_shield,
                        onClick = {
                            val text = TextView(context)
                            val pPLink =
                                "https://raw.githubusercontent.com/rebelonion/Dantotsu/main/privacy_policy.md"
                            val backup =
                                "https://gcore.jsdelivr.net/gh/rebelonion/dantotsu/privacy_policy.md"
                            text.text = getString(R.string.loading)
                            val markWon = try {
                                buildMarkwon(this@SettingsAboutActivity, false)
                            } catch (e: IllegalArgumentException) {
                                return@Settings
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                val res = try {
                                    val out = client.get(pPLink)
                                    if (out.code != 200) {
                                        client.get(backup)
                                    } else {
                                        out
                                    }.text
                                } catch (e: Exception) {
                                    getString(R.string.failed_to_load)
                                }
                                runOnUiThread {
                                    markWon.setMarkdown(text, res)
                                }
                            }

                            CustomBottomDialog.newInstance().apply {
                                setTitleText(context.getString(R.string.privacy_policy))
                                addView(text)
                                setNegativeButton(context.getString(R.string.close)) {
                                    dismiss()
                                }
                                show(supportFragmentManager, "dialog")
                            }
                        }
                    ),
                ).also { settings = it },
                tiled = true,
            )
            binding.settingsRecyclerView.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        }
    }

    /** Greys the background-check row out, or back in, as "Check for Updates" is switched. */
    private fun setBackgroundCheckEnabled(enabled: Boolean) {
        val i = settings.indexOfFirst { it.anchorKey == BACKGROUND_CHECK_KEY }
        if (i < 0) return
        settings[i] = settings[i].copy(isEnabled = enabled)
        binding.settingsRecyclerView.adapter?.notifyItemChanged(i)
    }

    private companion object {
        const val BACKGROUND_CHECK_KEY = "check_app_updates_background"
    }
}