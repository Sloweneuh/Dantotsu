package ani.dantotsu.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.BuildConfig
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.sync.SyncStatus
import ani.dantotsu.databinding.BottomSheetSettingsBinding
import ani.dantotsu.databinding.ViewTilePanelBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.home.AnimeFragment
import ani.dantotsu.home.HomeFragment
import ani.dantotsu.home.LoginFragment
import ani.dantotsu.home.MangaFragment
import ani.dantotsu.home.NoInternet
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.notification.NotificationActivity
import ani.dantotsu.settings.quicktiles.QuickTileHost
import ani.dantotsu.settings.quicktiles.QuickTiles
import ani.dantotsu.settings.quicktiles.TilePanelController
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.schedule

class SettingsDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var pageType: PageType
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageType = arguments?.getSerializableCompat("pageType") as? PageType ?: PageType.HOME
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val window = dialog?.window
        window?.statusBarColor = Color.CYAN
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)

        val offline = !isOnline(requireContext()) || PrefManager.getVal<Boolean>(PrefName.OfflineMode)

        // The row stays, offline or not. Hiding it outright left the top of the sheet blank, and
        // the parts of it that come from cache — who you are signed in as, and your avatar — are
        // exactly as true without a connection. Only the things that need to reach out go away:
        // notifications, sync state, and opening the profile.
        binding.settingsCloudCard.isVisible = !offline
        binding.settingsNotificationContainer.isVisible = !offline

        // A shortcut back to the home page, pointless when that is where the sheet was opened from.
        // Every screen that is not a main tab reports the default HOME page type, so the home tab is
        // only actually "here" when the host is MainActivity (or the offline home).
        val onHomePage = (activity is MainActivity && pageType == PageType.HOME) ||
            pageType == PageType.OfflineHOME
        binding.settingsHomeCard.isVisible = !onHomePage
        binding.settingsHome.setOnClickListener {
            val intent = Intent(activity, MainActivity::class.java).putExtra(
                "FRAGMENT_CLASS_NAME",
                if (Anilist.token != null) HomeFragment::class.java.name
                else LoginFragment::class.java.name
            )
            startActivity(intent)
            dismiss()
        }

        setupQuickTiles(offline)

        if (offline) {
            binding.settingsUsername.isVisible = Anilist.username != null
            binding.settingsUsername.text = Anilist.username
            // Guarded: loadImage clears the view when handed nothing, glyph and all.
            Anilist.avatar?.takeIf { it.isNotBlank() }
                ?.let { binding.settingsUserAvatar.loadImage(it) }
            // Where login/logout normally sits: a status, not an action. Logging out here would
            // strand the user on a sign-in screen they cannot use.
            binding.settingsLogin.setText(R.string.offline_mode)
            binding.settingsLogin.isClickable = false
        }

        if (!offline) {
            val notificationIcon = if (Anilist.unreadNotificationCount > 0) {
                R.drawable.ic_round_notifications_active_24
            } else {
                R.drawable.ic_round_notifications_none_24
            }
            binding.settingsNotification.setImageResource(notificationIcon)

            if (Anilist.token != null) {
                binding.settingsLogin.setText(R.string.logout)
                binding.settingsLogin.setOnClickListener {
                    requireContext().customAlertDialog().apply {
                        setTitle(R.string.logout)
                        setMessage(R.string.logout_confirm)
                        setPosButton(R.string.yes) {
                            Anilist.removeSavedToken()
                            startMainActivity(requireActivity())
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
                binding.settingsUsername.text = Anilist.username
                binding.settingsUserAvatar.loadImage(Anilist.avatar)
            } else {
                binding.settingsUsername.visibility = View.GONE
                binding.settingsLogin.setText(R.string.login)
                binding.settingsLogin.setOnClickListener {
                    dismiss()
                    Anilist.loginIntent(requireActivity())
                }
            }
            binding.settingsNotificationCount.isVisible = Anilist.unreadNotificationCount > 0
            binding.settingsNotificationCount.text = Anilist.unreadNotificationCount.toString()
            binding.settingsUserAvatar.setOnClickListener {
                ContextCompat.startActivity(
                    requireContext(), Intent(requireContext(), ProfileActivity::class.java)
                        .putExtra("userId", Anilist.userid), null
                )
            }

            binding.settingsNotification.setOnClickListener {
                startActivity(Intent(activity, NotificationActivity::class.java))
                dismiss()
            }

            bindCloudStatus()
        }

    }

    /** The tile panel below the account row: same machinery as the search sheet. */
    private fun setupQuickTiles(offline: Boolean) {
        TilePanelController(
            binding = ViewTilePanelBinding.bind(binding.root),
            catalogue = QuickTiles,
            host = QuickTileHost(
                activity = requireActivity(),
                dismiss = { dismiss() },
                setOfflineMode = ::switchOfflineMode,
            ),
            offline = offline,
        ).apply {
            setFooterText(
                getString(
                    R.string.quick_tiles_version,
                    getString(R.string.app_name),
                    BuildConfig.VERSION_NAME,
                )
            )
            attach()
        }
    }

    /**
     * Leaving or entering offline mode means leaving the current page too, since half of them do
     * not exist on the other side of the switch. Lifted verbatim from the old offline switch.
     */
    private fun switchOfflineMode(enabled: Boolean) {
        Timer().schedule(300) {
            when (pageType) {
                PageType.MANGA, PageType.ANIME, PageType.HOME -> {
                    // Entering offline mode: the single offline home is shown regardless of
                    // which page we came from.
                    startActivity(Intent(activity, NoInternet::class.java))
                }

                PageType.OfflineMANGA -> {
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.putExtra("FRAGMENT_CLASS_NAME", MangaFragment::class.java.name)
                    startActivity(intent)
                }

                PageType.OfflineHOME -> {
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.putExtra(
                        "FRAGMENT_CLASS_NAME",
                        if (Anilist.token != null) HomeFragment::class.java.name else LoginFragment::class.java.name
                    )
                    startActivity(intent)
                }

                PageType.OfflineANIME -> {
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.putExtra("FRAGMENT_CLASS_NAME", AnimeFragment::class.java.name)
                    startActivity(intent)
                }
            }

            dismiss()
            PrefManager.setVal(PrefName.OfflineMode, enabled)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        enum class PageType {
            MANGA, ANIME, HOME, OfflineMANGA, OfflineANIME, OfflineHOME
        }

        fun newInstance(pageType: PageType): SettingsDialogFragment {
            val fragment = SettingsDialogFragment()
            val args = Bundle()
            args.putSerializable("pageType", pageType)
            fragment.arguments = args
            return fragment
        }
    }

    /**
     * Keeps the cloud icon showing what sync is actually doing, for as long as the sheet is open.
     *
     * Collected rather than read once: a pull or push can start and finish while the sheet is up —
     * returning to the app is one of the things that triggers one — so a snapshot would routinely
     * show a transfer that had already ended, or miss one entirely.
     */
    private fun bindCloudStatus() {
        SyncStatus.refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncStatus.state.collect { state ->
                    binding.settingsCloudStatus.setImageResource(
                        when (state) {
                            SyncStatus.State.Disabled -> R.drawable.ic_round_cloud_off_24
                            SyncStatus.State.Synced -> R.drawable.ic_round_cloud_done_24
                            SyncStatus.State.Downloading -> R.drawable.ic_round_cloud_download_24
                            SyncStatus.State.Uploading -> R.drawable.ic_round_cloud_upload_24
                            SyncStatus.State.Conflict -> R.drawable.ic_round_cloud_alert_24
                        }
                    )
                }
            }
        }
        binding.settingsCloudStatus.setOnClickListener {
            // Everything the icon can be reporting is acted on from the same screen: set up a code,
            // resolve a conflict, force either direction, or delete the cloud copy.
            startActivity(Intent(activity, SettingsBackupSyncActivity::class.java))
            dismiss()
        }
    }

}
