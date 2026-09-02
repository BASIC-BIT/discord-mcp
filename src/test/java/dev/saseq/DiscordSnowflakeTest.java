package dev.saseq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordSnowflakeTest {
    @Test
    void acceptsOnlyNonzeroUnsigned64BitDiscordSnowflakes() {
        assertThat(DiscordSnowflake.isValid("12345678901234567")).isTrue();
        assertThat(DiscordSnowflake.isValid("18446744073709551615")).isTrue();

        assertThat(DiscordSnowflake.isValid(null)).isFalse();
        assertThat(DiscordSnowflake.isValid("1234567890123456")).isFalse();
        assertThat(DiscordSnowflake.isValid("00000000000000000")).isFalse();
        assertThat(DiscordSnowflake.isValid("99999999999999999999")).isFalse();
        assertThat(DiscordSnowflake.isValid("1234567890123456٧")).isFalse();
    }
}
