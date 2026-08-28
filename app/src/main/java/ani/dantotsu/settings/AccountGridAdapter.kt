package ani.dantotsu.settings

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemAccountGridBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage

/** The eight tiles on the Accounts grid, in display order. */
enum class AccountProvider { ANILIST, MAL, KITSU, SIMKL, MANGAUPDATES, MANGABAKA, DISCORD, COMICK }

sealed class AccountState {
    /** Never connected on any device. */
    data object SignedOut : AccountState()

    /** Not signed in on this device, but the account name synced from another one. */
    data class KnownAccount(val name: String) : AccountState()

    data class SignedIn(val name: String, val avatarUrl: String?) : AccountState()

    /** A tracker that needs AniList connected first. */
    data object AniListRequired : AccountState()

    data object ComingSoon : AccountState()
}

data class AccountTile(
    val provider: AccountProvider,
    val logoRes: Int,
    val label: String,
    val state: AccountState,
    /** Discord only, when signed in: the current presence-status drawable. */
    val discordStatusRes: Int? = null,
)

class AccountGridAdapter(
    private val onLogin: (AccountProvider) -> Unit,
    private val onLogout: (AccountProvider) -> Unit,
    private val onAvatarTap: (AccountProvider) -> Unit,
    private val onInfo: (AccountProvider) -> Unit,
    private val onCycleDiscordStatus: () -> Unit,
) : RecyclerView.Adapter<AccountGridAdapter.Holder>() {

    private val items = mutableListOf<AccountTile>()

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(list: List<AccountTile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemAccountGridBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAccountGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = items[position]
        val b = holder.binding
        val ctx = b.root.context
        // Only genuinely non-interactive tiles are greyed out. A "known" account (name synced from
        // another device but not signed in here) stays fully available — just its name is dimmed.
        val dimmed = tile.state is AccountState.AniListRequired ||
            tile.state is AccountState.ComingSoon
        b.root.alpha = if (dimmed) 0.45f else 1f

        // ---- avatar ----
        // Photo and logo are separate views: the photo gets the circular mask (fills the circle),
        // the logo is a plain padded ImageView (a circular mask clips square logos like MangaBaka's).
        val state = tile.state
        if (state is AccountState.SignedIn && !state.avatarUrl.isNullOrBlank()) {
            b.accountAvatar.isVisible = true
            b.accountLogo.isVisible = false
            b.accountAvatar.loadImage(state.avatarUrl)
        } else {
            b.accountAvatar.isVisible = false
            b.accountLogo.isVisible = true
            b.accountLogo.setImageResource(tile.logoRes)
            b.accountLogo.setColorFilter(themeAccent(ctx))
        }

        // ---- badge ----
        val discordStatus = tile.discordStatusRes
        when {
            tile.provider == AccountProvider.DISCORD && state is AccountState.SignedIn && discordStatus != null -> {
                b.accountBadgeCard.isVisible = true
                b.accountBadge.clearColorFilter()
                b.accountBadge.setImageResource(discordStatus)
                b.accountBadgeCard.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    it.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.bounce_zoom))
                    onCycleDiscordStatus()
                }
            }
            state is AccountState.SignedIn -> {
                b.accountBadgeCard.isVisible = true
                b.accountBadgeCard.isClickable = false
                b.accountBadge.setImageResource(tile.logoRes)
                b.accountBadge.setColorFilter(themeAccent(ctx))
            }
            else -> {
                b.accountBadgeCard.isVisible = false
                b.accountBadgeCard.isClickable = false
            }
        }

        // ---- name ----
        b.accountName.text = when (state) {
            is AccountState.SignedIn -> state.name.ifBlank { tile.label }
            is AccountState.KnownAccount -> state.name.ifBlank { tile.label }
            else -> tile.label
        }
        b.accountName.alpha = if (state is AccountState.KnownAccount) 0.5f else 1f

        // ---- action ----
        when (state) {
            is AccountState.SignedIn -> {
                b.accountAction.isVisible = true
                b.accountAction.setText(R.string.logout)
                b.accountAction.setOnClickListener { onLogout(tile.provider) }
            }
            is AccountState.SignedOut, is AccountState.KnownAccount -> {
                b.accountAction.isVisible = true
                b.accountAction.setText(R.string.login)
                b.accountAction.setOnClickListener { onLogin(tile.provider) }
            }
            is AccountState.ComingSoon -> {
                b.accountAction.isVisible = true
                b.accountAction.setText(R.string.coming_soon)
                b.accountAction.setOnClickListener { onInfo(tile.provider) }
            }
            is AccountState.AniListRequired -> {
                b.accountAction.isVisible = false
                b.accountAction.setOnClickListener(null)
            }
        }

        // ---- avatar tap / long-press ----
        b.accountAvatarFrame.setOnClickListener {
            when (state) {
                is AccountState.SignedIn -> onAvatarTap(tile.provider)
                is AccountState.SignedOut, is AccountState.KnownAccount -> onLogin(tile.provider)
                else -> onInfo(tile.provider)
            }
        }
        b.accountAvatarFrame.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onInfo(tile.provider)
            true
        }
    }

    private fun themeAccent(ctx: android.content.Context): Int =
        ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
}
