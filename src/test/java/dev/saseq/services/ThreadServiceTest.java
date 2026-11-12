package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.pagination.ThreadChannelPaginationAction;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThreadServiceTest {
    private final JDA jda = mock(JDA.class);
    private final ThreadService service = new ThreadService(jda);
    private static final String CHANNEL = "345678901234567890";
    private static final String THREAD = "456789012345678901";

    @Test
    void threadInfoReportsAnUnavailableParentWithoutFailing() {
        ThreadChannel thread = thread(THREAD, true);
        when(thread.getType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        when(jda.getThreadChannelById(THREAD)).thenReturn(thread);
        assertThat(service.getThreadInfo(THREAD)).contains("Parent channel: unknown");
    }

    @Test
    void listsActiveAndArchivedPublicThreadsOnceAndBoundsArchivedLookup() {
        TextChannel parent = parent();
        ThreadChannel active = thread("111111111111111111", false);
        ThreadChannel archived = thread(THREAD, true);
        when(parent.getThreadChannels()).thenReturn(List.of(active));
        ThreadChannelPaginationAction lookup = mock(ThreadChannelPaginationAction.class);
        when(parent.retrieveArchivedPublicThreadChannels()).thenReturn(lookup);
        when(lookup.limit(100)).thenReturn(lookup);
        when(lookup.complete()).thenReturn(List.of(active, archived));
        assertThat(service.listThreads(CHANNEL)).contains("Retrieved 2 threads", "ID: " + THREAD, "Archived: true");
        verify(lookup).limit(100);
    }

    @Test
    void retainsActiveThreadsWhenArchivedPermissionIsMissing() {
        TextChannel parent = parent();
        ThreadChannel active = thread(THREAD, false);
        when(parent.getThreadChannels()).thenReturn(List.of(active));
        InsufficientPermissionException denied = mock(InsufficientPermissionException.class);
        when(denied.getPermission()).thenReturn(Permission.MESSAGE_HISTORY);
        when(parent.retrieveArchivedPublicThreadChannels()).thenThrow(denied);
        assertThat(service.listThreads(CHANNEL)).contains("Retrieved 1 threads", "Archived threads were not listed");
    }

    @Test
    void threadInfoReportsParentAndUnknownOwner() {
        parent();
        IThreadContainerUnion parent = mock(IThreadContainerUnion.class);
        when(parent.getName()).thenReturn("general");
        when(parent.getId()).thenReturn(CHANNEL);
        ThreadChannel thread = thread(THREAD, true);
        when(thread.getParentChannel()).thenReturn(parent);
        when(thread.getType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        when(jda.getThreadChannelById(THREAD)).thenReturn(thread);
        assertThat(service.getThreadInfo(THREAD)).contains("Parent channel: #general", "Owner: unknown", "Archived: true");
    }

    @Test
    void capsActiveThreadOutputAndReportsTheTotal() {
        TextChannel parent = parent();
        ThreadChannel active = thread(THREAD, false);
        ThreadChannel last = thread("556789012345678901", false);
        when(last.getName()).thenReturn("omitted thread");
        java.util.ArrayList<ThreadChannel> threads = new java.util.ArrayList<>(java.util.Collections.nCopies(100, active));
        threads.add(last);
        when(parent.getThreadChannels()).thenReturn(threads);
        ThreadChannelPaginationAction lookup = mock(ThreadChannelPaginationAction.class);
        when(parent.retrieveArchivedPublicThreadChannels()).thenReturn(lookup);
        when(lookup.limit(100)).thenReturn(lookup);
        when(lookup.complete()).thenReturn(List.of());
        assertThat(service.listThreads(CHANNEL))
                .contains("Retrieved 101 threads", "Showing first 100 of 101 threads.")
                .doesNotContain("omitted thread");
    }

    @Test
    void missingArchivedThreadExplainsDiscoveryStep() {
        assertThatThrownBy(() -> service.getThreadInfo(THREAD)).hasMessageContaining("call list_threads on its parent channel first");
        assertThatThrownBy(() -> service.listThreads(CHANNEL)).isInstanceOf(IllegalArgumentException.class);
    }

    private TextChannel parent() {
        TextChannel parent = mock(TextChannel.class);
        when(parent.getName()).thenReturn("general");
        when(parent.getId()).thenReturn(CHANNEL);
        when(jda.getGuildChannelById(CHANNEL)).thenReturn(parent);
        return parent;
    }

    private ThreadChannel thread(String id, boolean archived) {
        ThreadChannel thread = mock(ThreadChannel.class);
        when(thread.getId()).thenReturn(id);
        when(thread.getName()).thenReturn("discussion");
        when(thread.isArchived()).thenReturn(archived);
        when(thread.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-11-12T00:00:00Z"));
        return thread;
    }
}
