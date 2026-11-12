package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmojiServiceTest {
    @Test
    void oversizeRemoteEmojiReportsTheActualLimit() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildById("123456789012345678")).thenReturn(guild);
        try (var guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch("https://example.com/emoji.png", 256 * 1024, "image"))
                    .thenThrow(new RemoteFetchGuard.TooLargeException("image exceeds the maximum allowed size"));
            assertThatThrownBy(() -> new EmojiService(jda).createEmoji("123456789012345678", "emoji", null, "https://example.com/emoji.png", null))
                    .hasMessageContaining("256 KB limit");
        }
    }

    @Test
    void remoteEmojiUsesSharedGuardWithActualDiscordByteLimit() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildById("123456789012345678")).thenReturn(mock(Guild.class));
        try (var guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch("https://example.com/emoji.png", 256 * 1024, "image"))
                    .thenThrow(new IllegalArgumentException("too large"));
            assertThatThrownBy(() -> new EmojiService(jda).createEmoji("123456789012345678", "emoji", null, "https://example.com/emoji.png", null))
                    .hasMessage("too large");
            guard.verify(() -> RemoteFetchGuard.fetch("https://example.com/emoji.png", 256 * 1024, "image"));
        }
    }
}
