package io.github.tffy1.pdfviewer.ui.viewer.links

import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.core.model.PageRect
import io.github.tffy1.pdfviewer.pdf.PdfLink
import kotlin.math.max
import kotlin.math.min

/* Pure link rules (hit testing and the external-URI allow-list), unit-tested. */

/** Extra margin in points around link rects: links are often tight boxes around small text. */
internal const val LINK_HIT_TOLERANCE_PT = 4f

/** Schemes a PDF may open. Everything else (intent:, file:, content:, javascript:…) is blocked. */
private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "tel")

private val SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

/**
 * The link under [point]. Links that really contain the point win over ones that only match
 * within [tolerance]; among several candidates the smallest (most specific) one is chosen.
 */
internal fun hitTestLinks(
    links: List<PdfLink>,
    point: PagePoint,
    tolerance: Float = LINK_HIT_TOLERANCE_PT,
): PdfLink? {
    val exact = links.filter { normalized(it.bounds).contains(point) }
    val candidates = exact.ifEmpty {
        links.filter { normalized(it.bounds).inset(-tolerance).contains(point) }
    }
    return candidates.minByOrNull { val r = normalized(it.bounds); r.width * r.height }
}

private fun normalized(rect: PageRect): PageRect = PageRect(
    min(rect.left, rect.right),
    min(rect.top, rect.bottom),
    max(rect.left, rect.right),
    max(rect.top, rect.bottom),
)

/**
 * Cleans a URI taken from a PDF for display and launching: trims whitespace, lower-cases the
 * scheme (intent filters match it case-sensitively) and turns a bare "www." address into http.
 * Returns null for a blank URI.
 */
internal fun normalizeExternalUri(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val scheme = uriScheme(trimmed)
    return when {
        scheme != null -> scheme.lowercase() + trimmed.substring(scheme.length)
        trimmed.startsWith("www.", ignoreCase = true) -> "http://$trimmed"
        else -> trimmed
    }
}

/** The scheme of [uri] as written (without the colon), or null when it has none. */
internal fun uriScheme(uri: String): String? = SCHEME_REGEX.find(uri)?.groupValues?.get(1)

/**
 * Whether a (normalized) URI from a PDF may be handed to another app: only web, e-mail and
 * phone links, and never ones containing control or bidi-override characters that could
 * disguise the real target.
 */
internal fun isAllowedExternalUri(uri: String): Boolean {
    val scheme = uriScheme(uri)?.lowercase() ?: return false
    if (scheme !in ALLOWED_SCHEMES) return false
    if (uri.any { it.isISOControl() || it in BIDI_CONTROLS }) return false
    if (scheme == "http" || scheme == "https") return !externalUriHost(uri).isNullOrEmpty()
    return uri.length > scheme.length + 1
}

private val BIDI_CONTROLS: Set<Char> = setOf(
    '‎', '‏', '؜',
    '‪', '‫', '‬', '‭', '‮',
    '⁦', '⁧', '⁨', '⁩',
)

/**
 * Host of an http(s) URI, ignoring any "user:password@" part (a classic phishing trick:
 * "https://bank.com@evil.example"). Null for other schemes or when there is no host.
 */
internal fun externalUriHost(uri: String): String? {
    val scheme = uriScheme(uri)?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val rest = uri.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return null
    val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith("[")) {
        hostAndPort.substringBefore(']') + "]" // IPv6 literal
    } else {
        hostAndPort.substringBefore(':')
    }
    return host.ifEmpty { null }
}
