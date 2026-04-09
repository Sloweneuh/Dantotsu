package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.CharacterSearchResults
import ani.dantotsu.connections.anilist.MUSearchResults
import ani.dantotsu.connections.anilist.StaffSearchResults
import ani.dantotsu.connections.anilist.StudioSearchResults
import ani.dantotsu.connections.anilist.UserSearchResults
import ani.dantotsu.connections.mangaupdates.MUMediaAdapter
import ani.dantotsu.databinding.ActivitySearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.UsersAdapter
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val scope = lifecycleScope
    val model: AnilistSearch by viewModels()

    var style: Int = 0
    lateinit var searchType: SearchType
    private var screenWidth: Float = 0f

    private lateinit var mediaAdaptor: MediaAdaptor
    private lateinit var characterAdaptor: CharacterAdapter
    private lateinit var studioAdaptor: StudioAdapter
    private lateinit var staffAdaptor: AuthorAdapter
    private lateinit var usersAdapter: UsersAdapter
    private lateinit var muSearchAdaptor: MUMediaAdapter

    private lateinit var progressAdapter: ProgressAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var headerAdaptor: HeaderInterface

    lateinit var aniMangaResult: AniMangaSearchResults
    lateinit var characterResult: CharacterSearchResults
    lateinit var studioResult: StudioSearchResults
    lateinit var staffResult: StaffSearchResults
    lateinit var userResult: UserSearchResults
    lateinit var muSearchResult: MUSearchResults

    lateinit var updateChips: (() -> Unit)
    var updateMuChips: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        screenWidth = resources.displayMetrics.run { widthPixels / density }

        binding.searchRecyclerView.updatePaddingRelative(
            top = statusBarHeight,
            bottom = navBarHeight + 80f.px
        )

        val notSet = model.notSet
        searchType = SearchType.fromString(intent.getStringExtra("type") ?: "ANIME")
        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                style = PrefManager.getVal(PrefName.SearchStyle)
                var listOnly: Boolean? = intent.getBooleanExtra("listOnly", false)
                if (!listOnly!!) listOnly = null

                if (model.notSet) {
                    model.notSet = false
                    model.aniMangaSearchResults = AniMangaSearchResults(
                        intent.getStringExtra("type") ?: "ANIME",
                        isAdult = if (Anilist.adult) intent.getBooleanExtra(
                            "hentai",
                            false
                        ) else false,
                        onList = listOnly,
                        search = intent.getStringExtra("query"),
                        genres = intent.getStringExtra("genre")?.let { mutableListOf(it) },
                        tags = intent.getStringExtra("tag")?.let { mutableListOf(it) },
                        sort = intent.getStringExtra("sortBy"),
                        status = intent.getStringExtra("status"),
                        source = intent.getStringExtra("source"),
                        countryOfOrigin = intent.getStringExtra("country"),
                        season = intent.getStringExtra("season"),
                        seasonYear = if (intent.getStringExtra("type") == "ANIME") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        startYear = if (intent.getStringExtra("type") == "MANGA") intent.getStringExtra(
                            "seasonYear"
                        )
                            ?.toIntOrNull() else null,
                        results = mutableListOf(),
                        hasNextPage = false
                    )
                }

                aniMangaResult = model.aniMangaSearchResults
                mediaAdaptor =
                    MediaAdaptor(
                        style,
                        model.aniMangaSearchResults.results,
                        this,
                        matchParent = true
                    )
            }

            SearchType.CHARACTER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.characterSearchResults = CharacterSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )

                    characterResult = model.characterSearchResults
                    characterAdaptor = CharacterAdapter(model.characterSearchResults.results)
                }
            }

            SearchType.STUDIO -> {
                if (model.notSet) {
                    model.notSet = false
                    model.studioSearchResults = StudioSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )

                    studioResult = model.studioSearchResults
                    studioAdaptor = StudioAdapter(model.studioSearchResults.results)
                }
            }

            SearchType.STAFF -> {
                if (model.notSet) {
                    model.notSet = false
                    model.staffSearchResults = StaffSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )

                    staffResult = model.staffSearchResults
                    staffAdaptor = AuthorAdapter(model.staffSearchResults.results)
                }
            }

            SearchType.USER -> {
                if (model.notSet) {
                    model.notSet = false
                    model.userSearchResults = UserSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false
                    )

                    userResult = model.userSearchResults
                    usersAdapter = UsersAdapter(model.userSearchResults.results, grid = true)
                }
            }

            SearchType.MANGAUPDATES -> {
                if (model.notSet) {
                    model.notSet = false
                    val muGenres = intent.getStringArrayListExtra("genres")
                        ?.toMutableList()
                        ?: intent.getStringExtra("genre")?.let { mutableListOf(it) }
                    val muCategories = intent.getStringArrayListExtra("categories")
                        ?.toMutableList()
                        ?: intent.getStringExtra("category")?.let { mutableListOf(it) }
                    model.muSearchResults = MUSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false,
                        genres = muGenres,
                        categories = muCategories
                    )
                }
                muSearchResult = model.muSearchResults
                muSearchAdaptor = MUMediaAdapter(model.muSearchResults.results)
            }
        }

        progressAdapter = ProgressAdapter(searched = model.searched)
        headerAdaptor = if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            SearchAdapter(this, searchType)
        } else {
            SupportingSearchAdapter(this, searchType)
        }

        val gridSize = (screenWidth / 120f).toInt()
        val gridLayoutManager = GridLayoutManager(this, gridSize)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (position) {
                    0 -> gridSize
                    concatAdapter.itemCount - 1 -> gridSize
                    else -> when (style) {
                        0 -> 1
                        else -> gridSize
                    }
                }
            }
        }

        concatAdapter = when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                ConcatAdapter(headerAdaptor, mediaAdaptor, progressAdapter)
            }

            SearchType.CHARACTER -> {
                ConcatAdapter(headerAdaptor, characterAdaptor, progressAdapter)
            }

            SearchType.STUDIO -> {
                ConcatAdapter(headerAdaptor, studioAdaptor, progressAdapter)
            }

            SearchType.STAFF -> {
                ConcatAdapter(headerAdaptor, staffAdaptor, progressAdapter)
            }

            SearchType.USER -> {
                ConcatAdapter(headerAdaptor, usersAdapter, progressAdapter)
            }

            SearchType.MANGAUPDATES -> {
                ConcatAdapter(headerAdaptor, muSearchAdaptor, progressAdapter)
            }
        }

        binding.searchRecyclerView.layoutManager = gridLayoutManager
        binding.searchRecyclerView.adapter = concatAdapter

        binding.searchRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (!v.canScrollVertically(1)) {
                    if (model.hasNextPage(searchType) && model.resultsIsNotEmpty(searchType) && !loading) {
                        scope.launch(Dispatchers.IO) {
                            loading = true
                            model.loadNextPage(searchType)
                            loading = false
                        }
                    }
                }
                super.onScrolled(v, dx, dy)
            }
        })

        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                model.getSearch<AniMangaSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.aniMangaSearchResults.apply {
                            onList = it.onList
                            isAdult = it.isAdult
                            perPage = it.perPage
                            search = it.search
                            sort = it.sort
                            genres = it.genres
                            excludedGenres = it.excludedGenres
                            excludedTags = it.excludedTags
                            tags = it.tags
                            season = it.season
                            startYear = it.startYear
                            seasonYear = it.seasonYear
                            yearRangeStart = it.yearRangeStart
                            yearRangeEnd = it.yearRangeEnd
                            status = it.status
                            source = it.source
                            format = it.format
                            countryOfOrigin = it.countryOfOrigin
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.aniMangaSearchResults.results.size
                        model.aniMangaSearchResults.results.addAll(it.results)
                        mediaAdaptor.notifyItemRangeInserted(prev, it.results.size)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                    }
                }
            }

            SearchType.CHARACTER -> {
                model.getSearch<CharacterSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.characterSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.characterSearchResults.results.size
                        model.characterSearchResults.results.addAll(it.results)
                        characterAdaptor.notifyItemRangeInserted(prev, it.results.size)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                    }
                }
            }

            SearchType.STUDIO -> {
                model.getSearch<StudioSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.studioSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.studioSearchResults.results.size
                        model.studioSearchResults.results.addAll(it.results)
                        studioAdaptor.notifyItemRangeInserted(prev, it.results.size)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                    }
                }
            }

            SearchType.STAFF -> {
                model.getSearch<StaffSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.staffSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.staffSearchResults.results.size
                        model.staffSearchResults.results.addAll(it.results)
                        staffAdaptor.notifyItemRangeInserted(prev, it.results.size)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                    }
                }
            }

            SearchType.USER -> {
                model.getSearch<UserSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.userSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                        }

                        val prev = model.userSearchResults.results.size
                        model.userSearchResults.results.addAll(it.results)
                        usersAdapter.notifyItemRangeInserted(prev, it.results.size)

                        progressAdapter.bar?.isVisible = it.hasNextPage
                    }
                }
            }

            SearchType.MANGAUPDATES -> {
                model.getSearch<MUSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.muSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                            format = it.format
                            year = it.year
                            genres = it.genres
                            excludedGenres = it.excludedGenres
                            categories = it.categories
                            excludedCategories = it.excludedCategories
                            licensed = it.licensed
                            statusFilters = it.statusFilters
                            orderBy = it.orderBy
                        }
                        val prev = model.muSearchResults.results.size
                        model.muSearchResults.results.addAll(it.results)
                        muSearchAdaptor.notifyItemRangeInserted(prev, it.results.size)
                        progressAdapter.bar?.isVisible = it.hasNextPage
                    } else {
                        progressAdapter.bar?.isVisible = false
                    }
                }
            }
        }

        progressAdapter.ready.observe(this) {
            if (it == true) {
                if (!notSet) {
                    if (!model.searched) {
                        model.searched = true
                        headerAdaptor.search?.run()
                    }
                } else
                    headerAdaptor.requestFocus?.run()

                if (intent.getBooleanExtra("search", false)) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                    search()
                }
            }
        }
    }

    fun emptyMediaAdapter() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { emptyMediaAdapter() }
            return
        }

        searchTimer.cancel()
        searchTimer.purge()
        clearResultsOnMainThread()
        progressAdapter.bar?.visibility = View.GONE
    }

    private fun clearResultsOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { clearResultsOnMainThread() }
            return
        }

        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                model.aniMangaSearchResults.results.clear()
                mediaAdaptor.notifyDataSetChanged()
            }

            SearchType.CHARACTER -> {
                model.characterSearchResults.results.clear()
                characterAdaptor.notifyDataSetChanged()
            }

            SearchType.STUDIO -> {
                model.studioSearchResults.results.clear()
                studioAdaptor.notifyDataSetChanged()
            }

            SearchType.STAFF -> {
                model.staffSearchResults.results.clear()
                staffAdaptor.notifyDataSetChanged()
            }

            SearchType.USER -> {
                model.userSearchResults.results.clear()
                usersAdapter.notifyDataSetChanged()
            }

            SearchType.MANGAUPDATES -> {
                model.muSearchResults.results.clear()
                muSearchAdaptor.notifyDataSetChanged()
            }
        }
    }

    private var searchTimer = Timer()
    private var loading = false
    fun search() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { search() }
            return
        }

        headerAdaptor.setHistoryVisibility(false)
        resetSearchState()
        clearResultsOnMainThread()

        progressAdapter.bar?.visibility = View.VISIBLE

        searchTimer.cancel()
        searchTimer.purge()
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                scope.launch(Dispatchers.IO) {
                    loading = true
                    model.loadSearch(searchType)
                    loading = false
                }
            }
        }
        searchTimer = Timer()
        searchTimer.schedule(timerTask, 500)
    }

    private fun resetSearchState() {
        when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                aniMangaResult.page = 1
                aniMangaResult.hasNextPage = false
            }

            SearchType.CHARACTER -> {
                characterResult.page = 1
                characterResult.hasNextPage = false
            }

            SearchType.STUDIO -> {
                studioResult.page = 1
                studioResult.hasNextPage = false
            }

            SearchType.STAFF -> {
                staffResult.page = 1
                staffResult.hasNextPage = false
            }

            SearchType.USER -> {
                userResult.page = 1
                userResult.hasNextPage = false
            }

            SearchType.MANGAUPDATES -> {
                muSearchResult.page = 1
                muSearchResult.hasNextPage = false
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun recycler() {
        if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            mediaAdaptor.type = style
            mediaAdaptor.notifyDataSetChanged()
        }
    }

    var state: Parcelable? = null
    override fun onPause() {
        if (this::headerAdaptor.isInitialized) {
            headerAdaptor.addHistory()
        }
        super.onPause()
        state = binding.searchRecyclerView.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        binding.searchRecyclerView.layoutManager?.onRestoreInstanceState(state)
    }
    
    // Return the current textual content of the header's search bar, or null if blank.
    fun getHeaderSearchText(): String? {
        return if (this::headerAdaptor.isInitialized) headerAdaptor.getSearchText() else null
    }

}