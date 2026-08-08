package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaLoginDialog
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesLoginDialog
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)

        binding.apply {
            settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsAccountHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            settingsMangaUpdatesHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.mangaupdates_account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_mangaupdates_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            settingsMangaBakaHelp.setOnClickListener {
                CustomBottomDialog.newInstance().apply {
                    setTitleText(context.getString(R.string.mangabaka_account_help))
                    addView(
                        TextView(it.context).apply {
                            val markWon = Markwon.builder(it.context)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                            markWon.setMarkdown(this, context.getString(R.string.full_mangabaka_account_help))
                        }
                    )
                }.show(supportFragmentManager, "dialog")
            }

            fun reload() {
                if (Anilist.token != null) {
                    settingsAnilistLogin.setText(R.string.logout)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsAnilistUsername.visibility = View.VISIBLE
                    settingsAnilistUsername.text = Anilist.username
                    settingsAnilistAvatar.loadImage(Anilist.avatar)
                    settingsAnilistAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val anilistLink = getString(
                            R.string.anilist_link,
                            PrefManager.getVal<String>(PrefName.AnilistUserName)
                        )
                        openLinkInBrowser(anilistLink)
                    }

                    settingsMALLoginRequired.visibility = View.GONE
                    settingsMALLogin.visibility = View.VISIBLE
                    settingsMALUsername.visibility = View.VISIBLE
                    settingsMangaUpdatesLoginContainer.visibility = View.VISIBLE
                    settingsMangaBakaLoginContainer.visibility = View.VISIBLE
                    settingsRecyclerView.visibility = View.VISIBLE

                    if (MAL.token != null) {
                        settingsMALLogin.setText(R.string.logout)
                        settingsMALLogin.setOnClickListener {
                            MAL.removeSavedToken()
                            restartMainActivity.isEnabled = true
                            reload()
                        }
                        if (MAL.username == null || MAL.avatar == null) {
                            lifecycleScope.launch {
                                MAL.query.getUserData()
                                reload()
                            }
                        }
                        settingsMALUsername.visibility = View.VISIBLE
                        settingsMALUsername.alpha = 1f // may have been dimmed while signed out
                        settingsMALUsername.text = MAL.username
                        if (!MAL.avatar.isNullOrBlank()) {
                            settingsMALAvatar.loadImage(MAL.avatar)
                        } else {
                            settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                        }
                        settingsMALAvatar.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            openLinkInBrowser(getString(R.string.myanilist_link, MAL.username))
                        }
                    } else {
                        settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                        showKnownAccount(settingsMALUsername, PrefName.MALUserName)
                        settingsMALLogin.setText(R.string.login)
                        settingsMALLogin.setOnClickListener {
                            MAL.loginIntent(context)
                        }
                    }
                } else {
                    settingsAnilistAvatar.setImageResource(R.drawable.ic_round_person_24)
                    settingsAnilistUsername.visibility = View.GONE
                    settingsRecyclerView.visibility = View.GONE
                    settingsAnilistLogin.setText(R.string.login)
                    settingsAnilistLogin.setOnClickListener {
                        Anilist.loginIntent(context)
                    }
                    settingsMALLoginRequired.visibility = View.VISIBLE
                    settingsMALLogin.visibility = View.GONE
                    settingsMALUsername.visibility = View.GONE
                    settingsMangaUpdatesLoginContainer.visibility = View.GONE
                    settingsMangaBakaLoginContainer.visibility = View.GONE
                }

                if (Discord.token != null) {
                    val id = PrefManager.getVal(PrefName.DiscordId, null as String?)
                    val avatar = PrefManager.getVal(PrefName.DiscordAvatar, null as String?)
                    val username = PrefManager.getVal(PrefName.DiscordUserName, null as String?)

                    if (id != null && avatar != null) {
                        settingsDiscordAvatar.loadImage("https://cdn.discordapp.com/avatars/$id/$avatar.png")
                        settingsDiscordAvatar.setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val discordLink = getString(R.string.discord_link, id)
                            openLinkInBrowser(discordLink)
                        }
                    } else {
                        settingsDiscordAvatar.setImageResource(R.drawable.ic_round_person_24)
                    }
                    settingsDiscordUsername.visibility = View.VISIBLE
                    settingsDiscordUsername.alpha = 1f // may have been dimmed while signed out
                    settingsDiscordUsername.text =
                        username ?: Discord.token?.replace(Regex("."), "*")
                    settingsDiscordLogin.setText(R.string.logout)
                    settingsDiscordLogin.setOnClickListener {
                        Discord.removeSavedToken(context)
                        restartMainActivity.isEnabled = true
                        reload()
                    }

                    settingsPresenceSwitcher.visibility = View.VISIBLE
                    var initialStatus = when (PrefManager.getVal<String>(PrefName.DiscordStatus)) {
                        "online" -> R.drawable.discord_status_online
                        "idle" -> R.drawable.discord_status_idle
                        "dnd" -> R.drawable.discord_status_dnd
                        "invisible" -> R.drawable.discord_status_invisible
                        else -> R.drawable.discord_status_online
                    }
                    settingsPresenceSwitcher.setImageResource(initialStatus)

                    val zoomInAnimation =
                        AnimationUtils.loadAnimation(context, R.anim.bounce_zoom)
                    settingsPresenceSwitcher.setOnClickListener {
                        var status = "online"
                        initialStatus = when (initialStatus) {
                            R.drawable.discord_status_online -> {
                                status = "idle"
                                R.drawable.discord_status_idle
                            }

                            R.drawable.discord_status_idle -> {
                                status = "dnd"
                                R.drawable.discord_status_dnd
                            }

                            R.drawable.discord_status_dnd -> {
                                status = "invisible"
                                R.drawable.discord_status_invisible
                            }

                            R.drawable.discord_status_invisible -> {
                                status = "online"
                                R.drawable.discord_status_online
                            }

                            else -> R.drawable.discord_status_online
                        }

                        PrefManager.setVal(PrefName.DiscordStatus, status)
                        settingsPresenceSwitcher.setImageResource(initialStatus)
                        settingsPresenceSwitcher.startAnimation(zoomInAnimation)
                    }
                    settingsPresenceSwitcher.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        DiscordDialogFragment().show(supportFragmentManager, "dialog")
                        true
                    }
                } else {
                    settingsPresenceSwitcher.visibility = View.GONE
                    settingsDiscordAvatar.setImageResource(R.drawable.ic_round_person_24)
                    showKnownAccount(settingsDiscordUsername, PrefName.DiscordUserName)
                    settingsDiscordLogin.setText(R.string.login)
                    settingsDiscordLogin.setOnClickListener {
                        Discord.warning(context)
                            .show(supportFragmentManager, "dialog")
                    }
                }

                // MangaUpdates Login
                if (MangaUpdates.token != null) {
                    settingsMangaUpdatesLogin.setText(R.string.logout)
                    settingsMangaUpdatesLogin.setOnClickListener {
                        MangaUpdates.logout()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsMangaUpdatesUsername.visibility = View.VISIBLE
                    settingsMangaUpdatesUsername.alpha = 1f
                    settingsMangaUpdatesUsername.text = MangaUpdates.username ?: "Logged In"

                    // Load avatar if available
                    if (!MangaUpdates.avatar.isNullOrBlank()) {
                        settingsMangaUpdatesAvatar.loadImage(MangaUpdates.avatar)
                    } else {
                        settingsMangaUpdatesAvatar.setImageResource(R.drawable.ic_round_person_24)
                    }

                    settingsMangaUpdatesAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        // Open MangaUpdates profile page if username is available
                        MangaUpdates.username?.let { username ->
                            openLinkInBrowser("https://www.mangaupdates.com/users/$username")
                        }
                    }
                } else {
                    settingsMangaUpdatesAvatar.setImageResource(R.drawable.ic_round_person_24)
                    showKnownAccount(settingsMangaUpdatesUsername, PrefName.MangaUpdatesUsername)
                    settingsMangaUpdatesLogin.setText(R.string.login)
                    settingsMangaUpdatesLogin.setOnClickListener {
                        val loginDialog = MangaUpdatesLoginDialog()
                        loginDialog.setOnLoginSuccessListener {
                            restartMainActivity.isEnabled = true
                            reload()
                        }
                        loginDialog.show(supportFragmentManager, "mangaupdates_login")
                    }
                }

                // MangaBaka Login
                if (MangaBaka.token != null) {
                    settingsMangaBakaLogin.setText(R.string.logout)
                    settingsMangaBakaLogin.setOnClickListener {
                        MangaBaka.removeSavedToken()
                        restartMainActivity.isEnabled = true
                        reload()
                    }
                    settingsMangaBakaUsername.visibility = View.VISIBLE
                    settingsMangaBakaUsername.alpha = 1f
                    settingsMangaBakaUsername.text = MangaBaka.username ?: getString(R.string.logged_in)
                    // MangaBaka has no avatar system - use an "open" icon instead of the generic
                    // person placeholder, since tapping opens the user's MangaBaka profile page.
                    settingsMangaBakaAvatar.setImageResource(R.drawable.ic_open_24)
                    settingsMangaBakaAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        MangaBaka.username?.let { username ->
                            openLinkInBrowser("${MangaBaka.WEB_URL}/u/$username")
                        }
                    }
                } else {
                    settingsMangaBakaAvatar.setImageResource(R.drawable.ic_round_person_24)
                    showKnownAccount(settingsMangaBakaUsername, PrefName.MangaBakaUserName)
                    settingsMangaBakaLogin.setText(R.string.login)
                    settingsMangaBakaLogin.setOnClickListener {
                        val loginDialog = MangaBakaLoginDialog()
                        loginDialog.setOnLoginSuccessListener {
                            restartMainActivity.isEnabled = true
                            reload()
                        }
                        loginDialog.show(supportFragmentManager, "mangabaka_login")
                    }
                }

                settingsRecyclerView.adapter = SettingsAdapter(
                    arrayListOf(
                        Settings(
                            type = 2,
                            name = getString(R.string.enable_rpc),
                            desc = getString(R.string.enable_rpc_desc),
                            icon = R.drawable.ic_discord,
                            isChecked = PrefManager.getVal(PrefName.rpcEnabled),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.rpcEnabled, isChecked)
                            },
                            isVisible = Discord.token != null,
                            attachToSwitch = {
                                it.settingsExtraIcon.visibility = View.VISIBLE
                                it.settingsExtraIcon.setImageResource(R.drawable.ic_round_settings_24)
                                it.settingsExtraIcon.setOnClickListener {
                                    DiscordDialogFragment().show(supportFragmentManager, "dialog")
                                }
                            }
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.anilist_settings),
                            desc = getString(R.string.alsettings_desc),
                            icon = R.drawable.ic_anilist,
                            onClick = {
                                lifecycleScope.launch {
                                    Anilist.query.getUserData()
                                    startActivity(Intent(context, AnilistSettingsActivity::class.java))
                                }
                            },
                            isActivity = true
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.connections_settings),
                            desc = getString(R.string.connections_desc),
                            icon = R.drawable.network_node_24,
                            onClick = {
                                startActivity(Intent(context, SettingsConnectionsActivity::class.java))
                            },
                            isActivity = true
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.list_sync_settings),
                            desc = getString(R.string.list_sync_settings_desc),
                            icon = R.drawable.ic_round_sync_24,
                            onClick = {
                                startActivity(Intent(context, SettingsListSyncActivity::class.java))
                            },
                            isActivity = true,
                            isVisible = MAL.token != null || MangaBaka.token != null
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.comments_button),
                            desc = getString(R.string.comments_button_desc),
                            icon = R.drawable.ic_round_comment_24,
                            isChecked = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1,
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.CommentsEnabled, if (isChecked) 1 else 2)
                                reload()
                            },
                            isVisible = Anilist.token != null
                        ),
                    )
                )
                if (settingsRecyclerView.layoutManager == null) {
                    settingsRecyclerView.layoutManager =
                        LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                }
            }
            reload()
        }
    }

    fun reload() {
        snackString(getString(R.string.restart_app_extra))
        //snackString(R.string.restart_app_extra)
        //?.setDuration(Snackbar.LENGTH_LONG)
        //?.setAction(R.string.do_it) {
        //startMainActivity(this@SettingsAccountActivity)
        //} Disabled for now. Doesn't update the ADDRESS even after this
    }

    /**
     * Shows the account name this connection is known by, on a device that isn't signed in to it.
     *
     * The name syncs between a user's devices even though the login itself deliberately doesn't, so
     * a second device can say *which* account it means instead of offering an unexplained "Log in".
     * It also survives a local sign-out, where it reads as "this is the account you had".
     *
     * Just the name, dimmed. This line sits in a wrap_content view beside the login button, sized
     * for a username — a sentence explaining what to do never fit and was clipped on ordinary
     * screens. It doesn't need one: the button right next to it already reads "Log in" rather than
     * "Log out", and the dimming is what separates a name we merely know from one that's signed in.
     *
     * Hidden entirely when there is no name — a connection never used.
     */
    private fun showKnownAccount(view: android.widget.TextView, pref: PrefName) {
        val name = PrefManager.getVal<String>(pref)
        if (name.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = name
        view.alpha = 0.5f
    }

}

