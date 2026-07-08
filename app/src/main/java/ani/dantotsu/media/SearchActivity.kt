package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
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
import ani.dantotsu.connections.anilist.ComickSearchResults
import ani.dantotsu.connections.anilist.MUSearchResults
import ani.dantotsu.connections.anilist.MangaBakaSearchResults
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.connections.anilist.StaffSearchResults
import ani.dantotsu.connections.anilist.StudioSearchResults
import ani.dantotsu.connections.anilist.UserSearchResults
import android.content.Intent
import ani.dantotsu.connections.mangaupdates.MUMediaAdapter
import ani.dantotsu.databinding.ActivitySearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
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
    var supportStyle: Int = 0
    lateinit var searchType: SearchType
    private var screenWidth: Float = 0f

    private lateinit var mediaAdaptor: MediaAdaptor
    private lateinit var characterAdaptor: CharacterAdapter
    private lateinit var studioAdaptor: StudioAdapter
    private lateinit var staffAdaptor: AuthorAdapter
    private lateinit var usersAdapter: UsersAdapter
    private lateinit var muSearchAdaptor: MUMediaAdapter
    private lateinit var comickSearchAdaptor: ComickSearchAdapter
    private lateinit var mangaBakaSearchAdaptor: MangaBakaSearchAdapter

    private lateinit var progressAdapter: ProgressAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var headerAdaptor: HeaderInterface

    lateinit var aniMangaResult: AniMangaSearchResults
    lateinit var characterResult: CharacterSearchResults
    lateinit var studioResult: StudioSearchResults
    lateinit var staffResult: StaffSearchResults
    lateinit var userResult: UserSearchResults
    lateinit var muSearchResult: MUSearchResults
    lateinit var comickSearchResult: ComickSearchResults
    lateinit var mangaBakaSearchResult: MangaBakaSearchResults

    lateinit var updateChips: (() -> Unit)
    var updateMuChips: (() -> Unit)? = null
    var updateComickChips: (() -> Unit)? = null
    var updateMangaBakaChips: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        screenWidth = resources.displayMetrics.run { widthPixels / density }

        binding.searchRecyclerView.updatePaddingRelative(
            bottom = navBarHeight + 80f.px
        )
        (binding.searchHeader.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = statusBarHeight
            binding.searchHeader.root.layoutParams = it
        }

        val notSet = model.notSet
        searchType = SearchType.fromString(intent.getStringExtra("type") ?: "ANIME")
        supportStyle = PrefManager.getVal(PrefName.SearchStyleSupporting)

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
                muSearchAdaptor = MUMediaAdapter(model.muSearchResults.results, type = supportStyle)
            }

            SearchType.COMICK -> {
                if (model.notSet) {
                    model.notSet = false
                    val genres = intent.getStringArrayListExtra("genres")
                        ?.toMutableList()
                        ?: intent.getStringExtra("genre")?.let { mutableListOf(it) }
                    val categories = intent.getStringArrayListExtra("categories")
                        ?.toMutableList()
                        ?: intent.getStringExtra("category")?.let { mutableListOf(it) }
                    model.comickSearchResults = ComickSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false,
                        genres = genres,
                        excludedGenres = intent.getStringArrayListExtra("excludedGenres")?.toMutableList(),
                        tags = intent.getStringArrayListExtra("tags")?.toMutableList(),
                        excludedTags = intent.getStringArrayListExtra("excludedTags")?.toMutableList(),
                        demographic = intent.getIntegerArrayListExtra("demographic")?.toMutableList(),
                        country = intent.getStringArrayListExtra("country")?.toMutableList(),
                        contentRating = intent.getStringArrayListExtra("contentRating")?.toMutableList(),
                        status = intent.getIntExtra("status", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        sort = intent.getStringExtra("sort"),
                        time = intent.getIntExtra("time", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        minimum = intent.getIntExtra("minimum", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        minimumRating = intent.getDoubleExtra("minimumRating", Double.NaN).takeIf { !it.isNaN() },
                        fromYear = intent.getIntExtra("fromYear", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        toYear = intent.getIntExtra("toYear", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        completed = if (intent.hasExtra("completed")) intent.getBooleanExtra("completed", false) else null,
                        excludeMyList = null,
                        showAll = if (intent.hasExtra("showAll")) intent.getBooleanExtra("showAll", false) else null,
                        categories = categories,
                    )
                    // Seed the genre/category display-name cache so chip labels are correct
                    // even when the filter bottom sheet has never been opened.
                    intent.getStringExtra("genre")?.let { slug ->
                        intent.getStringExtra("genreName")?.let { name ->
                            ComickApi.seedGenreCache(slug, name)
                        }
                    }
                    intent.getStringExtra("category")?.let { slug ->
                        intent.getStringExtra("categoryName")?.let { name ->
                            ComickApi.seedCategoryCache(slug, name)
                        }
                    }
                }
                comickSearchResult = model.comickSearchResults
                comickSearchAdaptor = ComickSearchAdapter(model.comickSearchResults.results, supportStyle) { comic ->
                    onComickResultClicked(comic)
                }
            }

            SearchType.MANGABAKA -> {
                if (model.notSet) {
                    model.notSet = false
                    val genres = intent.getStringArrayListExtra("genres")
                        ?.toMutableList()
                        ?: intent.getStringExtra("genre")?.let { mutableListOf(it) }
                    val tags = intent.getStringArrayListExtra("tags")
                        ?.toMutableList()
                        ?: intent.getStringExtra("tag")?.let { mutableListOf(it) }
                    model.mangaBakaSearchResults = MangaBakaSearchResults(
                        search = intent.getStringExtra("query"),
                        results = mutableListOf(),
                        hasNextPage = false,
                        genres = genres,
                        excludedGenres = intent.getStringArrayListExtra("excludedGenres")?.toMutableList(),
                        tags = tags,
                        excludedTags = intent.getStringArrayListExtra("excludedTags")?.toMutableList(),
                        types = intent.getStringArrayListExtra("types")?.toMutableList(),
                        statuses = intent.getStringArrayListExtra("statuses")?.toMutableList(),
                        contentRatings = intent.getStringArrayListExtra("contentRatings")?.toMutableList(),
                        fromYear = intent.getIntExtra("fromYear", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        toYear = intent.getIntExtra("toYear", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                        sort = intent.getStringExtra("sort"),
                    )
                    // Seed the genre display-name cache so chip labels are correct even before the
                    // filter sheet (which fetches the full genre list) has been opened.
                    intent.getStringExtra("genre")?.let { slug ->
                        intent.getStringExtra("genreName")?.let { name ->
                            MangaBakaApi.seedGenreName(slug, name)
                        }
                    }
                }
                mangaBakaSearchResult = model.mangaBakaSearchResults
                mangaBakaSearchAdaptor = MangaBakaSearchAdapter(model.mangaBakaSearchResults.results, supportStyle) { series ->
                    onMangaBakaResultClicked(series)
                }
            }
        }

        progressAdapter = ProgressAdapter(searched = model.searched)
        headerAdaptor = if (searchType == SearchType.ANIME || searchType == SearchType.MANGA) {
            SearchAdapter(this, searchType)
        } else {
            SupportingSearchAdapter(this, searchType)
        }
        headerAdaptor.attach(binding.searchHeader)

        val gridSize = (screenWidth / 120f).toInt()
        val gridLayoutManager = GridLayoutManager(this, gridSize)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (position) {
                    concatAdapter.itemCount - 1 -> gridSize
                    else -> {
                        val currentStyle = when (searchType) {
                            SearchType.MANGAUPDATES, SearchType.COMICK, SearchType.MANGABAKA -> supportStyle
                            SearchType.ANIME, SearchType.MANGA -> style
                            else -> 0
                        }
                        when (currentStyle) {
                            0 -> 1
                            else -> gridSize
                        }
                    }
                }
            }
        }

        concatAdapter = when (searchType) {
            SearchType.ANIME, SearchType.MANGA -> {
                ConcatAdapter(mediaAdaptor, progressAdapter)
            }

            SearchType.CHARACTER -> {
                ConcatAdapter(characterAdaptor, progressAdapter)
            }

            SearchType.STUDIO -> {
                ConcatAdapter(studioAdaptor, progressAdapter)
            }

            SearchType.STAFF -> {
                ConcatAdapter(staffAdaptor, progressAdapter)
            }

            SearchType.USER -> {
                ConcatAdapter(usersAdapter, progressAdapter)
            }

            SearchType.MANGAUPDATES -> {
                ConcatAdapter(muSearchAdaptor, progressAdapter)
            }

            SearchType.COMICK -> {
                ConcatAdapter(comickSearchAdaptor, progressAdapter)
            }

            SearchType.MANGABAKA -> {
                ConcatAdapter(mangaBakaSearchAdaptor, progressAdapter)
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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)

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
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)
                        progressAdapter.bar?.isVisible = it.hasNextPage
                    } else {
                        progressAdapter.bar?.isVisible = false
                    }
                }
            }

            SearchType.COMICK -> {
                model.getSearch<ComickSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.comickSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                            genres = it.genres
                            categories = it.categories
                        }

                        val prev = model.comickSearchResults.results.size
                        model.comickSearchResults.results.addAll(it.results)
                        comickSearchAdaptor.notifyItemRangeInserted(prev, it.results.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)
                        progressAdapter.bar?.isVisible = it.hasNextPage
                    } else {
                        progressAdapter.bar?.isVisible = false
                    }
                }
            }

            SearchType.MANGABAKA -> {
                model.getSearch<MangaBakaSearchResults>(searchType).observe(this) {
                    if (it != null) {
                        model.mangaBakaSearchResults.apply {
                            search = it.search
                            page = it.page
                            hasNextPage = it.hasNextPage
                            genres = it.genres
                            excludedGenres = it.excludedGenres
                            tags = it.tags
                            excludedTags = it.excludedTags
                            types = it.types
                            excludedTypes = it.excludedTypes
                            statuses = it.statuses
                            excludedStatuses = it.excludedStatuses
                            contentRatings = it.contentRatings
                            excludedContentRatings = it.excludedContentRatings
                            fromYear = it.fromYear
                            toYear = it.toYear
                            sort = it.sort
                        }

                        val prev = model.mangaBakaSearchResults.results.size
                        model.mangaBakaSearchResults.results.addAll(it.results)
                        mangaBakaSearchAdaptor.notifyItemRangeInserted(prev, it.results.size)
                        if (prev == 0) binding.searchRecyclerView.scrollToPosition(0)
                        progressAdapter.bar?.isVisible = it.hasNextPage
                    } else {
                        progressAdapter.bar?.isVisible = false
                    }
                }
            }
        }

        headerAdaptor.ready.observe(this) {
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

            SearchType.COMICK -> {
                model.comickSearchResults.results.clear()
                comickSearchAdaptor.notifyDataSetChanged()
            }

            SearchType.MANGABAKA -> {
                model.mangaBakaSearchResults.results.clear()
                mangaBakaSearchAdaptor.notifyDataSetChanged()
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

            SearchType.COMICK -> {
                comickSearchResult.page = 1
                comickSearchResult.hasNextPage = false
            }

            SearchType.MANGABAKA -> {
                mangaBakaSearchResult.page = 1
                mangaBakaSearchResult.hasNextPage = false
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

    @SuppressLint("NotifyDataSetChanged")
    fun recyclerSupporting() {
        when (searchType) {
            SearchType.MANGAUPDATES -> {
                muSearchAdaptor.type = supportStyle
                muSearchAdaptor.notifyDataSetChanged()
            }
            SearchType.COMICK -> {
                comickSearchAdaptor.type = supportStyle
                comickSearchAdaptor.notifyDataSetChanged()
            }
            SearchType.MANGABAKA -> {
                mangaBakaSearchAdaptor.type = supportStyle
                mangaBakaSearchAdaptor.notifyDataSetChanged()
            }
            else -> Unit
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

    private fun onComickResultClicked(comic: ComickComic) {
        val slug = comic.slug ?: return
        startActivity(
            Intent(this, ComickMediaActivity::class.java)
                .putExtra(ComickMediaActivity.EXTRA_SLUG, slug)
        )
    }

    private fun onMangaBakaResultClicked(series: MangaBakaApi.Series) {
        startActivity(
            Intent(this, MangaBakaMediaActivity::class.java)
                .putExtra(MangaBakaMediaActivity.EXTRA_SERIES_ID, series.id)
        )
    }

}