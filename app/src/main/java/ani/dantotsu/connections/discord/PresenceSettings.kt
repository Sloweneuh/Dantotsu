package ani.dantotsu.connections.discord

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * How Rich Presence is dressed, one set for every kind of media.
 *
 * Replaces the per-tab anime/manga settings ([PrefName.DiscordRPCModeAnime],
 * [PrefName.DiscordRPCModeManga] and their icon switches), which [migrate] carries over the first
 * time any of these is read.
 */
object PresenceSettings {

    /** The media button and the small icon follow the site the media comes from. */
    const val MODE_MEDIA = "media"

    /** The media button as in [MODE_MEDIA], under Dantotsu's own icon. */
    const val MODE_DANTOTSU = "dantotsu"

    val mode: String
        get() = migrated { PrefManager.getVal<String>(PrefName.DiscordRPCMode) }

    val showSiteIcon: Boolean
        get() = migrated { PrefManager.getVal(PrefName.DiscordRPCShowSiteIcon) }

    val showProfile: Boolean
        get() = migrated { PrefManager.getVal(PrefName.DiscordRPCShowProfile) }

    /**
     * The button to the media's page. Stored under the old "show buttons" key — it only became a
     * working switch shortly before this split, so it is still at its default nearly everywhere.
     */
    val showMediaButton: Boolean
        get() = PrefManager.getVal(PrefName.DiscordShowButtons)

    private inline fun <T> migrated(read: () -> T): T {
        migrate()
        return read()
    }

    /**
     * Carries the old anime-tab settings over — anime was the tab the sheet opened on — once.
     *
     *  - Dantotsu mode stays Dantotsu mode.
     *  - AniList and MAL mode become media mode: the site they picked is what media mode shows
     *    for media from it, and other media now get their own site instead of a missing link.
     *  - "Show only anime/manga link button" becomes media mode without the icon or profile.
     *
     * A device that never touched those settings starts in media mode.
     */
    private fun migrate() {
        if (PrefManager.getVal<String>(PrefName.DiscordRPCMode).isNotEmpty()) return
        val old = PrefManager.getVal(PrefName.DiscordRPCModeAnime, UNSET)
        val oldIcon = PrefManager.getVal(PrefName.DiscordRPCShowIconAnime, true)
        when (old) {
            UNSET -> set(MODE_MEDIA, icon = true, profile = true)
            "dantotsu" -> set(MODE_DANTOTSU, icon = oldIcon, profile = true)
            "nothing" -> set(MODE_MEDIA, icon = false, profile = false)
            else -> set(MODE_MEDIA, icon = oldIcon, profile = true)
        }
    }

    private fun set(mode: String, icon: Boolean, profile: Boolean) {
        PrefManager.setVal(PrefName.DiscordRPCShowSiteIcon, icon)
        PrefManager.setVal(PrefName.DiscordRPCShowProfile, profile)
        // Last: its being set is what marks the migration done.
        PrefManager.setVal(PrefName.DiscordRPCMode, mode)
    }

    private const val UNSET = "\u0000unset"
}
