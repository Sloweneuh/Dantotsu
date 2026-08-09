package ani.dantotsu.settings

import ani.dantotsu.R
import ani.dantotsu.databinding.LayoutSearchEmptyStateBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.showNoResults

/**
 * The placeholder an extensions tab shows when it lists nothing.
 *
 * "No results found for your query" is only true when the user narrowed the list themselves, with
 * the search box or the language picker — those they can widen again. A tab that is empty on its
 * own terms has to say why instead, and an Available tab with no repository configured says the one
 * thing that will actually fill it.
 *
 * @param filtered whether a search query or the language picker is currently narrowing the list.
 * @param installed an Installed tab rather than an Available one.
 * @param repoPref which repository list feeds this tab, for the Available tabs. Null skips the
 *   check, for a list that doesn't come from repositories.
 */
fun LayoutSearchEmptyStateBinding.showExtensionsEmpty(
    filtered: Boolean,
    installed: Boolean,
    repoPref: PrefName? = null,
) {
    if (filtered) {
        showNoResults()
        return
    }
    val hasRepos = repoPref == null ||
            PrefManager.getVal<Set<String>>(repoPref).isNotEmpty()
    showNoResults(
        root.context.getString(
            when {
                installed -> R.string.extensions_none_installed
                !hasRepos -> R.string.extensions_no_repos
                else -> R.string.extensions_none_available
            }
        )
    )
}

/** True while the language picker is narrowing a list to one language rather than showing all. */
fun isLanguageFiltered(): Boolean = PrefManager.getVal<String>(PrefName.LangSort) != "all"
