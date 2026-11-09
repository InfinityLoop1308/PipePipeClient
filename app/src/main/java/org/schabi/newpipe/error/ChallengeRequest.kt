package org.schabi.newpipe.error

import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Finds out whether an error means that a site wants the user to pass a challenge
 * (reCAPTCHA, Cloudflare, Bandcamp's client challenge, ...) before it serves more content.
 *
 * Errors usually reach the UI wrapped in other exceptions, e.g. the player wraps a failed
 * stream info load in a `StreamInfoLoadException`, so the whole cause chain is searched.
 */
object ChallengeRequest {

    /** Bound on the cause chain walk, as protection against unusual exception graphs. */
    private const val MAX_CAUSE_DEPTH = 16

    /**
     * @return the [ReCaptchaException] found in [throwable] or its causes, or null if there is
     * none
     */
    @JvmStatic
    fun find(throwable: Throwable?): ReCaptchaException? {
        var current = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is ReCaptchaException) {
                return current
            }
            val cause = current.cause
            if (cause === current) {
                return null
            }
            current = cause
            depth++
        }
        return null
    }

    /**
     * @return the first [ReCaptchaException] found in any of [errors] or their causes, or null
     * if there is none
     */
    @JvmStatic
    fun find(errors: Collection<Throwable>): ReCaptchaException? =
        errors.firstNotNullOfOrNull { find(it) }

    /**
     * @param challengeDomain the registrable domain of the pending challenge's page (see
     *                        [ChallengeCookies.domainOf]), or null if it is unknown
     * @param solvedDomains   the registrable domains a challenge screen stored cookies for
     * @return whether those cookies can have passed the pending challenge: they must include
     * the challenge's domain. With an unknown domain, any stored cookie counts.
     */
    @JvmStatic
    fun isSolvedBy(challengeDomain: String?, solvedDomains: Collection<String>): Boolean {
        if (solvedDomains.isEmpty()) {
            return false
        }
        return challengeDomain == null || challengeDomain in solvedDomains
    }
}
