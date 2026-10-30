package com.sayl.downloader

/** نوع الفشل يقرر ما بعده: انتظار الشبكة، أو إعادة مؤجلة، أو تبديل السيرفر، أو فشل نهائي. */
object Failure {
    enum class Kind { NETWORK, TEMPORARY, EXTRACT, PERMANENT }

    private val PERMANENT = Regex(
        "sign in|login required|log in|private video|members only|age-restricted|confirm your age|unsupported url|" +
            "errno 28|no space left|video unavailable|has been removed|http error 404|not available in your country",
        RegexOption.IGNORE_CASE,
    )
    private val NETWORK = Regex(
        "timed out|network is unreachable|connection (reset|refused|aborted)|temporary failure in name resolution|" +
            "getaddrinfo|no address associated|failed to resolve|software caused connection abort",
        RegexOption.IGNORE_CASE,
    )
    private val EXTRACT = Regex(
        "unable to extract|http error 403|nsig|signature|requested format is not available|unable to download json|no video formats",
        RegexOption.IGNORE_CASE,
    )

    fun classify(message: String?): Kind {
        val m = message.orEmpty()
        return when {
            PERMANENT.containsMatchIn(m) -> Kind.PERMANENT
            NETWORK.containsMatchIn(m) -> Kind.NETWORK
            EXTRACT.containsMatchIn(m) -> Kind.EXTRACT
            else -> Kind.TEMPORARY
        }
    }
}

/** إعادة المحاولة بانتظار متزايد: 30 ث، ثم دقيقتان، ثم 10 دقائق. */
object Retry {
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 30_000L
        attempt == 2 -> 120_000L
        else -> 600_000L
    }

    fun shouldRetry(kind: Failure.Kind, attempts: Int, max: Int): Boolean =
        (kind == Failure.Kind.TEMPORARY || kind == Failure.Kind.EXTRACT) && attempts < max
}
