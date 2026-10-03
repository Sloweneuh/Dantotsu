package ani.dantotsu.connections.discord

import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The opt-in "browsing" presence ([PrefName.DiscordRPCBrowsing]) for a media page: which title is
 * open, while the page is in front and nothing else is on Discord.
 *
 * Created once per page as a property; the page hands it what it shows through [update] once
 * that has loaded, and it does the rest — publishing when the page resumes, clearing when it
 * pauses. A player or reader opened from the page takes over, and the presence comes back when it
 * closes, see [RPCManager.setPresence].
 */
class BrowsingPresence(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    /** What the page shows, and [source] — the site the page is on — for the button and icon. */
    data class Page(
        val title: String,
        val cover: String?,
        val source: RPC.Source?,
    )

    private var page: Page? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    /** Sets what the page shows, and publishes it straight away if the page is already in front. */
    fun update(page: Page) {
        this.page = page
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) publish()
    }

    // ON_RESUME is dispatched after the activity's own onResume, so the page is in front by now.
    override fun onResume(owner: LifecycleOwner) = publish()

    override fun onPause(owner: LifecycleOwner) =
        RPCManager.clearPresence(activity, RPC.Kind.BROWSING)

    private fun publish() {
        val page = page ?: return
        if (!PrefManager.getVal<Boolean>(PrefName.DiscordRPCBrowsing)) return
        if (!RPCManager.isAllowed(activity)) return
        RPCManager.setPresence(
            activity,
            RPC.Companion.RPCData(
                applicationId = Discord.application_Id,
                kind = RPC.Kind.BROWSING,
                type = RPC.Type.WATCHING,
                activityName = "Dantotsu",
                details = activity.getString(R.string.discord_browsing_details),
                state = page.title,
                largeImage = page.cover?.takeIf { it.startsWith("http") }?.let { RPC.Link(page.title, it) },
                source = page.source,
            )
        )
    }
}
