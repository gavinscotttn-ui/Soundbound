package app.soundbound.core.book.epub

import app.soundbound.core.model.BookMetadata

/** One entry of the OPF `<manifest>`. */
internal data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
) {
    val isCover: Boolean get() = "cover-image" in properties
    val isNav: Boolean get() = "nav" in properties
    val isDocument: Boolean
        get() = mediaType == "application/xhtml+xml" || mediaType == "text/html" ||
            mediaType == "application/x-dtbook+xml"
}

/** One entry of the OPF `<spine>`, already resolved against the manifest. */
internal data class SpineItem(
    val item: ManifestItem,
    val linear: Boolean,
    val properties: Set<String> = emptySet(),
)

/** The parsed OPF package document. */
internal data class EpubPackage(
    val metadata: BookMetadata,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    /** Path of the OPF inside the zip; all hrefs are relative to its directory. */
    val opfPath: String,
    val tocItemId: String?,
    val coverItemId: String?,
    val version: String,
    val pageProgressionRightToLeft: Boolean,
) {
    val opfDirectory: String
        get() = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
}
