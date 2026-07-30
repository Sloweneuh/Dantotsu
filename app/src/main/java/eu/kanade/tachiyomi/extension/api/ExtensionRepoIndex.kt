package eu.kanade.tachiyomi.extension.api

import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.ByteString.Companion.decodeHex
import okio.GzipSource
import okio.buffer

/**
 * Repository index handling for both the legacy `index.min.json` array and the v2 index
 * (`index.pb`, a gzipped protobuf; `index.json` is its proto3-JSON twin).
 *
 * Schema mirrors Mihon's index.proto
 * (https://github.com/mihonapp/tachiyomix/blob/e4d546c/index/index.proto), including the
 * `Resources.jarUrl` (field 501) that keiyoushi's copy adds.
 */

/** Index file names a repository URL may already point at. */
private val INDEX_FILE_NAMES = listOf("index.min.json", "index.json", "index.pb")

private val GZIP_MAGIC = "1f8b".decodeHex()

// region v2 index (index.proto)

@Serializable
internal data class RepoIndex(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: RepoContact? = null,
    // oneof extensions { ExtensionList extensionList = 101; string extensionListUrl = 102; }
    @ProtoNumber(101) val extensionList: RepoExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@Serializable
internal data class RepoContact(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
internal data class RepoExtensionList(
    @ProtoNumber(1) val extensions: List<RepoExtension> = emptyList(),
)

@Serializable
internal data class RepoExtension(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: RepoResources = RepoResources(),
    @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) @Serializable(with = LenientLongSerializer::class) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.CONTENT_WARNING_UNSPECIFIED,
    @ProtoNumber(8) val sources: List<RepoSource> = emptyList(),
)

@Serializable
internal data class RepoResources(
    @ProtoNumber(1) val apkUrl: String = "",
    @ProtoNumber(2) val iconUrl: String = "",
    @ProtoNumber(501) val jarUrl: String = "",
)

@Serializable
internal data class RepoSource(
    @ProtoNumber(1) @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val language: String = "",
    @ProtoNumber(4) val homeUrl: String = "",
    @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
    @ProtoNumber(7) val message: String? = null,
)

/** Declaration order must stay aligned with the proto enum values 0..3. */
@Serializable
internal enum class ContentWarning {
    CONTENT_WARNING_UNSPECIFIED,
    CONTENT_WARNING_SAFE,
    CONTENT_WARNING_MIXED,
    CONTENT_WARNING_NSFW,
}

/**
 * proto3 JSON encodes int64 as a string, protobuf as a varint. The same models decode both,
 * so accept either representation.
 */
internal object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long = when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().jsonPrimitive.content.toLong()
        else -> decoder.decodeLong()
    }
}

// endregion

// region repo descriptor (repo.json)

@Serializable
internal data class RepoDescriptor(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: RepoMeta? = null,
)

@Serializable
internal data class RepoMeta(
    val name: String = "",
    val shortName: String? = null,
    val website: String = "",
    val signingKeyFingerprint: String = "",
)

// endregion

// region legacy index (index.min.json)

@Serializable
private data class LegacyExtensionJson(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<LegacySourceJson>? = null,
)

@Serializable
private data class LegacySourceJson(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

// endregion

/** Format-agnostic view of one repository entry, consumed by [ExtensionGithubApi]. */
internal data class RepoEntry(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double,
    val lang: String,
    val isNsfw: Boolean,
    val hasReadme: Boolean,
    val hasChangelog: Boolean,
    val sources: List<RepoEntrySource>,
    /** Legacy apk file name; blank for v2 indexes, which carry an absolute [apkUrl] instead. */
    val apkName: String,
    /** Absolute apk url; only v2 indexes provide one. */
    val apkUrl: String?,
    val iconUrl: String,
)

internal data class RepoEntrySource(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

/**
 * Fetches and decodes a repository index, transparently handling the legacy JSON array, the
 * v2 protobuf index and its JSON twin, plus gzip and the jsDelivr mirror fallback.
 */
internal class ExtensionRepoFetcher(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun fetch(repoUrl: String): List<RepoEntry> {
        val base = repoUrl.repoBaseUrl()
        val indexUrl = resolveIndexUrl(repoUrl, base)
        val bytes = getWithMirror(indexUrl, base)
            ?: error("Could not fetch index for $repoUrl")
        return decode(bytes, base)
    }

    /**
     * Repositories advertise their v2 index through `repo.json`, so a stored legacy url keeps
     * working after the repository migrates. Falls back to whatever the url already points at.
     */
    private suspend fun resolveIndexUrl(repoUrl: String, base: String): String {
        val descriptor = get("$base/repo.json")?.let { bytes ->
            runCatching { json.decodeFromString<RepoDescriptor>(bytes.decodeToString()) }
                .onFailure { Logger.log("Malformed repo.json for $base") }
                .getOrNull()
        }
        descriptor?.indexV2?.takeIf { it.isNotBlank() }?.let { return it }

        val trimmed = repoUrl.removeSuffix("/")
        return if (INDEX_FILE_NAMES.any { trimmed.endsWith("/$it") }) {
            trimmed
        } else {
            "$base/index.min.json"
        }
    }

    private suspend fun decode(bytes: ByteArray, base: String): List<RepoEntry> {
        return when (bytes.formatMarker()) {
            '[' -> json
                .decodeFromString(ListSerializer(LegacyExtensionJson.serializer()), bytes.decodeToString())
                .map { it.toRepoEntry(base) }

            '{' -> json.decodeFromString<RepoIndex>(bytes.decodeToString()).resolveExtensions()

            else -> decodeProto<RepoIndex>(bytes).resolveExtensions()
        }
    }

    /** Handles the `extensions` oneof: either an inline list or a url pointing at one. */
    private suspend fun RepoIndex.resolveExtensions(): List<RepoEntry> {
        extensionList?.let { return it.extensions.map(RepoExtension::toRepoEntry) }

        val url = extensionListUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val bytes = get(url) ?: return emptyList()
        val list = if (bytes.formatMarker() == '{') {
            json.decodeFromString<RepoExtensionList>(bytes.decodeToString())
        } else {
            decodeProto<RepoExtensionList>(bytes)
        }
        return list.extensions.map(RepoExtension::toRepoEntry)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> decodeProto(bytes: ByteArray): T =
        ProtoBuf.decodeFromByteArray<T>(bytes)

    private suspend fun getWithMirror(url: String, base: String): ByteArray? {
        get(url)?.let { return it }
        val mirror = base.jsDelivrMirror() ?: return null
        return get("$mirror/${url.substringAfterLast('/')}")
    }

    private suspend fun get(url: String): ByteArray? = try {
        client.newCall(GET(url)).awaitSuccess().use { it.readIndexBytes() }
    } catch (e: Throwable) {
        Logger.log("Failed to fetch $url")
        Logger.log(e)
        null
    }
}

/** Index payloads may be gzipped in-band (the v2 `index.pb` is), independent of Content-Encoding. */
private fun Response.readIndexBytes(): ByteArray = body.source().use { source ->
    if (source.rangeEquals(0, GZIP_MAGIC)) {
        GzipSource(source).buffer().use { it.readByteArray() }
    } else {
        source.readByteArray()
    }
}

/**
 * First meaningful byte, used to tell the formats apart. Whitespace is deliberately not skipped:
 * a protobuf payload starts with a field tag that is frequently 0x0a, i.e. '\n'.
 */
private fun ByteArray.formatMarker(): Char? {
    val start = if (size >= 3 && this[0] == 0xEF.toByte() &&
        this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()
    ) 3 else 0
    return getOrNull(start)?.toInt()?.toChar()
}

/** Strips a trailing index file name, leaving the directory the repo lives in. */
private fun String.repoBaseUrl(): String {
    val trimmed = removeSuffix("/")
    INDEX_FILE_NAMES.forEach { name ->
        if (trimmed.endsWith("/$name")) return trimmed.removeSuffix("/$name")
    }
    return trimmed
}

private fun String.jsDelivrMirror(): String? {
    val parts = removePrefix("https://").removePrefix("http://").removeSuffix("/").split("/")
    if (parts.size < 3) return null
    val branch = parts.getOrNull(3) ?: "main"
    return "https://gcore.jsdelivr.net/gh/${parts[1]}/${parts[2]}@$branch"
}

private fun LegacyExtensionJson.toRepoEntry(base: String) = RepoEntry(
    name = name,
    pkgName = pkg,
    versionName = version,
    versionCode = code,
    libVersion = version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0,
    lang = lang,
    isNsfw = nsfw == 1,
    hasReadme = hasReadme == 1,
    hasChangelog = hasChangelog == 1,
    sources = sources.orEmpty().map { RepoEntrySource(it.id, it.lang, it.name, it.baseUrl) },
    apkName = apk,
    apkUrl = null,
    iconUrl = "$base/icon/$pkg.png",
)

private fun RepoExtension.toRepoEntry() = RepoEntry(
    name = name,
    pkgName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    libVersion = extensionLib.toDoubleOrNull() ?: 0.0,
    // v2 drops the per-extension language and only tags sources.
    lang = sources.map { it.language }.distinct().singleOrNull() ?: "all",
    isNsfw = contentWarning == ContentWarning.CONTENT_WARNING_NSFW,
    hasReadme = false,
    hasChangelog = false,
    sources = sources.map { RepoEntrySource(it.id, it.language, it.name, it.homeUrl) },
    apkName = resources.apkUrl.substringAfterLast('/'),
    apkUrl = resources.apkUrl.takeIf { it.isNotBlank() },
    iconUrl = resources.iconUrl,
)
