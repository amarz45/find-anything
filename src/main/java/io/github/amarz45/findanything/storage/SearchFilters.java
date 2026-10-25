package io.github.amarz45.findanything.storage;

import io.github.amarz45.findanything.config.IndexConfig;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Narrows a search by file type, modification date, size and location (F04). Every part is
 * optional: an empty type set, a null date or location, or a negative size means "no limit". The
 * index applies the filters inside its queries; {@link #matches} is the same test for a file
 * already in hand.
 *
 * @param fileTypes the file types to keep, as {@link IndexedFile#fileType()} ("pdf", "docx")
 * @param modifiedAfter keep files modified on or after this day (local time)
 * @param modifiedBefore keep files modified on or before this day (local time)
 * @param minSizeBytes keep files at least this big, or -1
 * @param maxSizeBytes keep files at most this big, or -1
 * @param location keep files under this folder, which must be inside a search folder
 */
public record SearchFilters(
        Set<String> fileTypes,
        LocalDate modifiedAfter,
        LocalDate modifiedBefore,
        long minSizeBytes,
        long maxSizeBytes,
        Path location) {

    /** No filtering at all. */
    public static final SearchFilters NONE = new SearchFilters(Set.of(), null, null, -1, -1, null);

    public SearchFilters {
        fileTypes = Set.copyOf(Objects.requireNonNull(fileTypes, "fileTypes"));
        minSizeBytes = Math.max(minSizeBytes, -1);
        maxSizeBytes = Math.max(maxSizeBytes, -1);
        location = location == null ? null : location.toAbsolutePath().normalize();
    }

    /**
     * Checks the filters against each other and the configuration. Returns the problems to show the
     * user, or an empty list when the filters are fine.
     */
    public List<String> validate(IndexConfig indexConfig) {
        List<String> errors = new ArrayList<>();
        if (modifiedAfter != null
                && modifiedBefore != null
                && modifiedAfter.isAfter(modifiedBefore)) {
            errors.add(
                    "The start date ("
                            + modifiedAfter
                            + ") is after the end date ("
                            + modifiedBefore
                            + ").");
        }
        if (minSizeBytes >= 0 && maxSizeBytes >= 0 && minSizeBytes > maxSizeBytes) {
            errors.add(
                    "The minimum size ("
                            + minSizeBytes
                            + " bytes) is larger than the maximum size ("
                            + maxSizeBytes
                            + " bytes).");
        }
        if (location != null && indexConfig.findFolderContaining(location).isEmpty()) {
            errors.add("The location " + location + " is outside the search folders.");
        }
        return errors;
    }

    /** Whether a file passes every filter. Dates are compared in the system's time zone. */
    public boolean matches(IndexedFile file) {
        if (!fileTypes.isEmpty() && !fileTypes.contains(file.fileType())) {
            return false;
        }
        LocalDate day = file.modifiedTime().atZone(ZoneId.systemDefault()).toLocalDate();
        if (modifiedAfter != null && day.isBefore(modifiedAfter)) {
            return false;
        }
        if (modifiedBefore != null && day.isAfter(modifiedBefore)) {
            return false;
        }
        if (minSizeBytes >= 0 && file.sizeBytes() < minSizeBytes) {
            return false;
        }
        if (maxSizeBytes >= 0 && file.sizeBytes() > maxSizeBytes) {
            return false;
        }
        return location == null || file.path().startsWith(location);
    }

    /** True when nothing is filtered. */
    public boolean isEmpty() {
        return equals(NONE);
    }
}
