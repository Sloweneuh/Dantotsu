package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.PresenceSettings
import ani.dantotsu.connections.discord.RPCManager
import ani.dantotsu.databinding.BottomSheetDiscordRpcBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.io.File

/**
 * Rich Presence settings — one set for every kind of media, see [PresenceSettings] — with a
 * preview of how the status looks under them.
 */
class DiscordDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDiscordRpcBinding? = null
    private val binding get() = _binding!!

    private var tokenRefreshJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDiscordRpcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read through PresenceSettings first: that is what carries old settings over.
        val mode = PresenceSettings.mode
        if (mode == PresenceSettings.MODE_DANTOTSU) binding.radioDantotsu.isChecked = true
        else binding.radioMedia.isChecked = true
        binding.switchShowIcon.isChecked = PresenceSettings.showSiteIcon
        binding.switchShowButtons.isChecked = PresenceSettings.showMediaButton
        binding.switchShowProfile.isChecked = PresenceSettings.showProfile
        binding.switchShareBrowsing.isChecked = PrefManager.getVal(PrefName.DiscordRPCBrowsing)

        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            PrefManager.setVal(
                PrefName.DiscordRPCMode,
                if (checkedId == binding.radioDantotsu.id) PresenceSettings.MODE_DANTOTSU
                else PresenceSettings.MODE_MEDIA
            )
            updatePreview()
        }
        binding.switchShowIcon.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.DiscordRPCShowSiteIcon, isChecked)
            updatePreview()
        }
        binding.switchShowButtons.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.DiscordShowButtons, isChecked)
            updatePreview()
        }
        binding.switchShowProfile.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.DiscordRPCShowProfile, isChecked)
            updatePreview()
        }
        binding.switchShareBrowsing.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.DiscordRPCBrowsing, isChecked)
        }

        updatePreview()
        updateTokenExpiry()

        // Auto-refresh the token expiry display every 30s while dialog is open
        tokenRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(30_000L)
                if (_binding != null) updateTokenExpiry()
            }
        }
    }

    private fun updateTokenExpiry() {
        var expiresAt = RPCManager.getTokenExpiresAt()

        // If RPCManager hasn't initialized yet, try loading from disk
        if (expiresAt == 0L) {
            expiresAt = runCatching {
                val ctx = context ?: return
                File(ctx.filesDir, "discord/discord_expiry.txt").readText().toLong()
            }.getOrDefault(0L)
        }

        if (expiresAt > 0L) {
            val remaining = expiresAt - System.currentTimeMillis()
            binding.tokenExpiryStatus.visibility = View.VISIBLE
            if (remaining <= 0) {
                binding.tokenExpiryStatus.text = "⚠ Token expired — will auto-refresh on next RPC"
            } else {
                val days = remaining / (1000 * 60 * 60 * 24)
                val hours = (remaining / (1000 * 60 * 60)) % 24
                val mins = (remaining / (1000 * 60)) % 60
                val timeStr = buildString {
                    if (days > 0) append("${days}d ")
                    if (hours > 0) append("${hours}h ")
                    if (days == 0L) append("${mins}m")
                }.trim()
                binding.tokenExpiryStatus.text = "🔄 Token auto-refreshes in $timeStr"
            }
        } else {
            binding.tokenExpiryStatus.visibility = View.GONE
        }
    }

    /** An anime from AniList, as the settings would show it. */
    private fun updatePreview() {
        val mediaMode = PresenceSettings.mode == PresenceSettings.MODE_MEDIA
        binding.modeDescription.setText(
            if (mediaMode) R.string.discord_media_mode_desc else R.string.discord_dantotsu_mode_desc
        )

        binding.previewActivityName.text = "Watching One-Punch Man Season 3"
        binding.previewDetails.text = "Episode 1: Strategy Meeting"
        binding.previewState.text = "Episode 1/??"
        Glide.with(this).load(R.mipmap.ic_launcher).into(binding.previewLargeImage)

        if (PresenceSettings.showSiteIcon) {
            binding.previewSmallImage.visibility = View.VISIBLE
            Glide.with(this)
                .load(if (mediaMode) Discord.small_Image_AniList else Discord.small_Image)
                .into(binding.previewSmallImage)
        } else {
            binding.previewSmallImage.visibility = View.GONE
        }

        binding.previewButton1.visibility =
            if (PresenceSettings.showMediaButton) View.VISIBLE else View.GONE
        binding.previewButton1.text = "VIEW ANIME ON ANILIST"
        binding.previewButton2.visibility =
            if (PresenceSettings.showProfile) View.VISIBLE else View.GONE
        binding.previewButton2.text = "VIEW PROFILE"
    }

    override fun onDestroy() {
        tokenRefreshJob?.cancel()
        _binding = null
        super.onDestroy()
    }
}
