package app.soundbound.core.prefs

import app.soundbound.core.library.LibraryFilter
import app.soundbound.core.library.LibrarySort
import kotlinx.serialization.Serializable

/** How the app is coloured. */
enum class AppTheme(val displayName: String) {
    FOLLOW_SYSTEM("Match the system"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("True black"),
}

/** Reading surface presets. Named after what they are for, not what colour they are. */
enum class ReaderTheme(val displayName: String) {
    PAPER("Paper"),
    CREAM("Cream"),
    SEPIA("Sepia"),
    GREY("Grey"),
    NIGHT("Night"),
    MIDNIGHT("Midnight"),
    CONTRAST("High contrast"),
    FOLLOW_APP("Match the app"),
}

enum class ReadingFont(val displayName: String, val isSerif: Boolean) {
    SYSTEM_SERIF("Serif", true),
    SYSTEM_SANS("Sans serif", false),
    LITERATA("Literata", true),
    BOOKERLY("Bookerly-like", true),
    ATKINSON("Atkinson Hyperlegible", false),
    OPEN_DYSLEXIC("OpenDyslexic", false),
}

enum class PageTurn(val displayName: String) {
    SCROLL("Continuous scroll"),
    PAGED("Page by page"),
    PAGED_ANIMATED("Page by page, with a curl"),
}

/** How the spoken text is marked in the reader. */
enum class HighlightStyle(val displayName: String) {
    SENTENCE("Highlight the sentence"),
    WORD("Highlight the word"),
    SENTENCE_AND_WORD("Sentence, with the word emphasised"),
    UNDERLINE("Underline only"),
    NONE("No highlight"),
}

@Serializable
data class TypographySettings(
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.55f,
    val paragraphSpacingMultiplier: Float = 0.8f,
    val letterSpacingEm: Float = 0f,
    val wordSpacingEm: Float = 0f,
    val pageMarginDp: Float = 24f,
    val maxLineLengthCharacters: Int = 72,
    val font: String = ReadingFont.LITERATA.name,
    val justifyText: Boolean = false,
    val hyphenate: Boolean = true,
    val indentParagraphs: Boolean = false,
    val boldText: Boolean = false,
    /** Honour the publisher's own stylesheet where the book provides one. */
    val usePublisherStyles: Boolean = false,
) {
    val readingFont: ReadingFont
        get() = runCatching { ReadingFont.valueOf(font) }.getOrDefault(ReadingFont.LITERATA)

    fun coerced() = copy(
        fontSizeSp = fontSizeSp.coerceIn(11f, 48f),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(1.0f, 3.0f),
        paragraphSpacingMultiplier = paragraphSpacingMultiplier.coerceIn(0f, 3f),
        letterSpacingEm = letterSpacingEm.coerceIn(-0.05f, 0.3f),
        wordSpacingEm = wordSpacingEm.coerceIn(0f, 1f),
        pageMarginDp = pageMarginDp.coerceIn(0f, 96f),
        maxLineLengthCharacters = maxLineLengthCharacters.coerceIn(30, 140),
    )
}

@Serializable
data class SpeechSettings(
    val voiceId: String? = null,
    val rate: Float = 1.0f,
    val pitchSemitones: Float = 0f,
    val volume: Float = 1.0f,
    val expressiveness: Float = 0.667f,
    val cadenceVariation: Float = 0.8f,
    val speakHeadings: Boolean = true,
    val announceFootnotes: Boolean = false,
    val speakImageDescriptions: Boolean = true,
    val expandNumbers: Boolean = true,
    val expandAbbreviations: Boolean = true,
    val spellOutAcronyms: Boolean = true,
    val readWebAddresses: Boolean = false,
    val readYearsAsYears: Boolean = true,
    val sentencePauseMillis: Int = 160,
    val paragraphPauseMillis: Int = 420,
    val chapterPauseMillis: Int = 900,
    /** Continue into the next chapter rather than stopping at the end of one. */
    val continueAcrossChapters: Boolean = true,
    /** Scroll the reader to follow the narration. */
    val followWithReader: Boolean = true,
    val highlightStyle: String = HighlightStyle.SENTENCE_AND_WORD.name,
    /** Pronunciation corrections the user has made, keyed by lower-case word. */
    val pronunciationOverrides: Map<String, String> = emptyMap(),
    /** Prefer espeak-ng for phonemisation when the library is present. */
    val preferEspeak: Boolean = true,
    val lastSleepTimerMinutes: Int = 30,
    /** Duck rather than pause when another app makes a short sound. */
    val duckOnInterruption: Boolean = true,
    /** Resume automatically when headphones are reconnected. */
    val resumeOnHeadphonesReconnected: Boolean = true,
) {
    val highlight: HighlightStyle
        get() = runCatching { HighlightStyle.valueOf(highlightStyle) }
            .getOrDefault(HighlightStyle.SENTENCE_AND_WORD)

    fun toSpeechParams() = app.soundbound.core.tts.SpeechParams(
        rate = rate,
        pitchSemitones = pitchSemitones,
        volume = volume,
        expressiveness = expressiveness,
        cadenceVariation = cadenceVariation,
    ).coerced()

    fun toNormalisationOptions() = app.soundbound.core.text.NormalisationOptions(
        expandNumbers = expandNumbers,
        expandAbbreviations = expandAbbreviations,
        spellOutAcronyms = spellOutAcronyms,
        readWebAddresses = readWebAddresses,
        readYearsAsYears = readYearsAsYears,
    )

    fun toSegmentationOptions() = app.soundbound.core.text.SegmentationOptions(
        pauseAfterSentenceMillis = sentencePauseMillis.coerceIn(0, 2000),
        pauseAfterParagraphMillis = paragraphPauseMillis.coerceIn(0, 4000),
        pauseAfterHeadingMillis = chapterPauseMillis.coerceIn(0, 6000),
    )

    fun toPlanOptions() = app.soundbound.core.player.SpeechPlanOptions(
        segmentation = toSegmentationOptions(),
        normalisation = toNormalisationOptions(),
        speakHeadings = speakHeadings,
        announceFootnotes = announceFootnotes,
        speakImageDescriptions = speakImageDescriptions,
    )
}

@Serializable
data class ReaderSettings(
    val theme: String = ReaderTheme.FOLLOW_APP.name,
    val pageTurn: String = PageTurn.SCROLL.name,
    val keepScreenOn: Boolean = true,
    val showProgressBar: Boolean = true,
    val showClock: Boolean = true,
    val showBatteryLevel: Boolean = false,
    val showChapterTitle: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    val tapEdgesToTurnPages: Boolean = true,
    val fullScreenImmersive: Boolean = true,
    val brightnessOverride: Float? = null,
    /** Warm the display at night without changing the theme. */
    val warmth: Float = 0f,
) {
    val readerTheme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(theme) }.getOrDefault(ReaderTheme.FOLLOW_APP)

    val pageTurnStyle: PageTurn
        get() = runCatching { PageTurn.valueOf(pageTurn) }.getOrDefault(PageTurn.SCROLL)
}

@Serializable
data class LibrarySettings(
    val sort: String = LibrarySort.RECENTLY_OPENED.name,
    val filter: String = LibraryFilter.ALL.name,
    val gridColumns: Int = 0,
    val showProgressOnCovers: Boolean = true,
    /** Folders watched for new books, so the library picks them up on its own. */
    val watchedFolders: List<String> = emptyList(),
) {
    val librarySort: LibrarySort
        get() = runCatching { LibrarySort.valueOf(sort) }.getOrDefault(LibrarySort.RECENTLY_OPENED)

    val libraryFilter: LibraryFilter
        get() = runCatching { LibraryFilter.valueOf(filter) }.getOrDefault(LibraryFilter.ALL)
}

/** Everything the user can change, in one serialisable object. */
@Serializable
data class Settings(
    val version: Int = 1,
    val appTheme: String = AppTheme.FOLLOW_SYSTEM.name,
    /** Colour the interface from the system palette, where the platform offers one. */
    val useDynamicColour: Boolean = true,
    val typography: TypographySettings = TypographySettings(),
    val speech: SpeechSettings = SpeechSettings(),
    val reader: ReaderSettings = ReaderSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val lastOpenedBookId: String? = null,
    /**
     * A short vibration on the actions worth confirming by touch.
     *
     * On by default, and offered as a setting because it is exactly the sort of thing people
     * feel strongly about in both directions — and because some find it unbearable.
     */
    val haptics: Boolean = true,
    /** Set once the user has been shown the welcome flow. */
    val hasCompletedSetup: Boolean = false,
) {
    val theme: AppTheme
        get() = runCatching { AppTheme.valueOf(appTheme) }.getOrDefault(AppTheme.FOLLOW_SYSTEM)
}
