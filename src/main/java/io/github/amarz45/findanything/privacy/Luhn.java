package io.github.amarz45.findanything.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Luhn checksum, which card numbers and Canadian SINs both satisfy, and a search for numbers
 * that pass it. Checking the checksum keeps most ordinary numbers (dates, sizes, phone numbers)
 * from being taken for one.
 */
final class Luhn {

    /** Groups of digits joined by single spaces or hyphens, such as "4111 1111 1111 1111". */
    private static final Pattern RUN = Pattern.compile("(?<!\\d)\\d+(?:[ -]\\d+)*(?!\\d)");

    private static final Pattern GROUP = Pattern.compile("\\d+");

    private Luhn() {}

    /** Whether the digits pass the Luhn check; anything that isn't a digit is skipped. */
    static boolean isValid(CharSequence number) {
        int sum = 0;
        boolean doubled = false;
        int digits = 0;
        for (int i = number.length() - 1; i >= 0; i--) {
            char c = number.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int digit = c - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
            digits++;
        }
        return digits > 0 && sum % 10 == 0;
    }

    /**
     * Finds the numbers of {@code minDigits} to {@code maxDigits} digits that pass the Luhn check.
     * A number is one or more whole groups of digits separated by single spaces or hyphens, so a
     * card number followed on the same line by another number is still found. The longest match at
     * each place wins, and matches don't overlap.
     *
     * @return each match as it is written in the text, separators included
     */
    static List<String> findNumbers(String text, int minDigits, int maxDigits) {
        List<String> found = new ArrayList<>();
        Matcher run = RUN.matcher(text);
        while (run.find()) {
            List<int[]> groups = new ArrayList<>(); // each group's start and end in the text
            Matcher group = GROUP.matcher(text).region(run.start(), run.end());
            while (group.find()) {
                groups.add(new int[] {group.start(), group.end()});
            }
            int first = 0;
            while (first < groups.size()) {
                int last = longestValid(text, groups, first, minDigits, maxDigits);
                if (last < 0) {
                    first++;
                } else {
                    found.add(text.substring(groups.get(first)[0], groups.get(last)[1]));
                    first = last + 1;
                }
            }
        }
        return found;
    }

    /** The last group of the longest valid number that starts at group {@code first}, or -1. */
    private static int longestValid(
            String text, List<int[]> groups, int first, int minDigits, int maxDigits) {
        int best = -1;
        int digits = 0;
        for (int last = first; last < groups.size(); last++) {
            digits += groups.get(last)[1] - groups.get(last)[0];
            if (digits > maxDigits) {
                break;
            }
            if (digits >= minDigits
                    && isValid(text.subSequence(groups.get(first)[0], groups.get(last)[1]))) {
                best = last;
            }
        }
        return best;
    }
}
