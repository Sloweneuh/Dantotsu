package ani.dantotsu.connections.handoff.transport

import ani.dantotsu.settings.saving.PrefManager
import java.util.UUID

/**
 * Stable per-install identity used to de-duplicate a receiver that's discovered over more than one
 * transport at once (Nearby + LAN).
 *
 * A MAC address would seem the natural key, but it isn't usable: Nearby Connections hides the
 * medium and never exposes one, NSD only yields a host/port, and Wi-Fi MAC randomization plus the
 * Android 6+ lockdown make even a device's own MAC unreadable. So instead we generate a random id
 * once per install and stamp it onto every advertisement; both transports carry the same value, so
 * the same device collapses to one entry regardless of how it was found.
 */
internal object HandoffDevice {

    private const val ID_KEY = "handoff_device_id"

    /** Random, generated once and persisted; 12 hex chars is plenty to be unique among nearby devices. */
    val id: String by lazy {
        PrefManager.getCustomVal(ID_KEY, "").ifEmpty {
            UUID.randomUUID().toString().replace("-", "").take(12).also {
                PrefManager.setCustomVal(ID_KEY, it)
            }
        }
    }
}
