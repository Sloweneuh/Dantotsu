package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.kitsu.KitsuLoginDialog
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaLoginDialog
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MangaUpdatesLoginDialog
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.connections.simkl.SimklLoginDialog
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
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
    private lateinit var gridAdapter: AccountGridAdapter
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

        binding.settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // After a login/logout the app data has to be reloaded; a back press then reopens MainActivity.
        onBackPressedDispatcher.addCallback(this, restartMainActivity)

        gridAdapter = AccountGridAdapter(
            onLogin = { startLogin(it) },
            onLogout = { logout(it) },
            onAvatarTap = { openProfile(it) },
            onInfo = { showInfoSheet(it) },
            onCycleDiscordStatus = { cycleDiscordStatus() },
        )
        binding.accountGrid.layoutManager = GridLayoutManager(this, 4)
        binding.accountGrid.adapter = gridAdapter

        reload()
    }

    // ---- grid ----

    private fun reload() {
        gridAdapter.submit(buildTiles())
        buildSettingsList()
        // MAL caches its profile lazily; fetch it once so the tile isn't left blank after login.
        if (MAL.token != null && (MAL.username == null || MAL.avatar == null)) {
            lifecycleScope.launch {
                MAL.query.getUserData()
                gridAdapter.submit(buildTiles())
            }
        }
    }

    private fun buildTiles(): List<AccountTile> {
        val anilistIn = Anilist.token != null
        return listOf(
            tile(AccountProvider.ANILIST, R.drawable.ic_anilist, R.string.anilist,
                if (anilistIn) AccountState.SignedIn(
                    Anilist.username ?: knownName(PrefName.AnilistUserName), Anilist.avatar
                ) else knownOrOut(PrefName.AnilistUserName)),
            gatedTile(AccountProvider.MAL, R.drawable.ic_myanimelist, R.string.myanimelist, anilistIn,
                if (MAL.token != null) AccountState.SignedIn(
                    MAL.username ?: knownName(PrefName.MALUserName), MAL.avatar
                ) else knownOrOut(PrefName.MALUserName)),
            gatedTile(AccountProvider.KITSU, R.drawable.ic_kitsu, R.string.kitsu, anilistIn,
                if (Kitsu.token != null) AccountState.SignedIn(
                    Kitsu.username ?: knownName(PrefName.KitsuUserName), Kitsu.avatar
                ) else knownOrOut(PrefName.KitsuUserName)),
            gatedTile(AccountProvider.SIMKL, R.drawable.ic_simkl, R.string.simkl, anilistIn,
                if (Simkl.token != null) AccountState.SignedIn(
                    Simkl.username ?: knownName(PrefName.SimklUserName), Simkl.avatar
                ) else knownOrOut(PrefName.SimklUserName)),
            gatedTile(AccountProvider.MANGAUPDATES, R.drawable.ic_round_mangaupdates_24, R.string.mangaupdates, anilistIn,
                if (MangaUpdates.token != null) AccountState.SignedIn(
                    MangaUpdates.username ?: knownName(PrefName.MangaUpdatesUsername), MangaUpdates.avatar
                ) else knownOrOut(PrefName.MangaUpdatesUsername)),
            gatedTile(AccountProvider.MANGABAKA, R.drawable.ic_round_mangabaka_24, R.string.mangabaka, anilistIn,
                if (MangaBaka.token != null) AccountState.SignedIn(
                    MangaBaka.username ?: knownName(PrefName.MangaBakaUserName), null
                ) else knownOrOut(PrefName.MangaBakaUserName)),
            AccountTile(
                AccountProvider.DISCORD, R.drawable.ic_discord, getString(R.string.discord),
                if (Discord.token != null) AccountState.SignedIn(
                    PrefManager.getVal(PrefName.DiscordUserName, null as String?)
                        ?: knownName(PrefName.DiscordUserName),
                    discordAvatarUrl(),
                ) else knownOrOut(PrefName.DiscordUserName),
                discordStatusRes = if (Discord.token != null) discordStatusDrawable() else null,
            ),
            AccountTile(AccountProvider.COMICK, R.drawable.ic_round_comick_24, getString(R.string.comick),
                AccountState.ComingSoon),
        )
    }

    private fun tile(p: AccountProvider, logo: Int, label: Int, state: AccountState) =
        AccountTile(p, logo, getString(label), state)

    private fun gatedTile(p: AccountProvider, logo: Int, label: Int, anilistIn: Boolean, state: AccountState) =
        AccountTile(p, logo, getString(label), if (anilistIn) state else AccountState.AniListRequired)

    private fun knownName(pref: PrefName): String = PrefManager.getVal<String>(pref)

    private fun knownOrOut(pref: PrefName): AccountState {
        val name = PrefManager.getVal<String>(pref)
        return if (name.isBlank()) AccountState.SignedOut else AccountState.KnownAccount(name)
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
            AccountProvider.MANGABAKA -> MangaBakaLoginDialog().apply {
                setOnLoginSuccessListener { onLoggedIn() }
            }.show(supportFragmentManager, "mangabaka_login")
            AccountProvider.DISCORD -> Discord.warning(this).show(supportFragmentManager, "dialog")
            AccountProvider.COMICK -> showInfoSheet(p)
        }
    }

    private fun onLoggedIn() {
        restartMainActivity.isEnabled = true
        reload()
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
            AccountProvider.COMICK -> return
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
            AccountProvider.COMICK -> null
        }
        if (url != null) openLinkInBrowser(url) else showInfoSheet(p)
    }

    private fun cycleDiscordStatus() {
        val next = when (PrefManager.getVal<String>(PrefName.DiscordStatus)) {
            "online" -> "idle"
            "idle" -> "dnd"
            "dnd" -> "invisible"
            else -> "online"
        }
        PrefManager.setVal(PrefName.DiscordStatus, next)
        reload()
    }

    private fun discordStatusDrawable(): Int = when (PrefManager.getVal<String>(PrefName.DiscordStatus)) {
        "idle" -> R.drawable.discord_status_idle
        "dnd" -> R.drawable.discord_status_dnd
        "invisible" -> R.drawable.discord_status_invisible
        else -> R.drawable.discord_status_online
    }

    private fun discordAvatarUrl(): String? {
        val id = PrefManager.getVal(PrefName.DiscordId, null as String?) ?: return null
        val avatar = PrefManager.getVal(PrefName.DiscordAvatar, null as String?) ?: return null
        return "https://cdn.discordapp.com/avatars/$id/$avatar.png"
    }

    // ---- info sheet (all providers) ----

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
            AccountProvider.DISCORD -> R.string.discord_account_help to R.string.full_discord_account_help
            AccountProvider.COMICK -> R.string.comick_account_help to R.string.full_comick_account_help
        }
        val signedIn = when (p) {
            AccountProvider.ANILIST -> Anilist.token != null
            AccountProvider.MAL -> MAL.token != null
            AccountProvider.KITSU -> Kitsu.token != null
            AccountProvider.SIMKL -> Simkl.token != null
            AccountProvider.MANGAUPDATES -> MangaUpdates.token != null
            AccountProvider.MANGABAKA -> MangaBaka.token != null
            AccountProvider.DISCORD -> Discord.token != null
            AccountProvider.COMICK -> false
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

    // ---- the settings list below the grid (unchanged behaviour) ----

    private fun buildSettingsList() {
        val context = this
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.enable_rpc),
                    desc = getString(R.string.enable_rpc_desc),
                    icon = R.drawable.ic_discord,
                    isChecked = PrefManager.getVal(PrefName.rpcEnabled),
                    switch = { isChecked, _ -> PrefManager.setVal(PrefName.rpcEnabled, isChecked) },
                    isVisible = Discord.token != null,
                    attachToSwitch = {
                        it.settingsExtraIcon.visibility = android.view.View.VISIBLE
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
                    isActivity = true,
                    isVisible = Anilist.token != null,
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
                    isVisible = MAL.token != null || Kitsu.token != null ||
                        Simkl.token != null || MangaBaka.token != null
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
        if (binding.settingsRecyclerView.layoutManager == null) {
            binding.settingsRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }
    }
}
