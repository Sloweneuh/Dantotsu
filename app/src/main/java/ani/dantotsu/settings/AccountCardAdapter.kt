package ani.dantotsu.settings

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemAccountCardBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage

/** The provider cards on the Accounts screen, in display order. */
enum class AccountProvider { ANILIST, MANGAUPDATES, MAL, MANGABAKA, KITSU, SIMKL, DISCORD, COMICK, MALSYNC }

sealed class AccountState {
    /** Never connected on any device. */
    data object SignedOut : AccountState()

    /** Not signed in on this device, but the account name synced from another one. */
    data class KnownAccount(val name: String) : AccountState()

    data class SignedIn(val name: String, val avatarUrl: String?) : AccountState()

    /** No login concept at all — an info source only (Comick, MALSync). */
    data object NoLogin : AccountState()
}

data class AccountCard(
    val provider: AccountProvider,
    val logoRes: Int,
    val label: String,
    val state: AccountState,
    /** false → the Login button is shown disabled (a tracker that needs AniList connected first). */
    val loginEnabled: Boolean = true,
    /** Discord only, when signed in: the current presence-status drawable for the badge. */
    val discordStatusRes: Int? = null,
)

/**
 * A vertical list of collapsible provider cards. Collapsed a card shows the mark, name, avatar and
 * a login/logout control; expanded it drops down that provider's own toggles and shortcuts, built
 * by [rowsFor] and rendered with the shared [SettingsAdapter].
 */
class AccountCardAdapter(
    private val onLogin: (AccountProvider) -> Unit,
    private val onLogout: (AccountProvider) -> Unit,
    private val onAvatarTap: (AccountProvider) -> Unit,
    private val onInfo: (AccountProvider) -> Unit,
    private val rowsFor: (AccountProvider) -> List<Settings>,
) : RecyclerView.Adapter<AccountCardAdapter.Holder>() {

    private val items = mutableListOf<AccountCard>()
    private val expanded = mutableSetOf<AccountProvider>()

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(list: List<AccountCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Where [provider]'s card sits, or -1 if it isn't in the current list. */
    fun positionOf(provider: AccountProvider): Int = items.indexOfFirst { it.provider == provider }

    /** Expands [provider]'s card (a no-op if it's already open, or not yet in the list) — used to
     *  land on a setting search brought up from inside a collapsed card. */
    fun expand(provider: AccountProvider) {
        if (expanded.add(provider)) {
            val pos = positionOf(provider)
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    inner class Holder(val b: ItemAccountCardBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAccountCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = items[position]
        val b = holder.b
        val ctx = b.root.context
        val state = card.state
        val accent = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)

        // ---- avatar / mark / badge ----
        when {
            state is AccountState.SignedIn && !state.avatarUrl.isNullOrBlank() -> {
                b.accountAvatar.isVisible = true
                b.accountLogo.isVisible = false
                b.accountAvatar.loadImage(state.avatarUrl)
            }
            state is AccountState.SignedIn -> {
                b.accountAvatar.isVisible = false
                b.accountLogo.isVisible = true
                b.accountLogo.setImageResource(R.drawable.ic_round_person_24)
                b.accountLogo.setColorFilter(accent)
            }
            else -> {
                b.accountAvatar.isVisible = false
                b.accountLogo.isVisible = true
                b.accountLogo.setImageResource(card.logoRes)
                b.accountLogo.setColorFilter(accent)
            }
        }

        val statusRes = card.discordStatusRes
        when {
            card.provider == AccountProvider.DISCORD && state is AccountState.SignedIn && statusRes != null -> {
                b.accountBadgeCard.isVisible = true
                b.accountBadgeCard.radius = 10.5f * ctx.resources.displayMetrics.density
                // The vectors' own `android:tint` isn't reliably applied through AppCompat, so colour
                // the dot explicitly instead of leaving it white.
                b.accountBadge.imageTintList = null
                b.accountBadge.setImageResource(statusRes)
                b.accountBadge.setColorFilter(discordStatusColor(statusRes))
            }
            state is AccountState.SignedIn -> {
                b.accountBadgeCard.isVisible = true
                b.accountBadgeCard.radius = 7f * ctx.resources.displayMetrics.density
                b.accountBadge.imageTintList = null
                b.accountBadge.setImageResource(card.logoRes)
                b.accountBadge.setColorFilter(accent)
            }
            else -> b.accountBadgeCard.isVisible = false
        }

        // ---- name / subtitle ----
        b.accountName.text = when (state) {
            is AccountState.SignedIn -> state.name.ifBlank { card.label }
            is AccountState.KnownAccount -> state.name.ifBlank { card.label }
            else -> card.label
        }
        b.accountName.alpha = if (state is AccountState.KnownAccount) 0.5f else 1f
        b.accountSub.text = when {
            // An avatar + the Logout button already say "connected" — no need to spell it out too,
            // and doing so was the line most likely to get clipped on a narrow phone.
            state is AccountState.SignedIn -> card.label
            state is AccountState.KnownAccount -> card.label
            state is AccountState.NoLogin -> ctx.getString(R.string.account_info_source)
            !card.loginEnabled -> ctx.getString(R.string.account_needs_anilist)
            else -> ctx.getString(R.string.account_not_connected)
        }

        // ---- login / logout / info ----
        b.accountInfo.setOnClickListener { onInfo(card.provider) }
        when {
            state is AccountState.SignedIn -> {
                b.accountLogin.isVisible = false
                b.accountLogout.isVisible = true
                b.accountLogout.setOnClickListener { onLogout(card.provider) }
            }
            state is AccountState.NoLogin -> {
                b.accountLogin.isVisible = false
                b.accountLogout.isVisible = false
            }
            else -> {
                b.accountLogout.isVisible = false
                b.accountLogin.isVisible = true
                b.accountLogin.isEnabled = card.loginEnabled
                b.accountLogin.setOnClickListener { onLogin(card.provider) }
            }
        }

        // ---- expansion ----
        val open = card.provider in expanded
        b.accountChevron.animate().cancel()
        b.accountChevron.rotation = if (open) 180f else 0f
        bindBody(b, card, open)

        b.accountCardHeader.setOnClickListener { toggle(b, card) }
        b.accountAvatarFrame.setOnClickListener {
            if (state is AccountState.SignedIn) onAvatarTap(card.provider) else toggle(b, card)
        }
        b.accountAvatarFrame.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onInfo(card.provider)
            true
        }
    }

    private fun toggle(b: ItemAccountCardBinding, card: AccountCard) {
        val nowOpen = if (card.provider in expanded) {
            expanded.remove(card.provider); false
        } else {
            expanded.add(card.provider); true
        }
        b.accountChevron.animate().rotation(if (nowOpen) 180f else 0f).setDuration(200).start()
        bindBody(b, card, nowOpen)
    }

    private fun bindBody(b: ItemAccountCardBinding, card: AccountCard, open: Boolean) {
        if (!open) {
            b.accountBody.isVisible = false
            b.accountBodyDivider.isVisible = false
            return
        }
        val rows = rowsFor(card.provider)
        if (b.accountBody.layoutManager == null) {
            b.accountBody.layoutManager = LinearLayoutManager(b.root.context)
        }
        b.accountBody.adapter = SettingsAdapter(ArrayList(rows))
        b.accountBody.isVisible = rows.isNotEmpty()
        b.accountBodyDivider.isVisible = rows.isNotEmpty()
    }

    /** Matches each status drawable's own baked-in colour (see the discord_status_* vectors). */
    private fun discordStatusColor(res: Int): Int = when (res) {
        R.drawable.discord_status_online -> 0xFF50A361.toInt()
        R.drawable.discord_status_idle -> 0xFFFF9F09.toInt()
        R.drawable.discord_status_dnd -> 0xFFEC3B37.toInt()
        else -> 0xFF81848F.toInt()
    }
}
