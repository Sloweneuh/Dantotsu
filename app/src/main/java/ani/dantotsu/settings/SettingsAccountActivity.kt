package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.kitsu.KitsuLoginDialog
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesLoginDialog
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.connections.simkl.SimklLoginDialog
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MangaBakaTagWeights
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private lateinit var cardAdapter: AccountCardAdapter
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.accountList, binding.settingsRecyclerView)

        binding.settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // After a login/logout the app data has to be reloaded; a back press then reopens MainActivity.
        onBackPressedDispatcher.addCallback(this, restartMainActivity)

        binding.settingsRecyclerView.layoutManager = LinearLayoutManager(this)

        cardAdapter = AccountCardAdapter(
            onLogin = { startLogin(it) },
            onLogout = { confirmLogout(it) },
            onAvatarTap = { openProfile(it) },
            onInfo = { showInfoSheet(it) },
            rowsFor = { rowsFor(it) },
        )
        binding.accountList.layoutManager = LinearLayoutManager(this)
        binding.accountList.adapter = cardAdapter

        reload()
        handleAccountAnchor()
    }

    /**
     * Settings search can land here pointed at one specific provider card (see
     * [SearchableSetting.anchorProvider]) — and, inside it, one specific row (see
     * [SearchableSetting.anchorRowKey]). Neither is a plain view id [SettingsRouter.handleHighlight]
     * can flash directly: the target card may need expanding first, and its body is a nested
     * [SettingsAdapter] built lazily (see [AccountCardAdapter.bindBody]), so the row has to be
     * looked up only once that's happened.
     */
    private fun handleAccountAnchor() {
        val providerName = intent.getStringExtra(SettingsRouter.EXTRA_ANCHOR_PROVIDER) ?: return
        val provider = runCatching { AccountProvider.valueOf(providerName) }.getOrNull() ?: return
        val rowKey = intent.getStringExtra(SettingsRouter.EXTRA_ANCHOR_ROW_KEY)
        if (rowKey != null) cardAdapter.expand(provider)
        binding.accountList.doOnPreDraw {
            scrollToCardAndFlash(provider, rowKey, attempts = 12)
        }
    }

    private fun scrollToCardAndFlash(provider: AccountProvider, rowKey: String?, attempts: Int) {
        val position = cardAdapter.positionOf(provider)
        if (position < 0) return
        binding.accountList.scrollToPosition(position)
        binding.accountList.postOnAnimation {
            val holder = binding.accountList.findViewHolderForAdapterPosition(position) as? AccountCardAdapter.Holder
            // A card-only hit (no rowKey) highlights the whole card, not just its header — the
            // header is only the full card's bounds while collapsed, and using the card itself
            // means the rounded radius below always matches what's actually on screen. A row hit
            // highlights just that row, which has no rounding of its own.
            val target: View?
            val cornerRadiusPx: Float
            if (rowKey == null) {
                target = holder?.b?.root
                cornerRadiusPx = holder?.b?.root?.radius ?: 0f
            } else {
                val nestedAdapter = holder?.b?.accountBody?.adapter as? SettingsAdapter
                val rowPos = nestedAdapter?.indexOfKey(rowKey) ?: -1
                target = if (rowPos >= 0) holder?.b?.accountBody?.layoutManager?.findViewByPosition(rowPos) else null
                cornerRadiusPx = 0f
            }
            if (target != null) {
                SettingsRouter.scrollToAndFlashSingle(target, cornerRadiusPx)
            } else if (attempts > 0) {
                // The card (or its body, once expanded) may not be laid out yet — try again next frame.
                binding.accountList.postOnAnimation { scrollToCardAndFlash(provider, rowKey, attempts - 1) }
            }
        }
    }

    private var firstResume = true
    private var lastMangaBakaSignedIn = MangaBaka.token != null
    override fun onResume() {
        super.onResume()
        // Refresh when returning from a login flow that runs in its own activity (MangaBaka OAuth).
        if (firstResume) {
            firstResume = false
            return
        }
        val nowSignedIn = MangaBaka.token != null
        if (nowSignedIn != lastMangaBakaSignedIn) {
            lastMangaBakaSignedIn = nowSignedIn
            restartMainActivity.isEnabled = true
        }
        reload()
    }

    // ---- cross-provider tools (below the cards) ----

    /** Cross-provider tools that don't belong to any single card. */
    private fun buildBottomRows() {
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = getString(R.string.list_comparison_title),
                    desc = getString(R.string.list_comparison_desc),
                    icon = R.drawable.ic_round_compare_arrows_24,
                    isActivity = true,
                    onClick = {
                        startActivity(Intent(this, SettingsListSyncActivity::class.java))
                    },
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.customize_info_tabs),
                    desc = getString(R.string.customize_info_tabs_desc),
                    icon = R.drawable.ic_round_view_array_24,
                    onClick = {
                        InfoTabOrderBottomSheet.newInstance()
                            .show(supportFragmentManager, InfoTabOrderBottomSheet.TAG)
                    },
                ),
            )
        )
    }

    // ---- cards ----

    private fun reload() {
        cardAdapter.submit(buildCards())
        buildBottomRows()
        // Refresh the profiles whose name/avatar the app caches, so opening this screen picks up a
        // changed avatar or username rather than showing the stale cached one indefinitely.
        lifecycleScope.launch {
            var changed = false
            if (MAL.token != null) changed = MAL.query.getUserData() || changed
            if (Kitsu.token != null) changed = Kitsu.getUserData() || changed
            if (Simkl.token != null) changed = Simkl.getUserData() || changed
            if (MangaBaka.token != null) changed = MangaBaka.getUserData() || changed
            if (changed) cardAdapter.submit(buildCards())
        }
    }

    private fun buildCards(): List<AccountCard> {
        val anilistIn = Anilist.token != null
        return listOf(
            card(AccountProvider.ANILIST, R.drawable.ic_anilist, R.string.anilist,
                if (anilistIn) AccountState.SignedIn(
                    Anilist.username ?: knownName(PrefName.AnilistUserName), Anilist.avatar
                ) else knownOrOut(PrefName.AnilistUserName)),
            gatedCard(AccountProvider.MANGAUPDATES, R.drawable.ic_round_mangaupdates_24, R.string.mangaupdates, anilistIn,
                if (MangaUpdates.token != null) AccountState.SignedIn(
                    MangaUpdates.username ?: knownName(PrefName.MangaUpdatesUsername), MangaUpdates.avatar
                ) else knownOrOut(PrefName.MangaUpdatesUsername)),
            gatedCard(AccountProvider.MAL, R.drawable.ic_myanimelist, R.string.myanimelist, anilistIn,
                if (MAL.token != null) AccountState.SignedIn(
                    MAL.username ?: knownName(PrefName.MALUserName), MAL.avatar
                ) else knownOrOut(PrefName.MALUserName)),
            gatedCard(AccountProvider.MANGABAKA, R.drawable.ic_round_mangabaka_24, R.string.mangabaka, anilistIn,
                if (MangaBaka.token != null) AccountState.SignedIn(
                    MangaBaka.username ?: knownName(PrefName.MangaBakaUserName), null
                ) else knownOrOut(PrefName.MangaBakaUserName)),
            gatedCard(AccountProvider.KITSU, R.drawable.ic_kitsu, R.string.kitsu, anilistIn,
                if (Kitsu.token != null) AccountState.SignedIn(
                    Kitsu.username ?: knownName(PrefName.KitsuUserName), Kitsu.avatar
                ) else knownOrOut(PrefName.KitsuUserName)),
            gatedCard(AccountProvider.SIMKL, R.drawable.ic_simkl, R.string.simkl, anilistIn,
                if (Simkl.token != null) AccountState.SignedIn(
                    Simkl.username ?: knownName(PrefName.SimklUserName), Simkl.avatar
                ) else knownOrOut(PrefName.SimklUserName)),
            AccountCard(
                AccountProvider.DISCORD, R.drawable.ic_discord, getString(R.string.discord),
                if (Discord.token != null) AccountState.SignedIn(
                    PrefManager.getVal(PrefName.DiscordUserName, null as String?)
                        ?: knownName(PrefName.DiscordUserName),
                    discordAvatarUrl(),
                ) else knownOrOut(PrefName.DiscordUserName),
                discordStatusRes = if (Discord.token != null) discordStatusDrawable() else null,
            ),
            AccountCard(AccountProvider.COMICK, R.drawable.ic_round_comick_24, getString(R.string.comick),
                AccountState.NoLogin),
            AccountCard(AccountProvider.MALSYNC, R.drawable.ic_malsync, getString(R.string.malsync),
                AccountState.NoLogin),
        )
    }

    private fun card(p: AccountProvider, logo: Int, label: Int, state: AccountState) =
        AccountCard(p, logo, getString(label), state)

    /** A tracker card whose Login button is disabled until AniList is connected. */
    private fun gatedCard(p: AccountProvider, logo: Int, label: Int, anilistIn: Boolean, state: AccountState) =
        AccountCard(p, logo, getString(label), state, loginEnabled = anilistIn)

    private fun knownName(pref: PrefName): String = PrefManager.getVal<String>(pref)

    private fun knownOrOut(pref: PrefName): AccountState {
        val name = PrefManager.getVal<String>(pref)
        return if (name.isBlank()) AccountState.SignedOut else AccountState.KnownAccount(name)
    }

    // ---- per-card rows (expanded body) ----

    private fun rowsFor(p: AccountProvider): List<Settings> = when (p) {
        AccountProvider.ANILIST -> anilistRows()
        AccountProvider.MAL -> trackerRows(
            R.string.myanimelist, PrefName.MalEnabled,
            R.string.mal_list_sync, R.string.mal_list_sync_desc,
            PrefName.MalListSyncEnabled, MAL.token != null,
        )
        AccountProvider.KITSU -> trackerRows(
            R.string.kitsu, PrefName.KitsuInfoEnabled,
            R.string.kitsu_list_sync, R.string.kitsu_list_sync_desc,
            PrefName.KitsuListSyncEnabled, Kitsu.token != null,
        )
        AccountProvider.SIMKL -> trackerRows(
            R.string.simkl, PrefName.SimklInfoEnabled,
            R.string.simkl_list_sync, R.string.simkl_list_sync_desc,
            PrefName.SimklListSyncEnabled, Simkl.token != null,
        )
        AccountProvider.MANGAUPDATES -> mangaUpdatesRows()
        AccountProvider.MANGABAKA -> mangaBakaRows()
        AccountProvider.COMICK -> listOf(
            infoRow(R.string.comick, PrefName.ComickEnabled, R.string.disable_comick_desc),
        )
        AccountProvider.MALSYNC -> malSyncRows()
        AccountProvider.DISCORD -> discordRows()
    }

    private fun header(res: Int) = Settings(type = 3, name = getString(res), desc = "", icon = 0)

    /** The "Show <provider> info" master switch shared by every info source — a plain info glyph,
     *  never the provider's own mark (that's already on the card above). */
    private fun infoRow(nameRes: Int, pref: PrefName, descRes: Int): Settings = Settings(
        type = 2,
        name = getString(R.string.account_show_info, getString(nameRes)),
        desc = getString(descRes),
        icon = R.drawable.ic_round_info_24,
        isChecked = PrefManager.getVal(pref),
        switch = { isChecked, _ -> PrefManager.setVal(pref, isChecked) },
        compact = true,
        anchorKey = "info",
    )

    private fun anilistRows(): List<Settings> {
        if (Anilist.token == null) return emptyList()
        return listOf(
            Settings(
                type = 1,
                name = getString(R.string.anilist_settings),
                desc = getString(R.string.alsettings_desc),
                icon = R.drawable.ic_round_settings_24,
                isActivity = true,
                compact = true,
                anchorKey = "anilistSettings",
                onClick = {
                    lifecycleScope.launch {
                        Anilist.query.getUserData()
                        startActivity(Intent(this@SettingsAccountActivity, AnilistSettingsActivity::class.java))
                    }
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.anilist_notifications),
                desc = getString(R.string.anilist_notifications_desc),
                icon = R.drawable.ic_round_notifications_none_24,
                isActivity = true,
                compact = true,
                anchorKey = "notifications",
                onClick = {
                    startActivity(Intent(this, SettingsAnilistNotificationActivity::class.java))
                },
            ),
        )
    }

    private fun trackerRows(
        nameRes: Int, infoPref: PrefName,
        syncNameRes: Int, syncDescRes: Int, syncPref: PrefName, signedIn: Boolean,
    ): List<Settings> = listOf(
        header(R.string.account_group_info),
        infoRow(nameRes, infoPref, R.string.account_show_info_desc),
        header(R.string.account_group_sync),
        Settings(
            type = 2,
            name = getString(syncNameRes),
            desc = getString(syncDescRes),
            icon = R.drawable.ic_round_sync_24,
            isChecked = PrefManager.getVal(syncPref),
            switch = { isChecked, _ -> PrefManager.setVal(syncPref, isChecked) },
            isEnabled = signedIn,
            compact = true,
            anchorKey = "sync",
        ),
    )

    private fun mangaUpdatesRows(): List<Settings> {
        val signedIn = MangaUpdates.token != null
        return listOf(
            header(R.string.account_group_info),
            infoRow(R.string.mangaupdates, PrefName.MangaUpdatesEnabled, R.string.account_show_info_desc),
            header(R.string.account_group_sync),
            Settings(
                type = 2,
                name = getString(R.string.mu_list_fetch_enabled),
                desc = getString(R.string.mu_list_fetch_enabled_desc),
                icon = R.drawable.ic_round_sync_24,
                isChecked = PrefManager.getVal(PrefName.MangaUpdatesListEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaUpdatesListEnabled, isChecked) },
                isEnabled = signedIn,
                compact = true,
                anchorKey = "muListFetch",
            ),
            Settings(
                type = 1,
                name = getString(R.string.mu_custom_list_mapping),
                desc = getString(R.string.mu_custom_list_mapping_desc),
                icon = R.drawable.view_list_24,
                isActivity = true,
                isEnabled = signedIn,
                compact = true,
                anchorKey = "muMapping",
                onClick = {
                    startActivity(Intent(this, MUCustomListMappingActivity::class.java))
                },
            ),
            header(R.string.account_group_notifications),
            Settings(
                type = 1,
                name = getString(R.string.mu_notifications),
                desc = getString(R.string.mu_notifications_desc),
                icon = R.drawable.ic_round_notifications_none_24,
                isActivity = true,
                isEnabled = signedIn,
                compact = true,
                anchorKey = "notifications",
                onClick = {
                    startActivity(Intent(this, SettingsMuNotificationActivity::class.java))
                },
            ),
        )
    }

    private fun mangaBakaRows(): List<Settings> = listOf(
        header(R.string.account_group_info),
        infoRow(R.string.mangabaka, PrefName.MangaBakaInfoEnabled, R.string.account_show_info_desc),
        Settings(
            type = 1,
            name = getString(R.string.mangabaka_tag_weight),
            desc = tagWeightDesc(),
            icon = R.drawable.ic_label_24,
            compact = true,
            anchorKey = "tagWeight",
            attach = { b ->
                b.settingsDesc.text = tagWeightDesc()
                b.attachView.visibility = View.GONE
            },
            onClick = { b ->
                customAlertDialog().apply {
                    setTitle(getString(R.string.mangabaka_tag_weight))
                    singleChoiceItems(
                        MangaBakaTagWeights.choiceAdapter(this@SettingsAccountActivity),
                        MangaBakaTagWeights.defaultIndex(),
                    ) { which ->
                        PrefManager.setVal(PrefName.MangaBakaTagWeightFilter, which)
                        b.settingsDesc.text = tagWeightDesc()
                    }
                    show()
                }
            },
        ),
        header(R.string.account_group_sync),
        Settings(
            type = 2,
            name = getString(R.string.mangabaka_list_sync),
            desc = getString(R.string.mangabaka_list_sync_desc),
            icon = R.drawable.ic_round_sync_24,
            isChecked = PrefManager.getVal(PrefName.MangaBakaListSyncEnabled),
            switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaBakaListSyncEnabled, isChecked) },
            isEnabled = MangaBaka.token != null,
            compact = true,
            anchorKey = "sync",
        ),
    )

    private fun malSyncRows(): List<Settings> = listOf(
        header(R.string.account_group_info),
        Settings(
            type = 2,
            name = getString(R.string.account_use_malsync),
            desc = getString(R.string.disable_malsync_desc),
            icon = R.drawable.ic_round_info_24,
            isChecked = PrefManager.getVal(PrefName.MalSyncInfoEnabled),
            switch = { isChecked, _ -> PrefManager.setVal(PrefName.MalSyncInfoEnabled, isChecked) },
            compact = true,
            anchorKey = "info",
        ),
        header(R.string.account_group_options),
        Settings(
            type = 1,
            name = getString(R.string.malsync_checks_dialog_title),
            desc = malSyncModeDesc(),
            icon = R.drawable.ic_round_settings_24,
            compact = true,
            anchorKey = "malsyncChecks",
            attach = { b ->
                b.settingsDesc.text = malSyncModeDesc()
                b.attachView.visibility = View.GONE
            },
            onClick = { b -> showMalSyncChecksDialog(b.settingsDesc) },
        ),
        Settings(
            type = 1,
            name = getString(R.string.malsync_exclude_manage),
            desc = getString(R.string.malsync_exclude_manage_desc),
            icon = R.drawable.ic_round_playlist_remove_24,
            compact = true,
            anchorKey = "malsyncExclude",
            onClick = {
                MediaExcludeBottomDialog.newInstance(
                    PrefName.MalSyncExcludeList, getString(R.string.malsync_exclude_manage)
                ).show(supportFragmentManager, "malSyncExclude")
            },
        ),
        header(R.string.account_group_notifications),
        Settings(
            type = 1,
            name = getString(R.string.unread_chapter_notifications),
            desc = getString(R.string.unread_chapter_notifications_desc),
            icon = R.drawable.ic_round_notifications_none_24,
            isActivity = true,
            compact = true,
            anchorKey = "notifications",
            onClick = {
                startActivity(Intent(this, SettingsUnreadChapterNotificationActivity::class.java))
            },
        ),
    )

    private fun discordRows(): List<Settings> {
        val signedIn = Discord.token != null
        return listOf(
            header(R.string.account_group_presence),
            Settings(
                type = 2,
                name = getString(R.string.enable_rpc),
                desc = getString(R.string.enable_rpc_desc),
                icon = R.drawable.ic_round_sports_esports_24,
                isChecked = PrefManager.getVal(PrefName.rpcEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.rpcEnabled, isChecked) },
                isEnabled = signedIn,
                compact = true,
                anchorKey = "rpcEnable",
            ),
            Settings(
                type = 1,
                name = getString(R.string.discord_rpc_settings),
                desc = getString(R.string.discord_rpc_settings_desc),
                icon = R.drawable.ic_round_settings_24,
                isEnabled = signedIn,
                compact = true,
                anchorKey = "rpcSettings",
                onClick = { DiscordDialogFragment().show(supportFragmentManager, "dialog") },
            ),
            Settings(
                type = 1,
                name = getString(R.string.discord_status_title),
                desc = discordStatusLabel(),
                icon = discordStatusDrawable(),
                isEnabled = signedIn,
                compact = true,
                anchorKey = "status",
                attach = { b ->
                    b.settingsDesc.text = discordStatusLabel()
                    // The vectors' own `android:tint` isn't reliably applied through AppCompat, so
                    // colour the icon explicitly instead of leaving it the row's usual colorPrimary.
                    b.settingsIcon.imageTintList = null
                    b.settingsIcon.setImageResource(discordStatusDrawable())
                    b.settingsIcon.setColorFilter(discordStatusColor(discordStatusDrawable()))
                    b.attachView.visibility = View.GONE
                },
                onClick = { b -> pickDiscordStatus(b.root) },
            ),
        )
    }

    private fun tagWeightDesc(): String = getString(
        R.string.mangabaka_tag_weight_desc,
        getString(MangaBakaTagWeights.options[MangaBakaTagWeights.defaultIndex()].label)
    )

    private fun malSyncModeDesc(): String {
        val mode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode)
        val modeText = when (mode) {
            "manga" -> getString(R.string.malsync_checks_option_manga)
            "anime" -> getString(R.string.malsync_checks_option_anime)
            else -> getString(R.string.malsync_checks_option_both)
        }
        return getString(R.string.malsync_checks_desc, modeText)
    }

    // ---- actions ----

    private fun startLogin(p: AccountProvider) {
        when (p) {
            AccountProvider.ANILIST -> Anilist.loginIntent(this)
            AccountProvider.MAL -> MAL.loginIntent(this)
            AccountProvider.KITSU -> KitsuLoginDialog().apply {
                setOnLoginSuccessListener { onLoggedIn() }
            }.show(supportFragmentManager, "kitsu_login")
            AccountProvider.SIMKL -> {
                if (!Simkl.isConfigured()) {
                    snackString(getString(R.string.simkl_not_configured))
                    return
                }
                SimklLoginDialog().apply {
                    setOnLoginSuccessListener { onLoggedIn() }
                }.show(supportFragmentManager, "simkl_login")
            }
            AccountProvider.MANGAUPDATES -> MangaUpdatesLoginDialog().apply {
                setOnLoginSuccessListener { onLoggedIn() }
            }.show(supportFragmentManager, "mangaupdates_login")
            AccountProvider.MANGABAKA -> MangaBaka.loginIntent(this)
            AccountProvider.DISCORD -> Discord.warning(this).show(supportFragmentManager, "dialog")
            AccountProvider.COMICK, AccountProvider.MALSYNC -> showInfoSheet(p)
        }
    }

    private fun onLoggedIn() {
        restartMainActivity.isEnabled = true
        reload()
    }

    private fun confirmLogout(p: AccountProvider) {
        customAlertDialog().apply {
            setTitle(getString(R.string.logout_confirm_title, providerLabel(p)))
            setMessage(getString(R.string.logout_confirm_message, providerLabel(p)))
            setPosButton(R.string.logout) { logout(p) }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun logout(p: AccountProvider) {
        when (p) {
            AccountProvider.ANILIST -> Anilist.removeSavedToken()
            AccountProvider.MAL -> MAL.removeSavedToken()
            AccountProvider.KITSU -> Kitsu.removeSavedToken()
            AccountProvider.SIMKL -> Simkl.removeSavedToken()
            AccountProvider.MANGAUPDATES -> MangaUpdates.logout()
            AccountProvider.MANGABAKA -> MangaBaka.removeSavedToken()
            AccountProvider.DISCORD -> Discord.removeSavedToken(this)
            AccountProvider.COMICK, AccountProvider.MALSYNC -> return
        }
        restartMainActivity.isEnabled = true
        reload()
        snackString(getString(R.string.restart_app_extra))
    }

    private fun openProfile(p: AccountProvider) {
        val url = when (p) {
            AccountProvider.ANILIST ->
                getString(R.string.anilist_link, PrefManager.getVal<String>(PrefName.AnilistUserName))
            AccountProvider.MAL -> MAL.username?.let { getString(R.string.myanilist_link, it) }
            AccountProvider.KITSU -> Kitsu.slug?.let { "${Kitsu.WEB_URL}/users/$it" }
            AccountProvider.SIMKL -> Simkl.userid?.let { "${Simkl.WEB_URL}/$it/dashboard" }
            AccountProvider.MANGAUPDATES ->
                MangaUpdates.username?.let { "https://www.mangaupdates.com/users/$it" }
            AccountProvider.MANGABAKA -> MangaBaka.username?.let { "${MangaBaka.WEB_URL}/u/$it" }
            AccountProvider.DISCORD -> PrefManager.getVal(PrefName.DiscordId, null as String?)
                ?.let { getString(R.string.discord_link, it) }
            AccountProvider.COMICK, AccountProvider.MALSYNC -> null
        }
        if (url != null) openLinkInBrowser(url) else showInfoSheet(p)
    }

    // ---- Discord status ----

    private val discordStatuses = listOf("online", "idle", "dnd", "invisible")

    private fun discordStatusLabel(): String = getString(
        when (PrefManager.getVal<String>(PrefName.DiscordStatus)) {
            "idle" -> R.string.discord_status_idle
            "dnd" -> R.string.discord_status_dnd
            "invisible" -> R.string.discord_status_invisible
            else -> R.string.discord_status_online
        }
    )

    private fun discordStatusIcon(key: String): Int = when (key) {
        "idle" -> R.drawable.discord_status_idle
        "dnd" -> R.drawable.discord_status_dnd
        "invisible" -> R.drawable.discord_status_invisible
        else -> R.drawable.discord_status_online
    }

    private fun discordStatusDrawable(): Int =
        discordStatusIcon(PrefManager.getVal(PrefName.DiscordStatus))

    /** Matches each status drawable's own baked-in colour (see the discord_status_* vectors). */
    private fun discordStatusColor(res: Int): Int = when (res) {
        R.drawable.discord_status_online -> 0xFF50A361.toInt()
        R.drawable.discord_status_idle -> 0xFFFF9F09.toInt()
        R.drawable.discord_status_dnd -> 0xFFEC3B37.toInt()
        else -> 0xFF81848F.toInt()
    }

    private fun discordAvatarUrl(): String? {
        val id = PrefManager.getVal(PrefName.DiscordId, null as String?) ?: return null
        val avatar = PrefManager.getVal(PrefName.DiscordAvatar, null as String?) ?: return null
        return "https://cdn.discordapp.com/avatars/$id/$avatar.png"
    }

    /** A short dropdown of the four presence states, anchored to the row (flips above if needed).
     *  Each option shows its own status drawable — the same shapes/colours used everywhere else —
     *  rather than a plain coloured dot. */
    private fun pickDiscordStatus(anchor: View) {
        val labels = discordStatuses.map {
            getString(
                when (it) {
                    "idle" -> R.string.discord_status_idle
                    "dnd" -> R.string.discord_status_dnd
                    "invisible" -> R.string.discord_status_invisible
                    else -> R.string.discord_status_online
                }
            )
        }
        val iconPx = (18 * resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_dropdown, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val statusRes = discordStatusIcon(discordStatuses[position])
                val icon = ContextCompat.getDrawable(context, statusRes)?.mutate()
                icon?.setBounds(0, 0, iconPx, iconPx)
                // As with the row icon, the vector's own `android:tint` isn't reliably applied
                // through AppCompat's compound-drawable path, so colour it explicitly.
                icon?.setColorFilter(discordStatusColor(statusRes), android.graphics.PorterDuff.Mode.SRC_IN)
                row.setCompoundDrawablesRelative(icon, null, null, null)
                row.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()
                return row
            }
        }
        ListPopupWindow(this).apply {
            anchorView = anchor
            setAdapter(adapter)
            isModal = true
            width = (200 * resources.displayMetrics.density).toInt()
            setBackgroundDrawable(ContextCompat.getDrawable(this@SettingsAccountActivity, R.drawable.dropdown_background))
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                PrefManager.setVal(PrefName.DiscordStatus, discordStatuses[position])
                reload()
            }
            show()
        }
    }

    // ---- MALSync "what to check" dialog (ported from the old Connections screen) ----

    private fun showMalSyncChecksDialog(descView: TextView) {
        val modeOptions = arrayOf(
            getString(R.string.malsync_checks_option_manga),
            getString(R.string.malsync_checks_option_anime),
            getString(R.string.malsync_checks_option_both),
        )
        val sortOptions = arrayOf(
            getString(R.string.unread_sort_option_unread),
            getString(R.string.unread_sort_option_recent),
        )
        val dialogView = layoutInflater.inflate(R.layout.dialog_malsync_checks, null)
        val modeDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.malSyncModeDropdown)
        val sortDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.unreadSortDropdown)
        modeDropdown.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, modeOptions))
        sortDropdown.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, sortOptions))
        val currentIndex = when (PrefManager.getVal<String>(PrefName.MalSyncCheckMode)) {
            "manga" -> 0
            "anime" -> 1
            else -> 2
        }
        modeDropdown.setText(modeOptions[currentIndex], false)
        sortDropdown.setText(
            sortOptions[if (PrefManager.getVal<String>(PrefName.UnreadChaptersSort) == "recent") 1 else 0],
            false,
        )
        modeDropdown.setOnItemClickListener { _, _, i, _ ->
            PrefManager.setVal(
                PrefName.MalSyncCheckMode,
                when (i) {
                    0 -> "manga"
                    1 -> "anime"
                    else -> "both"
                },
            )
            descView.text = getString(R.string.malsync_checks_desc, modeOptions[i])
        }
        sortDropdown.setOnItemClickListener { _, _, i, _ ->
            PrefManager.setVal(PrefName.UnreadChaptersSort, if (i == 1) "recent" else "unread")
        }
        customAlertDialog().apply {
            setTitle(R.string.malsync_checks_dialog_title)
            setCustomView(dialogView)
            setPosButton(R.string.close) {}
            setNeutralButton("?") { showConnectionsHelp(R.string.malsync_connections_help, R.string.full_malsync_connections_help) }
            show()
        }
    }

    // ---- info sheet (all providers) ----

    private fun providerLabel(p: AccountProvider): String = getString(
        when (p) {
            AccountProvider.ANILIST -> R.string.anilist
            AccountProvider.MAL -> R.string.myanimelist
            AccountProvider.KITSU -> R.string.kitsu
            AccountProvider.SIMKL -> R.string.simkl
            AccountProvider.MANGAUPDATES -> R.string.mangaupdates
            AccountProvider.MANGABAKA -> R.string.mangabaka
            AccountProvider.COMICK -> R.string.comick
            AccountProvider.MALSYNC -> R.string.malsync
            AccountProvider.DISCORD -> R.string.discord
        }
    )

    private fun showConnectionsHelp(titleRes: Int, bodyRes: Int) {
        val title = getString(titleRes)
        val body = getString(bodyRes)
        val bodyView = TextView(this).apply {
            Markwon.builder(this.context)
                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                .setMarkdown(this, body)
        }
        CustomBottomDialog.newInstance().apply {
            setTitleText(title)
            addView(bodyView)
        }.show(supportFragmentManager, "account_info")
    }

    private fun showInfoSheet(p: AccountProvider) {
        val (titleRes, bodyRes) = when (p) {
            AccountProvider.ANILIST -> R.string.anilist_account_help to R.string.full_anilist_account_help
            AccountProvider.MAL -> R.string.account_help to R.string.full_account_help
            AccountProvider.KITSU -> R.string.kitsu_account_help to R.string.full_kitsu_account_help
            AccountProvider.SIMKL -> R.string.simkl_account_help to R.string.full_simkl_account_help
            AccountProvider.MANGAUPDATES ->
                R.string.mangaupdates_account_help to R.string.full_mangaupdates_account_help
            AccountProvider.MANGABAKA ->
                R.string.mangabaka_account_help to R.string.full_mangabaka_account_help
            AccountProvider.COMICK -> R.string.comick_account_help to R.string.full_comick_account_help
            AccountProvider.MALSYNC -> R.string.malsync_connections_help to R.string.full_malsync_connections_help
            AccountProvider.DISCORD -> R.string.discord_account_help to R.string.full_discord_account_help
        }
        val signedIn = when (p) {
            AccountProvider.ANILIST -> Anilist.token != null
            AccountProvider.MAL -> MAL.token != null
            AccountProvider.KITSU -> Kitsu.token != null
            AccountProvider.SIMKL -> Simkl.token != null
            AccountProvider.MANGAUPDATES -> MangaUpdates.token != null
            AccountProvider.MANGABAKA -> MangaBaka.token != null
            AccountProvider.DISCORD -> Discord.token != null
            AccountProvider.COMICK, AccountProvider.MALSYNC -> false
        }
        // Resolve everything against the activity up front — inside CustomBottomDialog.apply { } the
        // `getString`/`context` receiver is the (not-yet-attached) fragment.
        val title = getString(titleRes)
        val body = getString(bodyRes)
        val openLabel = getString(R.string.account_open_profile)
        val bodyView = TextView(this).apply {
            Markwon.builder(this.context)
                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                .setMarkdown(this, body)
        }
        CustomBottomDialog.newInstance().apply {
            setTitleText(title)
            addView(bodyView)
            if (signedIn) setPositiveButton(openLabel) { openProfile(p) }
        }.show(supportFragmentManager, "account_info")
    }
}
