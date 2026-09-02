package dev.saseq;

/** Shared validation for Discord's nonzero unsigned 64-bit snowflake identifiers. */
public final class DiscordSnowflake {
    private DiscordSnowflake() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() < 17 || value.length() > 20
                || value.chars().allMatch(character -> character == '0')) {
            return false;
        }
        return isUnsignedValue(value);
    }

    /** Accepts a numeric Discord API value without imposing the canonical ID length. */
    public static boolean isUnsignedValue(String value) {
        if (value == null || value.isEmpty()
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            return false;
        }
        try {
            Long.parseUnsignedLong(value);
            return true;
        } catch (NumberFormatException tooLargeForUnsignedLong) {
            return false;
        }
    }

    /** Returns the canonical unsigned decimal form of a validated snowflake. */
    public static String canonicalize(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid Discord snowflake");
        }
        return Long.toUnsignedString(Long.parseUnsignedLong(value));
    }
}
