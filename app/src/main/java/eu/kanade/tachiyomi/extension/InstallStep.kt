package eu.kanade.tachiyomi.extension

/**
 * [RequiresUserAction] is reported when an install was run unattended and the system refused to
 * complete it without the user confirming — typically because this app is not the installer of
 * record for the package being replaced. It is terminal: the extension is untouched and still
 * carries its update, and it is the caller's job to decide whether to surface the confirmation.
 */
enum class InstallStep {
    Idle, Pending, Downloading, Installing, Installed, RequiresUserAction, Error;

    fun isCompleted(): Boolean {
        return this == Installed || this == Error || this == Idle || this == RequiresUserAction
    }
}
