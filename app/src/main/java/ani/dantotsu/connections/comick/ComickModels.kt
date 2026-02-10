package ani.dantotsu.connections.comick

import java.io.Serializable

data class ComickResponse(
    val comic: ComickComic?,
    val firstChap: ComickFirstChapter?
) : Serializable

data class ComickComic(
    val id: Int?,
    val title: String?,
    val desc: String?,
    val parsed: String?,
    val slug: String?,
    val country: String?,
    val status: Int?,
    val year: Int?,
    val bayesian_rating: String?,
    val rating_count: Int?,
    val follow_rank: Int?,
    val user_follow_count: Int?,
    val last_chapter: Double?,
    val chapter_count: Int?,
    val demographic: Int?,
    val final_chapter: String?,
    val final_volume: String?,
    val has_anime: Boolean?,
    val anime: ComickAnimeInfo?,
    val mu_comics: ComickMuComics?,
    val translation_completed: Boolean?,
    val content_rating: String?,
    val md_titles: List<ComickAlternativeTitle>?,
    val md_comic_md_genres: List<ComickGenre>?,
    val md_covers: List<ComickCover>?,
    val links: ComickLinks?,
    val recommendations: List<ComickRecommendation>?
) : Serializable

data class ComickFirstChapter(
    val chap: String?,
    val hid: String?,
    val lang: String?,
    val vol: String?
) : Serializable

data class ComickAnimeInfo(
    val start: String?,
    val end: String?
) : Serializable

data class ComickMuComics(
    val mu_comic_categories: List<ComickCategory>?
) : Serializable

data class ComickCategory(
    val mu_categories: ComickCategoryInfo?,
    val positive_vote: Int?,
    val negative_vote: Int?
) : Serializable

data class ComickCategoryInfo(
    val title: String?,
    val slug: String?
) : Serializable

data class ComickAlternativeTitle(
    val title: String?,
    val lang: String?
) : Serializable

data class ComickGenre(
    val md_genres: ComickGenreInfo?
) : Serializable

data class ComickGenreInfo(
    val name: String?,
    val type: String?,
    val slug: String?,
    val group: String?
) : Serializable

data class ComickLinks(
    val al: String?,
    val ap: String?,
    val bw: String?,
    val kt: String?,
    val mu: String?,
    val mal: String?,
    val raw: String?,
    val engtl: String?
) : Serializable

data class ComickSearchResult(
    val id: Int?,
    val slug: String?,
    val title: String?,
    val country: String?,
    val rating: String?,
    val bayesian_rating: String?,
    val status: Int?,
    val last_chapter: Double?,
    val demographic: Int?,
    val year: Int?
) : Serializable

data class ComickRecommendation(
    val up: Int?,
    val down: Int?,
    val total: Int?,
    val relates: ComickRecommendedComic?
) : Serializable

data class ComickRecommendedComic(
    val title: String?,
    val slug: String?,
    val hid: String?,
    val md_covers: List<ComickCover>?
) : Serializable

data class ComickCover(
    val vol: String?,
    val w: Int?,
    val h: Int?,
    val b2key: String?
) : Serializable

