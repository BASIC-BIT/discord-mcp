package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageReadSyncTest {
    private final JDA jda = mock(JDA.class);
    private final TextChannel channel = mock(TextChannel.class);
    private final MessageHistory history = mock(MessageHistory.class);
    private final MessageService service = new MessageService(jda);
    private static final String CHANNEL = "345678901234567890";
    private static final String TARGET = "456789012345678901";

    @BeforeEach
    void setup() {
        when(jda.getTextChannelById(CHANNEL)).thenReturn(channel);
        when(jda.getGatewayIntents()).thenReturn(EnumSet.of(GatewayIntent.MESSAGE_CONTENT));
        when(channel.getHistory()).thenReturn(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN_MESSAGE", "MISSING_ACCESS"})
    void replyLookupPreservesProviderFailuresWithoutScanning(String errorName) {
        var failure = mock(net.dv8tion.jda.api.exceptions.ErrorResponseException.class);
        when(failure.getErrorResponse()).thenReturn(net.dv8tion.jda.api.requests.ErrorResponse.valueOf(errorName));
        RestAction<Message> lookup = action(null);
        when(lookup.complete()).thenThrow(failure);
        when(channel.retrieveMessageById(TARGET)).thenReturn(lookup);
        assertThatThrownBy(() -> service.getMessageReplies(CHANNEL, TARGET, "1")).isSameAs(failure);
        verify(channel, never()).getHistoryAfter(anyString(), anyInt());
    }

    @Test
    void singleMessageUsesUpstreamFormatAndPreservesRawMentionsForVerification() {
        Message message = message(TARGET, "hello <@123456789012345678>");
        when(message.getContentDisplay()).thenReturn("hello @alice");
        doReturn(action(message)).when(channel).retrieveMessageById(TARGET);
        assertThat(service.getMessage(CHANNEL, TARGET))
                .startsWith("- (ID: " + TARGET + ") **[alice]** (Author ID: 123456789012345678)")
                .contains("```hello @alice```", "Raw content: ```hello <@123456789012345678>```", "Jump URL: https://discord.com/channels/1/2/3")
                .doesNotContain("contentAvailable", "\"guildId\"");
    }

    @Test
    void singleMessageLabelsUnknownContentAndKnownEmptyBotContent() {
        Message message = message(TARGET, "");
        doReturn(action(message)).when(channel).retrieveMessageById(TARGET);
        when(jda.getGatewayIntents()).thenReturn(EnumSet.noneOf(GatewayIntent.class));
        assertThat(service.getMessage(CHANNEL, TARGET)).contains("content not available to this bot");
        SelfUser bot = mock(SelfUser.class);
        when(bot.getId()).thenReturn("123456789012345678");
        when(jda.getSelfUser()).thenReturn(bot);
        assertThat(service.getMessage(CHANNEL, TARGET)).contains("[no text content]").doesNotContain("not available");
    }

    @Test
    void singleMessageIncludesReplyContextAndLabelsMissingReference() {
        Message reply = message(TARGET, "reply");
        repliesTo(reply, "111111111111111111");
        doReturn(action(reply)).when(channel).retrieveMessageById(TARGET);
        assertThat(service.getMessage(CHANNEL, TARGET)).contains("Replying to message 111111111111111111 (not available");
        doReturn(message("111111111111111111", "original")).when(reply).getReferencedMessage();
        assertThat(service.getMessage(CHANNEL, TARGET)).contains("Replying to: - (ID: 111111111111111111)", "```original```");
    }

    @Test
    void searchIsCaseInsensitiveAndProbesExactLimitCoverage() {
        doReturn(action(List.of(message(TARGET, "HELLO"), message("2", "other")))).when(history).retrievePast(2);
        doReturn(action(List.of())).when(history).retrievePast(1);
        assertThat(service.searchMessages(CHANNEL, "hello", "2"))
                .contains("Found 1 messages", "scanned 2 messages", "the whole history was scanned");
        verify(history).retrievePast(1);
    }

    @Test
    void searchReportsWhenOlderMessagesOrContentWereNotScanned() {
        when(jda.getGatewayIntents()).thenReturn(EnumSet.noneOf(GatewayIntent.class));
        doReturn(action(List.of(message(TARGET, ""))), action(List.of(message("2", "older")))).when(history).retrievePast(1);
        assertThat(service.searchMessages(CHANNEL, "hello", "1"))
                .contains("older messages were not scanned", "content unavailable for 1 scanned message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "nope"})
    void scanLimitsRejectInvalidValues(String limit) {
        assertThatThrownBy(() -> service.searchMessages(CHANNEL, "hello", limit)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getMessageReplies(CHANNEL, TARGET, limit)).isInstanceOf(IllegalArgumentException.class);
        verify(channel, never()).getHistory();
    }

    @Test
    void repliesFollowPaginationAndIgnoreNonReplyReferences() {
        Message reply = message("456789012345678903", "reply");
        repliesTo(reply, TARGET);
        Message forward = message("456789012345678902", "forward");
        MessageReference reference = reply.getMessageReference();
        when(forward.getMessageReference()).thenReturn(reference);
        doReturn(action(message(TARGET, "original"))).when(channel).retrieveMessageById(TARGET);
        MessageHistory.MessageRetrieveAction retrieve = mock(MessageHistory.MessageRetrieveAction.class);
        when(retrieve.complete()).thenReturn(history);
        when(channel.getHistoryAfter(TARGET, 2)).thenReturn(retrieve);
        when(history.getRetrievedHistory()).thenReturn(List.of(reply, forward));
        doReturn(action(List.of())).when(history).retrieveFuture(1);
        assertThat(service.getMessageReplies(CHANNEL, TARGET, "2"))
                .contains("Found 1 replies", "scanned 2 messages", "up to the latest message", "Reply to: " + TARGET)
                .doesNotContain("```forward```");
    }

    @ParameterizedTest
    @ValueSource(strings = {"`", "``", "`inline`", "one\ntwo", "one\r\ntwo"})
    void readbackSeparatesDelimitersFromBackticksAndLineBreaks(String raw) {
        doReturn(action(message(TARGET, raw))).when(channel).retrieveMessageById(TARGET);
        assertThat(service.getMessage(CHANNEL, TARGET)).contains("\n```\n" + raw + "\n```");
    }

    @Test
    void codeFenceContentRemainsUnambiguousAndIncludesEditTime() {
        String raw = "before\n```java\ncode\n```\n````\nafter";
        Message message = message(TARGET, raw);
        when(message.getTimeEdited()).thenReturn(OffsetDateTime.parse("2026-11-12T01:00:00Z"));
        doReturn(action(message)).when(channel).retrieveMessageById(TARGET);
        assertThat(service.getMessage(CHANNEL, TARGET))
                .contains("\n`````\n" + raw + "\n`````", "Edited: 2026-11-12T01:00Z");
        when(message.getContentDisplay()).thenReturn("display changed");
        assertThat(service.getMessage(CHANNEL, TARGET))
                .contains("Raw content: \n`````\n" + raw + "\n`````");
    }

    @Test
    void searchCapsRenderedMatchesWithoutLosingScanCoverage() {
        Message match = message(TARGET, "match");
        List<Message> firstPage = java.util.Collections.nCopies(100, match);
        doReturn(action(firstPage)).when(history).retrievePast(100);
        doReturn(action(List.of(message("456789012345678902", "match omitted"))), action(List.of()))
                .when(history).retrievePast(1);
        assertThat(service.searchMessages(CHANNEL, "match", "101"))
                .contains("Found 101 messages", "scanned 101 messages", "the whole history was scanned",
                        "Showing first 100 of 101 matches.")
                .doesNotContain("match omitted");
        verify(history, times(2)).retrievePast(1);
    }

    @Test
    void repliesAdvancePastOnePageAndCapRenderedMatches() {
        Message reply = message(TARGET, "reply");
        repliesTo(reply, TARGET);
        Message last = message("456789012345678902", "omitted reply");
        repliesTo(last, TARGET);
        when(last.getIdLong()).thenReturn(1L);
        doReturn(action(message(TARGET, "original"))).when(channel).retrieveMessageById(TARGET);
        MessageHistory.MessageRetrieveAction retrieve = mock(MessageHistory.MessageRetrieveAction.class);
        when(retrieve.complete()).thenReturn(history);
        when(channel.getHistoryAfter(TARGET, 100)).thenReturn(retrieve);
        when(history.getRetrievedHistory()).thenReturn(java.util.Collections.nCopies(100, reply));
        doReturn(action(List.of(last)), action(List.of())).when(history).retrieveFuture(1);
        assertThat(service.getMessageReplies(CHANNEL, TARGET, "101"))
                .contains("Found 101 replies", "scanned 101 messages", "up to the latest message",
                        "Showing first 100 of 101 matches.")
                .doesNotContain("omitted reply");
        verify(history, times(2)).retrieveFuture(1);
    }

    @Test
    void deleteFailureReachesCallerBeforeSuccess() {
        Message message = message(TARGET, "text");
        doReturn(action(message)).when(channel).retrieveMessageById(TARGET);
        AuditableRestAction<Void> deletion = mock(AuditableRestAction.class);
        when(message.delete()).thenReturn(deletion);
        when(deletion.complete()).thenThrow(new IllegalStateException("Discord refused"));
        assertThatThrownBy(() -> service.deleteMessage(CHANNEL, TARGET)).hasMessage("Discord refused");
        verify(deletion, never()).queue();
    }

    private void repliesTo(Message message, String target) {
        MessageReference reference = mock(MessageReference.class);
        when(message.getType()).thenReturn(MessageType.INLINE_REPLY);
        when(message.getMessageReference()).thenReturn(reference);
        when(reference.getMessageId()).thenReturn(target);
    }

    private Message message(String id, String content) {
        Message message = mock(Message.class);
        User author = mock(User.class);
        when(author.getId()).thenReturn("123456789012345678");
        when(author.getName()).thenReturn("alice");
        when(message.getId()).thenReturn(id);
        when(message.getAuthor()).thenReturn(author);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-11-12T00:00:00Z"));
        when(message.getContentRaw()).thenReturn(content);
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");
        return message;
    }

    @SuppressWarnings("unchecked")
    private <T> RestAction<T> action(T result) {
        RestAction<T> action = mock(RestAction.class);
        when(action.complete()).thenReturn(result);
        return action;
    }
}
