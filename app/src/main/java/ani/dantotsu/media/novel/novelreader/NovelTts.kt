package ani.dantotsu.media.novel.novelreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.parsers.novel.lnreader.LNReaderBook
import ani.dantotsu.parsers.novel.lnreader.LNReaderReadState
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** What the reader and the notification both draw from. */
data class NovelTtsState(
    /** Whether speech is set up at all — the control bar and the notification exist only while true. */
    val active: Boolean = false,
    val playing: Boolean = false,
    /** Between chapters, or waiting on the engine: controls stay put but nothing is being said. */
    val preparing: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val sentence: String = "",
    val chapterTitle: String = "",
    val novelTitle: String = "",
    /** When the sleep timer will pause playback, as elapsed-realtime millis; 0 when it is off. */
    val sleepAt: Long = 0L,
) {
    val progress: Int get() = if (total <= 0) 0 else ((index + 1) * 100 / total)
}

/**
 * Reading a novel out loud.
 *
 * An object rather than something owned by the reader activity, because listening outlives looking:
 * the screen goes off, the user switches apps, and the chapter still has to finish and the next one
 * has to start. [NovelTtsService] keeps the process alive and puts the controls in the shade; this
 * holds the engine and the position, and is the only thing that knows how to say a sentence.
 *
 * The text comes from [NovelTtsText], which reads the same EPUB the reader was handed, so what is
 * spoken and what is on the page cannot drift apart. Where the two meet is a character offset:
 * [followAlong] turns the spoken position into the fraction the reader's `gotoFraction` takes, and
 * starting playback turns the reader's fraction back into a sentence.
 */
object NovelTts {

    private val _state = MutableStateFlow(NovelTtsState())
    val state: StateFlow<NovelTtsState> = _state.asStateFlow()

    /** How many sentences are handed to the engine ahead of the one being spoken. */
    private const val LOOKAHEAD = 2

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var appContext: Context? = null

    private var script: TtsScript = TtsScript.EMPTY

    /**
     * Which book the current script came from.
     *
     * Speech and the reader can each move to a new chapter, and both end with a book being opened.
     * Comparing this against the book that just loaded is what tells the difference between "the
     * reader followed speech" — nothing to do — and "the user changed chapter" — reload the script.
     */
    private var bookKey: String = ""

    /** The book as the reader was handed it, so the notification can reopen exactly that. */
    private var bookUri: Uri? = null
    private var locale: Locale = Locale.getDefault()

    private var index = 0
    private var queuedTo = -1

    /** What the user last asked for, which survives the pauses the system imposes. */
    private var wantPlaying = false

    /** True while a chapter is being fetched, so nothing else tries to start one. */
    private var advancing = false

    /** Set when audio focus was lost by something else, so playback resumes when it comes back. */
    private var pausedByFocus = false

    private var sleepTimer: Runnable? = null

    /**
     * Where the reader should move to keep up, if it is on screen.
     *
     * Set by the reader activity while it is alive and cleared when it is not, which is also how
     * this knows whether a chapter change should be handed to the reader or done in the background.
     */
    var onFollow: ((fraction: Double) -> Unit)? = null

    /** Asks the reader to open a chapter, so the page and the voice stay on the same one. */
    var onRequestChapter: ((index: Int) -> Unit)? = null

    /**
     * Which sentence to light up on the page, by the number it is tagged with in the document.
     *
     * Null clears the highlight — nothing is being read, or what is being read is a book with no
     * tagged sentences to point at.
     */
    var onHighlight: ((marker: Int?) -> Unit)? = null

    val isActive: Boolean get() = _state.value.active

    // region Public control

    /**
     * Starts speaking [script], from wherever the reader had got to.
     *
     * @param fraction the reader's own progress, mapped to the nearest sentence — starting at the
     *   top of a chapter the user is halfway through would be the wrong kind of literal.
     */
    fun start(
        context: Context,
        script: TtsScript,
        bookKey: String,
        bookUri: Uri?,
        locale: Locale,
        fraction: Double,
        chapterTitle: String,
        novelTitle: String,
    ) {
        appContext = context.applicationContext
        if (script.isEmpty) {
            Logger.log("Novel TTS: nothing to read in this book")
            return
        }
        this.script = script
        this.bookKey = bookKey
        this.bookUri = bookUri
        this.locale = locale
        index = script.indexAtFraction(fraction)
        queuedTo = index - 1
        lastFollowedFraction = -1.0
        _state.update {
            it.copy(
                active = true,
                preparing = true,
                index = index,
                total = script.size,
                sentence = script.getOrNull(index)?.text.orEmpty(),
                chapterTitle = chapterTitle,
                novelTitle = novelTitle,
            )
        }
        NovelTtsService.start(context)
        withEngine { play() }
    }

    fun play() {
        if (!isActive) return
        wantPlaying = true
        pausedByFocus = false
        if (!requestFocus()) {
            Logger.log("Novel TTS: another app is holding audio focus")
            return
        }
        withEngine {
            queuedTo = index - 1
            _state.update { it.copy(playing = true, preparing = false) }
            enqueueAhead()
        }
    }

    fun pause() {
        wantPlaying = false
        pausedByFocus = false
        tts?.stop()
        queuedTo = index - 1
        abandonFocus()
        _state.update { it.copy(playing = false) }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun next() = seekTo(index + 1)

    fun previous() = seekTo(index - 1)

    /** Moves within the chapter; running off either end is a chapter change, not a no-op. */
    fun seekTo(target: Int) {
        if (!isActive) return
        if (target >= script.size) {
            finishChapter()
            return
        }
        if (target < 0) {
            if (LNReaderSession.isActive && LNReaderSession.hasPrevious()) {
                changeChapter(LNReaderSession.currentIndex - 1)
            }
            return
        }
        index = target
        queuedTo = index - 1
        publishSentence()
        tts?.stop()
        if (wantPlaying) withEngine { enqueueAhead() } else followAlong()
    }

    /**
     * Moves the voice to wherever the reader's seek bar was dragged to.
     *
     * The bar measures the book and this measures sentences, so the two are matched through the
     * character offset that [TtsScript] keeps — the same conversion [followAlong] does in reverse.
     */
    fun seekToFraction(fraction: Double) {
        if (!isActive || script.isEmpty) return
        // Otherwise the page move that follows would be read as the voice arriving there already,
        // and the seek would be dropped as a position it is at.
        lastFollowedFraction = -1.0
        seekTo(script.indexAtFraction(fraction))
    }

    /** Stops entirely: the controls go away and the engine is handed back. */
    fun stop() {
        wantPlaying = false
        pausedByFocus = false
        cancelSleepTimer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        script = TtsScript.EMPTY
        bookKey = ""
        index = 0
        queuedTo = -1
        abandonFocus()
        // Nothing is being read, so nothing on the page should still look like it is.
        onHighlight?.invoke(null)
        _state.value = NovelTtsState()
        appContext?.let { NovelTtsService.stop(it) }
    }

    /**
     * Takes on the text of a book that has just been opened.
     *
     * Called for every book the reader loads. One of those is the chapter speech asked for, and
     * re-reading it from the top would undo the advance; every other one is the user moving
     * somewhere else, which speech should follow rather than fight.
     */
    fun adopt(
        context: Context,
        key: String,
        bookUri: Uri?,
        chapterTitle: String,
        script: TtsScript,
        locale: Locale,
    ) {
        if (!isActive) return
        appContext = context.applicationContext
        if (key == bookKey) {
            // Already reading this one — the reader has simply caught up.
            _state.update { it.copy(chapterTitle = chapterTitle) }
            return
        }
        this.script = script
        this.bookKey = key
        this.bookUri = bookUri
        this.locale = locale
        index = 0
        queuedTo = -1
        advancing = false
        lastFollowedFraction = -1.0
        _state.update {
            it.copy(
                preparing = false,
                index = 0,
                total = script.size,
                sentence = script.getOrNull(0)?.text.orEmpty(),
                chapterTitle = chapterTitle,
            )
        }
        if (script.isEmpty) {
            Logger.log("Novel TTS: the chapter that just opened has no text")
            pause()
            return
        }
        if (wantPlaying) withEngine { enqueueAhead() }
    }

    /** Re-reads speed and pitch, and says the current sentence again in the new voice. */
    fun applySettings() {
        val engine = tts ?: return
        engine.setSpeechRate(speed())
        engine.setPitch(pitch())
        applyVoice(engine)
        if (_state.value.playing) {
            engine.stop()
            queuedTo = index - 1
            enqueueAhead()
        }
    }

    /**
     * Throws away the current engine so the next play builds one from the chosen package.
     *
     * An engine's identity is fixed at construction, so switching to a different one is a matter of
     * replacing the instance rather than configuring it.
     */
    fun restartEngine() {
        val wasPlaying = _state.value.playing
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        queuedTo = index - 1
        if (wasPlaying && isActive) withEngine { enqueueAhead() }
    }

    /** The language the open book is in, which is the one voices are offered for. */
    fun currentLanguage(): Locale = locale

    /** Pauses playback in [minutes]; zero cancels a pending timer. */
    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) {
            _state.update { it.copy(sleepAt = 0L) }
            return
        }
        val delay = minutes * 60_000L
        val task = Runnable {
            sleepTimer = null
            _state.update { it.copy(sleepAt = 0L) }
            pause()
        }
        sleepTimer = task
        main.postDelayed(task, delay)
        _state.update { it.copy(sleepAt = android.os.SystemClock.elapsedRealtime() + delay) }
    }

    private fun cancelSleepTimer() {
        sleepTimer?.let { main.removeCallbacks(it) }
        sleepTimer = null
    }

    // endregion Public control

    // region Engine

    /**
     * Runs [action] once there is a working engine.
     *
     * Building one is asynchronous and can fail — no engine installed, or no data for the language
     * — and every control here has to cope with being pressed before it is ready.
     */
    private fun withEngine(action: () -> Unit) {
        val existing = tts
        if (existing != null && ready) {
            action()
            return
        }
        if (existing != null) return // still starting up; its init callback will carry on
        val context = appContext ?: return
        _state.update { it.copy(preparing = true) }

        // The init callback can only be given at construction, and it fires before the assignment
        // below on some engines, so it reads the instance out of `pending` rather than out of [tts].
        val preferred = PrefManager.getVal<String>(PrefName.NovelTtsEngine).takeIf { it.isNotBlank() }
        lateinit var pending: TextToSpeech
        val onInit = TextToSpeech.OnInitListener { status ->
            main.post { onEngineInit(pending, status, action) }
        }
        pending = if (preferred != null) TextToSpeech(context, onInit, preferred)
        else TextToSpeech(context, onInit)
        tts = pending
    }

    private fun onEngineInit(engine: TextToSpeech, status: Int, action: () -> Unit) {
        if (status != TextToSpeech.SUCCESS) {
            Logger.log("Novel TTS: no speech engine available (status $status)")
            ready = false
            engine.shutdown()
            if (tts === engine) tts = null
            _state.update { it.copy(preparing = false, playing = false) }
            return
        }
        // A stop() during start-up leaves this engine orphaned; hand it back rather than speak.
        if (tts !== engine) {
            engine.shutdown()
            return
        }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Not fatal: the engine falls back to its own default, which reads the words out in the
            // wrong accent rather than not at all. Better than refusing to start.
            Logger.log("Novel TTS: no voice data for $locale, using the engine's default")
            engine.setLanguage(Locale.getDefault())
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(speed())
        engine.setPitch(pitch())
        engine.setOnUtteranceProgressListener(progressListener)
        applyVoice(engine)
        ready = true
        _state.update { it.copy(preparing = false) }
        action()
    }

    private fun applyVoice(engine: TextToSpeech) {
        val wanted = PrefManager.getVal<String>(PrefName.NovelTtsVoice).takeIf { it.isNotBlank() }
            ?: return
        runCatching { engine.voices }.getOrNull()
            ?.firstOrNull { it.name == wanted }
            ?.let { engine.voice = it }
    }

    private fun speed() = PrefManager.getVal<Float>(PrefName.NovelTtsSpeed).coerceIn(0.25f, 4f)

    private fun pitch() = PrefManager.getVal<Float>(PrefName.NovelTtsPitch).coerceIn(0.5f, 2f)

    /** The voices this device offers, for the settings sheet to choose between. */
    fun voices(): List<Voice> = runCatching { tts?.voices?.toList() }.getOrNull().orEmpty()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val spoken = utteranceId?.toIntOrNull() ?: return
            main.post {
                index = spoken
                publishSentence()
                // Before moving the page: the highlight is what makes the move legible, and a page
                // that turns before the new sentence is marked shows the old one lit up for an
                // instant on a page it is no longer on.
                onHighlight?.invoke(script.getOrNull(index)?.marker)
                followAlong()
            }
        }

        override fun onDone(utteranceId: String?) {
            val spoken = utteranceId?.toIntOrNull() ?: return
            main.post { carryOnAfter(spoken) }
        }

        // Superseded by the overload below, but still abstract on the platform class, so it has to
        // be implemented: older engines call this one and nothing else.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = onError(utteranceId, -1)

        override fun onError(utteranceId: String?, errorCode: Int) {
            Logger.log("Novel TTS: engine error $errorCode on utterance $utteranceId")
            val failed = utteranceId?.toIntOrNull() ?: return
            // One sentence the engine cannot say should not end the chapter.
            main.post { carryOnAfter(failed) }
        }
    }

    /**
     * What follows a sentence the engine has finished with.
     *
     * Guarded on the user still wanting to listen, because stopping the engine is how pausing works
     * and several engines report the flushed utterances as done or failed on the way out. Without
     * this, pausing on the last sentence of a chapter would be indistinguishable from reaching the
     * end of it, and would turn the page.
     */
    private fun carryOnAfter(spoken: Int) {
        if (!wantPlaying || advancing) return
        if (spoken >= script.size - 1) finishChapter() else enqueueAhead()
    }

    /**
     * Keeps the engine's own queue a couple of sentences deep.
     *
     * Speaking one at a time and waiting for each to finish leaves an audible gap between every
     * sentence, because the engine has to spin up again each time. Queueing everything instead
     * would make pausing take effect only at the end of the chapter, and skipping impossible.
     */
    private fun enqueueAhead() {
        val engine = tts ?: return
        if (!ready || !wantPlaying) return
        while (queuedTo < index + LOOKAHEAD && queuedTo + 1 < script.size) {
            queuedTo++
            val sentence = script[queuedTo]
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            }
            engine.speak(sentence.text, TextToSpeech.QUEUE_ADD, params, queuedTo.toString())
        }
    }

    private fun publishSentence() {
        _state.update {
            it.copy(index = index, total = script.size, sentence = script.getOrNull(index)?.text.orEmpty())
        }
    }

    /**
     * Moves the page to where the voice is.
     *
     * Every sentence. Throttling this to paragraph boundaries was meant to spare the WebView some
     * relocations, and it made the feature look broken instead: a paragraph is usually still on the
     * page being read, so nothing moved for the first minute of a chapter and there was no sign
     * following was switched on at all. Asking for a position that is already on screen costs a
     * lookup and moves nothing — the cheap case, and the common one; asking for one past the end of
     * the page turns it, which is the whole point.
     */
    private var lastFollowedFraction = -1.0

    private fun followAlong() {
        if (!PrefManager.getVal<Boolean>(PrefName.NovelTtsFollowText)) return
        // Null while nothing is on screen to move — speech carries on regardless.
        val follow = onFollow ?: return
        val target = script.fractionOf(index)
        if (target == lastFollowedFraction) return
        lastFollowedFraction = target
        follow(target)
    }

    // endregion Engine

    // region Chapters

    private fun autoNext() = PrefManager.getVal<Boolean>(PrefName.NovelTtsAutoNextChapter)

    /**
     * The end of a chapter.
     *
     * Carrying on into the next one is the whole point of listening — a chapter is twenty minutes
     * and stopping dead at the end of each would make the feature useless without a screen — but it
     * is still a setting, because a downloaded book has no next chapter to fetch and some people
     * read one at a time.
     */
    private fun finishChapter() {
        if (advancing) return
        if (!autoNext() || !LNReaderSession.isActive || !LNReaderSession.hasNext()) {
            _state.update { it.copy(playing = false) }
            wantPlaying = false
            abandonFocus()
            return
        }
        changeChapter(LNReaderSession.currentIndex + 1)
    }

    private fun changeChapter(target: Int) {
        if (advancing) return
        advancing = true
        _state.update { it.copy(preparing = true) }
        tts?.stop()
        queuedTo = index - 1

        // With the reader on screen it does the loading, so the page and the voice arrive at the
        // same chapter together and its own progress handling runs. [adopt] picks the text back up.
        val reader = onRequestChapter
        if (reader != null) {
            reader(target)
            return
        }
        loadChapterHeadless(target)
    }

    /**
     * Fetches the next chapter with no reader on screen.
     *
     * Everything here is deliberately the quiet version of what the reader does. There is no window
     * to put a dialog in, so nothing is asked: the chapter is marked read, local progress is
     * recorded under the keys the chapter list reads, and the tracker is updated only where that
     * was already agreed to. Anything requiring an answer waits until the reader is opened again.
     */
    private fun loadChapterHeadless(target: Int) {
        val context = appContext ?: run { advancing = false; return }
        val parser = LNReaderSession.parser ?: run { advancing = false; return }
        val novel = LNReaderSession.novel ?: run { advancing = false; return }

        scope.launch {
            val built = withContext(Dispatchers.IO) {
                LNReaderBook.build(context, parser, novel, target)
            }
            built.onSuccess { epub ->
                reportChapterRead(LNReaderSession.currentIndex)
                LNReaderSession.currentIndex = target
                LNReaderSession.chapterAt(target)?.let { chapter ->
                    LNReaderReadState.markRead(
                        LNReaderSession.media?.id, parser.plugin.id, novel.path, chapter.path
                    )
                }
                val next = withContext(Dispatchers.IO) {
                    NovelTtsText.read(context, epub, locale)
                }
                advancing = false
                adopt(
                    context = context,
                    key = epub.absolutePath,
                    bookUri = providerUri(context, epub),
                    chapterTitle = LNReaderSession.chapterAt(target)?.name.orEmpty(),
                    script = next,
                    locale = locale,
                )
            }.onFailure {
                Logger.log("Novel TTS: could not load the next chapter — ${it.message}")
                chapterLoadFailed()
            }
        }
    }

    /**
     * Gives up on a chapter that would not load.
     *
     * Called by whichever side was doing the loading. Clearing [advancing] is the point: it is what
     * stops a second attempt while one is in flight, and left set by a failure it would block every
     * chapter change for the rest of the session — the controls would work and nothing would move.
     */
    fun chapterLoadFailed() {
        if (!advancing) return
        advancing = false
        wantPlaying = false
        _state.update { it.copy(preparing = false, playing = false) }
        abandonFocus()
    }

    private fun providerUri(context: Context, file: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
    }.getOrNull()

    /**
     * Records a chapter finished by listening.
     *
     * The same two things the reader records, minus the question: local position under the keys the
     * chapter list and continue card read, and the tracker only where the user has already said yes
     * for this media. Nothing goes out in incognito, and nothing goes out for a chapter whose number
     * cannot be worked out — there would be nothing to report.
     */
    private fun reportChapterRead(position: Int) {
        val media = LNReaderSession.media ?: return
        if (media.id < 0) return
        val chapter = LNReaderSession.chapterAt(position) ?: return
        val number = chapter.chapterNumber?.toFloat()
            ?: MediaNameAdapter.findChapterNumber(chapter.name)
            ?: return
        if (number <= 0f) return

        val label = number.toInt().toString()
        PrefManager.setCustomVal("${media.id}_current_chp", label)
        PrefManager.setCustomVal("${media.id}_$label", 100L)
        PrefManager.setCustomVal("${media.id}_${label}_max", 100L)

        if (PrefManager.getVal<Boolean>(PrefName.Incognito)) return
        if (media.isAdult && !PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)) return
        if (!PrefManager.getCustomVal("${media.id}_save_progress", true)) return
        val text = if (number == number.toLong().toFloat()) number.toLong().toString()
        else number.toString()
        updateProgress(media, text)
    }

    /**
     * The book being read, for the notification to tap back through to.
     *
     * Kept as the URI the reader was actually given rather than derived from [bookKey]: the first
     * chapter of a session arrives as a provider URI from whoever opened the reader, and only the
     * chapters loaded afterwards are known here as paths.
     */
    fun currentBookUri(): Uri? = bookUri

    // endregion Chapters

    // region Audio focus

    private var focusRequest: AudioFocusRequest? = null

    private fun audioManager() =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                // Speech does not duck usefully — half-volume narration under a navigation prompt
                // is just two things nobody can follow — so a duck request is treated as a pause.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.playing) {
                    pausedByFocus = true
                    tts?.stop()
                    queuedTo = index - 1
                    wantPlaying = false
                    _state.update { it.copy(playing = false) }
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                pausedByFocus = false
                play()
            }
        }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager() ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val manager = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

    // endregion Audio focus

    /** Minutes remaining on the sleep timer, for the settings sheet to show. */
    fun sleepMinutesLeft(): Int {
        val at = _state.value.sleepAt
        if (at <= 0L) return 0
        val left = at - android.os.SystemClock.elapsedRealtime()
        return if (left <= 0L) 0 else (left / 60_000.0).roundToInt().coerceAtLeast(1)
    }
}
