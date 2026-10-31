package ani.dantotsu.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ExtensionSync
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.AddRepositoryBottomSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * The anime extensions, from the couch: what needs updating, what the user's other devices have
 * that this one doesn't, what's installed, and everything their repositories offer — installed,
 * updated and removed through the same manager as the phone's list (so the system installer
 * prompt, which the remote can answer, comes up as usual). A repository can be added by URL too.
 */
class TvExtensionsActivity : AppCompatActivity(), TvScreen {

    private val manager: AnimeExtensionManager = Injekt.get()

    /** Installs and updates under way, by package: what each row says while it runs. */
    private val steps = mutableStateMapOf<String, InstallStep>()

    /** Packages the user's other devices have and this one doesn't (cloud sync), or none known. */
    private var fromOtherDevices by mutableStateOf<Set<String>>(emptySet())

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme {
                val installed by manager.installedExtensionsFlow.collectAsState()
                val available by manager.availableExtensionsFlow.collectAsState()
                TvExtensions(
                    installed = installed,
                    available = available,
                    steps = steps,
                    fromOtherDevices = fromOtherDevices,
                    onInstall = ::install,
                    onUpdate = ::update,
                    onUninstall = { manager.uninstallExtension(it.pkgName) },
                    onAddRepo = ::addRepo,
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { manager.findAvailableExtensions() }
            loadFromOtherDevices()
        }
    }

    private suspend fun loadFromOtherDevices() {
        // Read whether or not this device syncs its extensions: knowing what the others have is
        // what a TV needs to get going, and reading changes nothing.
        val diff = runCatching { ExtensionSync.computeDiff(readOnly = true) }.getOrNull() ?: return
        val missing = diff.toInstall.filter { it.type == ExtensionSync.ExtType.ANIME }.map { it.pkgName }.toSet()
        withContext(Dispatchers.Main) { fromOtherDevices = missing }
    }

    private fun install(extension: AnimeExtension.Available) = follow(extension.pkgName) { manager.installExtension(extension) }

    private fun update(extension: AnimeExtension.Installed) = follow(extension.pkgName) { manager.updateExtension(extension) }

    /** Runs an install or update, its steps shown on the row, until it's done or fails. */
    private fun follow(pkg: String, start: () -> rx.Observable<InstallStep>) {
        if (steps[pkg]?.isCompleted() == false) return
        steps[pkg] = InstallStep.Pending
        start().observeOn(AndroidSchedulers.mainThread()).subscribe(
            { step -> steps[pkg] = step },
            { error ->
                Logger.log("TvExtensions: install of $pkg failed: ${error.message}")
                steps[pkg] = InstallStep.Error
                snackString(getString(R.string.tv_ext_failed_named, pkg.substringAfterLast('.')))
            },
            {
                if (steps[pkg] != InstallStep.Error) steps.remove(pkg)
                fromOtherDevices = fromOtherDevices - pkg
            },
        )
    }

    private fun addRepo(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) {
            snackString(getString(R.string.tv_ext_repo_invalid))
            return
        }
        AddRepositoryBottomSheet.addRepo(trimmed, MediaType.ANIME)
        snackString(getString(R.string.tv_ext_repo_added))
    }
}

/** What a row is doing, if anything: its install or update step in words. */
@Composable
private fun stepLabel(step: InstallStep?): String? = when (step) {
    InstallStep.Pending -> stringResource(R.string.tv_ext_pending)
    InstallStep.Downloading -> stringResource(R.string.tv_ext_downloading)
    InstallStep.Installing -> stringResource(R.string.tv_ext_installing)
    InstallStep.RequiresUserAction -> stringResource(R.string.tv_ext_confirm)
    InstallStep.Error -> stringResource(R.string.tv_ext_failed)
    else -> null
}

private fun languageName(lang: String): String = when (lang) {
    "all", "" -> "Multi"
    "other" -> "Other"
    else -> Locale.forLanguageTag(lang).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase() }
}

@Composable
private fun TvExtensions(
    installed: List<AnimeExtension.Installed>,
    available: List<AnimeExtension.Available>,
    steps: Map<String, InstallStep>,
    fromOtherDevices: Set<String>,
    onInstall: (AnimeExtension.Available) -> Unit,
    onUpdate: (AnimeExtension.Installed) -> Unit,
    onUninstall: (AnimeExtension.Installed) -> Unit,
    onAddRepo: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf<AnimeExtension.Installed?>(null) }
    var addingRepo by remember { mutableStateOf(false) }
    var managingRepos by remember { mutableStateOf(false) }
    // Re-read when the dialog changes them: a pref, not state.
    var reposVersion by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val showNsfw = PrefManager.getVal<Boolean>(PrefName.NSFWExtension)
    val installedPkgs = installed.map { it.pkgName }.toSet()
    fun matches(name: String) = query.isBlank() || name.contains(query.trim(), ignoreCase = true)
    val updates = installed.filter { it.hasUpdate && matches(it.name) }
    val missing = available.filter { it.pkgName in fromOtherDevices && it.pkgName !in installedPkgs }
    val shownInstalled = installed.filter { matches(it.name) }.sortedBy { it.name.lowercase() }
    // Installed ones stay, marked: a row that left the list as it installed took the focus with
    // it, and the focus fell to the search field, keyboard and all.
    val shownAvailable = available
        .filter { (showNsfw || !it.isNsfw) && matches(it.name) }
        .sortedBy { it.name.lowercase() }
    val installedByPkg = installed.associateBy { it.pkgName }
    val noRepos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).isEmpty()

    // Focus starts on the first row, not the search field: there it brought the keyboard up before
    // anything was asked of it. Placed once, when there's a first row to place it on.
    val first = remember { FocusRequester() }
    val firstKey = updates.firstOrNull()?.pkgName?.let { "u$it" }
        ?: missing.firstOrNull()?.let { "all" }
        ?: shownInstalled.firstOrNull()?.pkgName?.let { "i$it" }
        ?: shownAvailable.firstOrNull()?.pkgName?.let { "a$it" }
    var focusPlaced by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(firstKey) {
        if (firstKey != null && !focusPlaced) {
            androidx.compose.runtime.withFrameNanos { }
            focusPlaced = runCatching { first.requestFocus() }.isSuccess
            // The TV hands a new screen's focus to its first field on its own, keyboard and all; the
            // keyboard outlives the field losing focus, so it's put away through the window.
            (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    fun startsHere(key: String) = if (key == firstKey) Modifier.focusRequester(first) else Modifier

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(760.dp).padding(top = 36.dp)) {
            Text(stringResource(R.string.tv_extensions), style = MaterialTheme.typography.displaySmall)
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                var searching by remember { mutableStateOf(false) }
                val searchField = remember { FocusRequester() }
                if (searching) {
                    LaunchedEffect(Unit) {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { searchField.requestFocus() }
                    }
                    TvSearchField(
                        query = query,
                        onQuery = { query = it },
                        hint = stringResource(R.string.tv_ext_search),
                        onSubmit = { searching = false },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchField)
                            .onFocusChanged { if (!it.isFocused && !it.hasFocus) searching = false },
                    )
                } else {
                    val search = { searching = true }
                    OutlinedButton(
                        onClick = search,
                        modifier = Modifier.weight(1f).pointerClick(onClick = search),
                    ) {
                        androidx.tv.material3.Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_round_search_24),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 10.dp).size(20.dp),
                        )
                        Text(query.ifBlank { stringResource(R.string.tv_ext_search) }, maxLines = 1)
                    }
                }
                val repos = { managingRepos = true }
                OutlinedButton(onClick = repos, modifier = Modifier.pointerClick(onClick = repos)) {
                    Text(stringResource(R.string.tv_ext_repos))
                }
            }
            FocusRestingAt(120.dp) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (noRepos) item { Note(stringResource(R.string.tv_ext_no_repos)) }

                    if (updates.isNotEmpty()) {
                        item { Section(R.string.tv_ext_updates) }
                        items(updates, key = { "u" + it.pkgName }) { ext ->
                            val update = { onUpdate(ext) }
                            ExtensionRow(
                                name = ext.name,
                                detail = "${languageName(ext.lang)} · ${ext.versionName}",
                                icon = { InstalledIcon(ext) },
                                action = stepLabel(steps[ext.pkgName]) ?: stringResource(R.string.tv_ext_update),
                                onClick = update,
                                modifier = startsHere("u" + ext.pkgName),
                            )
                        }
                    }

                    if (missing.isNotEmpty()) {
                        item { Section(R.string.tv_ext_from_devices) }
                        item {
                            val all = { missing.forEach(onInstall) }
                            ListItem(
                                selected = false,
                                onClick = all,
                                headlineContent = { Text(stringResource(R.string.tv_ext_install_all, missing.size)) },
                                modifier = startsHere("all").pointerClick(onClick = all),
                            )
                        }
                        items(missing, key = { "m" + it.pkgName }) { ext ->
                            AvailableRow(ext, steps[ext.pkgName], onInstall)
                        }
                    }

                    if (shownInstalled.isNotEmpty()) {
                        item { Section(R.string.tv_ext_installed) }
                        items(shownInstalled, key = { "i" + it.pkgName }) { ext ->
                            val manage = { managing = ext }
                            ExtensionRow(
                                name = ext.name,
                                detail = "${languageName(ext.lang)} · ${ext.versionName}",
                                icon = { InstalledIcon(ext) },
                                action = stepLabel(steps[ext.pkgName])
                                    ?: if (ext.hasUpdate) stringResource(R.string.tv_ext_update) else null,
                                onClick = manage,
                                modifier = startsHere("i" + ext.pkgName),
                            )
                        }
                    }

                    item { Section(R.string.tv_ext_available) }
                    if (shownAvailable.isEmpty()) {
                        item { Note(stringResource(if (available.isEmpty() && !noRepos) R.string.tv_ext_loading else R.string.tv_ext_none)) }
                    }
                    items(shownAvailable, key = { "a" + it.pkgName }) { ext ->
                        AvailableRow(
                            ext, steps[ext.pkgName], onInstall, startsHere("a" + ext.pkgName),
                            installed = installedByPkg[ext.pkgName],
                            onManage = { managing = it },
                        )
                    }
                }
            }
        }
    }

    managing?.let { ext ->
        TvDialog(onDismissRequest = { managing = null }) {
            Surface(shape = CARD_SHAPE, modifier = Modifier.width(440.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(ext.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${languageName(ext.lang)} · ${ext.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                    )
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    // Its settings, a button a source: an extension can carry one per language.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val configurable = ext.sources.filterIsInstance<eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource>()
                    if (configurable.isNotEmpty()) Column(
                        Modifier.padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        configurable.forEachIndexed { i, source ->
                            val open = {
                                managing = null
                                context.startActivity(
                                    android.content.Intent(context, TvExtensionSettingsActivity::class.java)
                                        .putExtra(TvExtensionSettingsActivity.EXTRA_SOURCE, source.id)
                                )
                            }
                            val lang = (source as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource)?.lang
                            // Focus starts on a harmless button: Settings, else Cancel (never Uninstall).
                            OutlinedButton(
                                onClick = open,
                                modifier = (if (i == 0) Modifier.focusRequester(focus) else Modifier).pointerClick(onClick = open),
                            ) {
                                Text(
                                    if (configurable.size > 1 && lang != null) stringResource(R.string.tv_ext_settings_for, languageName(lang))
                                    else stringResource(R.string.tv_ext_settings)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (ext.hasUpdate) {
                            val update = { managing = null; onUpdate(ext) }
                            Button(onClick = update, modifier = Modifier.pointerClick(onClick = update)) {
                                Text(stringResource(R.string.tv_ext_update))
                            }
                        }
                        val uninstall = { managing = null; onUninstall(ext) }
                        OutlinedButton(
                            onClick = uninstall,
                            modifier = Modifier.pointerClick(onClick = uninstall),
                        ) { Text(stringResource(R.string.tv_ext_uninstall)) }
                        val cancel = { managing = null }
                        OutlinedButton(
                            onClick = cancel,
                            modifier = (if (configurable.isEmpty()) Modifier.focusRequester(focus) else Modifier).pointerClick(onClick = cancel),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }

    if (managingRepos) {
        val repos = remember(reposVersion) { PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).sorted() }
        var removing by remember { mutableStateOf<String?>(null) }
        TvDialog(onDismissRequest = { managingRepos = false }) {
            Surface(shape = CARD_SHAPE, modifier = Modifier.width(640.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.tv_ext_repos), style = MaterialTheme.typography.headlineSmall)
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        androidx.compose.runtime.withFrameNanos { }
                        runCatching { focus.requestFocus() }
                    }
                    if (repos.isEmpty()) Note(stringResource(R.string.tv_ext_no_repos))
                    LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 420.dp)) {
                        items(repos, key = { it }) { repo ->
                            val ask = { removing = repo }
                            ListItem(
                                selected = false,
                                onClick = ask,
                                headlineContent = { Text(repo, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text(stringResource(R.string.tv_ext_remove_repo)) },
                                modifier = (if (repo == repos.first()) Modifier.focusRequester(focus) else Modifier).pointerClick(onClick = ask),
                            )
                        }
                        item {
                            val add = { managingRepos = false; addingRepo = true }
                            ListItem(
                                selected = false,
                                onClick = add,
                                headlineContent = { Text(stringResource(R.string.tv_ext_add_repo)) },
                                modifier = (if (repos.isEmpty()) Modifier.focusRequester(focus) else Modifier).pointerClick(onClick = add),
                            )
                        }
                    }
                }
            }
        }
        removing?.let { repo ->
            TvDialog(onDismissRequest = { removing = null }) {
                Surface(shape = CARD_SHAPE, modifier = Modifier.width(520.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.tv_ext_remove_repo_title), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            repo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        val cancelFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val remove = {
                                AddRepositoryBottomSheet.removeRepo(repo, MediaType.ANIME)
                                removing = null
                                reposVersion += 1
                            }
                            OutlinedButton(onClick = remove, modifier = Modifier.pointerClick(onClick = remove)) {
                                Text(stringResource(R.string.tv_ext_remove_repo))
                            }
                            // Focus on the harmless one.
                            val cancel = { removing = null }
                            Button(onClick = cancel, modifier = Modifier.focusRequester(cancelFocus).pointerClick(onClick = cancel)) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingRepo) {
        var url by remember { mutableStateOf("") }
        TvDialog(onDismissRequest = { addingRepo = false }) {
            Surface(shape = CARD_SHAPE, modifier = Modifier.width(560.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.tv_ext_add_repo), style = MaterialTheme.typography.headlineSmall)
                    val field = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                    val submit = {
                        if (url.isNotBlank()) {
                            addingRepo = false
                            onAddRepo(url)
                        }
                    }
                    TvSearchField(
                        query = url,
                        onQuery = { url = it },
                        hint = stringResource(R.string.tv_ext_repo_hint),
                        onSubmit = submit,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(field),
                    )
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = submit, modifier = Modifier.pointerClick(onClick = submit)) {
                            Text(stringResource(R.string.tv_ext_add))
                        }
                        val cancel = { addingRepo = false }
                        OutlinedButton(onClick = cancel, modifier = Modifier.pointerClick(onClick = cancel)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp, start = 16.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun AvailableRow(
    ext: AnimeExtension.Available,
    step: InstallStep?,
    onInstall: (AnimeExtension.Available) -> Unit,
    modifier: Modifier = Modifier,
    installed: AnimeExtension.Installed? = null,
    onManage: (AnimeExtension.Installed) -> Unit = {},
) {
    val select = { if (installed != null) onManage(installed) else onInstall(ext) }
    ExtensionRow(
        name = ext.name,
        detail = "${languageName(ext.lang)} · ${ext.versionName}" + if (ext.isNsfw) " · 18+" else "",
        icon = { RemoteIcon(ext.iconUrl) },
        action = stepLabel(step) ?: stringResource(if (installed != null) R.string.tv_ext_installed_label else R.string.tv_ext_install),
        onClick = select,
        modifier = modifier,
    )
}

/** One extension: its icon, name, language and version, and what selecting it does. */
@Composable
private fun ExtensionRow(
    name: String,
    detail: String,
    icon: @Composable () -> Unit,
    action: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = { Box(Modifier.size(36.dp)) { icon() } },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1) },
        trailingContent = {
            if (action != null) Text(
                action,
                style = MaterialTheme.typography.bodyLarge,
                // The row's own content colour, which flips with focus (see TvSettings' ChoiceRow).
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.8f),
            )
        },
        modifier = modifier.pointerClick(onClick = onClick),
    )
}

@Composable
private fun InstalledIcon(ext: AnimeExtension.Installed) {
    val bitmap = remember(ext.pkgName, ext.versionCode) {
        runCatching { ext.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RemoteIcon(url: String) {
    GlideImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
}
