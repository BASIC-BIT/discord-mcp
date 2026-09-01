package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordServiceTest {

    @Test
    void getBotInfoReturnsStructuredAuthenticatedIdentity() {
        JDA jda = mock(JDA.class);
        SelfUser self = mock(SelfUser.class);
        Guild guild = mock(Guild.class);
        when(jda.getSelfUser()).thenReturn(self);
        when(jda.getGuildById("987654321098765432")).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(self.getId()).thenReturn("123456789012345678");
        when(self.getName()).thenReturn("BASIC Ops");

        String result = new DiscordService(jda).getBotInfo("987654321098765432");

        assertThat(result).isEqualTo(
                "{\"botUserId\":\"123456789012345678\",\"botName\":\"BASIC Ops\","
                        + "\"guildId\":\"987654321098765432\"}");
    }
}
