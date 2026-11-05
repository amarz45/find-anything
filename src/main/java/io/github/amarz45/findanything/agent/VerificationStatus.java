package io.github.amarz45.findanything.agent;

/** What checking a citation against the index found (F09). */
public enum VerificationStatus {
    /** The quote appears in the cited file, on the cited page. */
    VERIFIED,
    /** The quote wasn't found there, or the citation hasn't been checked yet. */
    UNVERIFIED,
    /** The file on disk has changed since it was indexed, so the quote can't be trusted. */
    SOURCE_CHANGED
}
