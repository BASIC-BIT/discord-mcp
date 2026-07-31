package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;

@Service
public class MessageService {

    private final JDA jda;

    /**
     * The only directory {@code send_file} may read local paths from, and the only one
     * {@code download_attachment} may write to. Unset disables local paths entirely.
     * Package-private so tests can set it without Spring.
     */
    @Value("${DISCORD_MCP_FILE_ROOT:}")
    String fileRoot;

    public MessageService(JDA jda) {
        this.jda = jda;
    }

    /**
     * Helper method to get a MessageChannel by ID, checking both text channels and thread channels.
     */
    private MessageChannel getMessageChannelById(String channelId) {
        // First try text channel
        TextChannel textChannel = jda.getTextChannelById(channelId);
        if (textChannel != null) {
            return textChannel;
        }
        // Then try news/announcement channel
        NewsChannel newsChannel = jda.getNewsChannelById(channelId);
        if (newsChannel != null) {
            return newsChannel;
        }
        // Then try thread channel
        ThreadChannel threadChannel = jda.getThreadChannelById(channelId);
        if (threadChannel != null) {
            return threadChannel;
        }
        return null;
    }

    /**
     * Sends a message to a specified Discord channel.
     *
     * @param channelId The ID of the channel where the message will be sent.
     * @param message   The content of the message to be sent.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_message", description = "Send a message to a specific channel")
    public String sendMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Message content") String message) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message sentMessage = channel.sendMessage(message).complete();
        return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    /**
     * Sends a file (attachment) to a specified Discord channel.
     *
     * @param channelId The ID of the channel where the file will be sent.
     * @param filePath  Absolute path to a local file to upload.
     * @param fileUrl   Direct URL to a file to upload (alternative to filePath).
     * @param fileData  File contents as base64 (Data URI or raw) to upload (alternative to filePath).
     * @param fileName  File name to use for base64 input (and to override the name otherwise).
     * @param message   Optional text content to accompany the file.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_file", description = "Send a file (attachment) to a specific channel. Provide the file as a local filePath OR a direct fileUrl OR base64 fileData (with fileName). Optionally include a text message. Max 25MB")
    public String sendFile(@ToolParam(description = "Discord channel ID") String channelId,
                           @ToolParam(description = "Absolute path to a local file to upload", required = false) String filePath,
                           @ToolParam(description = "Direct URL to a file to upload (alternative to filePath)", required = false) String fileUrl,
                           @ToolParam(description = "File contents as base64 Data URI or raw base64 (alternative to filePath; requires fileName)", required = false) String fileData,
                           @ToolParam(description = "File name to use for base64 fileData, or to override the upload name", required = false) String fileName,
                           @ToolParam(description = "Optional text message to accompany the file", required = false) String message) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }

        ResolvedFile resolvedFile = resolveFile(filePath, fileUrl, fileData, fileName);
        if (resolvedFile.bytes().length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("File exceeds 25 MB limit (" + (resolvedFile.bytes().length / (1024 * 1024)) + " MB). Discord rejects larger uploads for standard bots.");
        }

        FileUpload fileUpload = FileUpload.fromData(resolvedFile.bytes(), resolvedFile.name());
        Message sentMessage;
        if (message != null && !message.isEmpty()) {
            sentMessage = channel.sendFiles(fileUpload).setContent(message).complete();
        } else {
            sentMessage = channel.sendFiles(fileUpload).complete();
        }
        return "File sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    private record ResolvedFile(byte[] bytes, String name) {
    }

    // Discord rejects standard-bot uploads above 25 MB.
    private static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

    private ResolvedFile resolveFile(String filePath, String fileUrl, String fileData, String fileName) {
        boolean hasPath = filePath != null && !filePath.isEmpty();
        boolean hasUrl = fileUrl != null && !fileUrl.isEmpty();
        boolean hasData = fileData != null && !fileData.isEmpty();

        int provided = (hasPath ? 1 : 0) + (hasUrl ? 1 : 0) + (hasData ? 1 : 0);
        if (provided == 0) {
            throw new IllegalArgumentException("One of 'filePath', 'fileUrl', or 'fileData' (base64) must be provided");
        }
        if (provided > 1) {
            throw new IllegalArgumentException("Provide only one of 'filePath', 'fileUrl', or 'fileData', not multiple");
        }

        boolean hasName = fileName != null && !fileName.isEmpty();
        if (hasPath) {
            return readLocalFile(filePath, hasName ? fileName : null);
        }
        if (hasUrl) {
            return new ResolvedFile(downloadFile(fileUrl), hasName ? fileName : extractFileNameFromUrl(fileUrl));
        }
        if (!hasName) {
            throw new IllegalArgumentException("'fileName' is required when providing base64 'fileData'");
        }
        return new ResolvedFile(decodeBase64(fileData), fileName);
    }

    private ResolvedFile readLocalFile(String filePath, String overrideName) {
        Path path = resolveWithinAllowedRoot(filePath);
        try {
            // Bounded read: readAllBytes on an attacker-chosen path would OOM the
            // JVM long before the size check below could reject it.
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_UPLOAD_BYTES + 1);
            }
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("File exceeds the 25 MB limit. Discord rejects larger uploads for standard bots.");
            }
            String name = overrideName != null ? overrideName : path.getFileName().toString();
            return new ResolvedFile(bytes, name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read file at filePath: " + e.getMessage());
        }
    }

    /**
     * Confine local file reads to an allowlisted root.
     *
     * <p>Without this, send_file with an absolute filePath will read anything the process can
     * read and post it to Discord. On a host where the service loads secrets from the
     * environment, that is a one-call credential exfiltration path reachable by prompt
     * injection. Set DISCORD_MCP_FILE_ROOT to the only directory uploads may come from.
     *
     * <p>Unset means local filePath uploads are refused entirely. That is the safe default:
     * callers can still use fileUrl or base64 fileData.
     *
     * @return the fully resolved real path, which the caller must read instead of the
     * caller-supplied one
     */
    private Path resolveWithinAllowedRoot(String filePath) {
        Path allowed = allowedRoot();
        Path real;
        try {
            // toRealPath, not normalize: normalize is purely lexical, so a symlink
            // inside the root pointing at /etc/shadow passes a prefix check on the
            // normalized path. Both sides must be resolved for the comparison to mean
            // anything, and the resolved path is what gets opened.
            real = Paths.get(filePath).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("File not found at filePath: " + filePath);
        }
        if (!real.startsWith(allowed) || real.equals(allowed)) {
            throw new IllegalArgumentException("filePath is outside the allowed upload directory");
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("filePath is not a regular file: " + filePath);
        }
        return real;
    }

    private Path allowedRoot() {
        if (fileRoot == null || fileRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "DISCORD_MCP_FILE_ROOT is not set, so local file reads and writes are "
                            + "disabled. Set it to the one directory this server may use. "
                            + "send_file can still take fileUrl or base64 fileData instead.");
        }
        Path root;
        try {
            root = Paths.get(fileRoot).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "DISCORD_MCP_FILE_ROOT does not exist or cannot be resolved: " + fileRoot);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("DISCORD_MCP_FILE_ROOT is not a directory: " + fileRoot);
        }
        // A filesystem root has no name components. Accepting "/" would confine
        // nothing at all and silently re-open the whole vulnerability.
        if (root.getNameCount() == 0) {
            throw new IllegalArgumentException("DISCORD_MCP_FILE_ROOT must not be a filesystem root");
        }
        return root;
    }

    private byte[] downloadFile(String url) {
        // Delegated to the shared guard: https only, public host, no redirect
        // following, bounded read. An unguarded fetch here would be an SSRF
        // vector reachable by any MCP client.
        return RemoteFetchGuard.fetch(url, MAX_UPLOAD_BYTES, "file");
    }

    private byte[] decodeBase64(String data) {
        String base64Data;
        if (data.startsWith("data:")) {
            int commaIndex = data.indexOf(',');
            if (commaIndex == -1) {
                throw new IllegalArgumentException("Invalid Data URI format. Expected: data:<mime>;base64,<data>");
            }
            base64Data = data.substring(commaIndex + 1);
        } else {
            base64Data = data;
        }
        try {
            return Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 fileData: " + e.getMessage());
        }
    }

    private String extractFileNameFromUrl(String url) {
        String path = url;
        int queryIndex = path.indexOf('?');
        if (queryIndex != -1) {
            path = path.substring(0, queryIndex);
        }
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash != -1 ? path.substring(lastSlash + 1) : path;
        return name.isEmpty() ? "file" : name;
    }

    /**
     * Edits an existing message in a specified Discord channel.
     *
     * @param channelId  The ID of the channel containing the message.
     * @param messageId  The ID of the message to be edited.
     * @param newMessage The new content for the message.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_message", description = "Edit a message from a specific channel")
    public String editMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Specific message ID") String messageId,
                              @ToolParam(description = "New message content") String newMessage) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (newMessage == null || newMessage.isEmpty()) {
            throw new IllegalArgumentException("newMessage cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        Message editedMessage = messageById.editMessage(newMessage).complete();
        return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
    }

    /**
     * Deletes a message from a specified Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to be deleted.
     * @return A confirmation message indicating the message was deleted successfully.
     */
    @Tool(name = "delete_message", description = "Delete a message from a specific channel")
    public String deleteMessage(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Specific message ID") String messageId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        messageById.delete().queue();
        return "Message deleted successfully";
    }

    /**
     * Reads message history from a specified Discord channel.
     *
     * @param channelId The ID of the channel from which to read messages.
     * @param count     Optional number of messages to retrieve (default is 100, max is 100).
     * @param before    Optional message ID to fetch messages before this message.
     * @param after     Optional message ID to fetch messages after this message.
     * @param around    Optional message ID to fetch messages around this message.
     * @return A formatted string containing the retrieved messages.
     */
    @Tool(name = "read_messages", description = "Read message history from a specific channel, optionally paginated with before/after/around")
    public String readMessages(@ToolParam(description = "Discord channel ID") String channelId,
                               @ToolParam(description = "Number of messages to retrieve (1-100)", required = false) String count,
                               @ToolParam(description = "Message ID to fetch messages before this message", required = false) String before,
                               @ToolParam(description = "Message ID to fetch messages after this message", required = false) String after,
                               @ToolParam(description = "Message ID to fetch messages around this message", required = false) String around) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        int limit = parseMessageLimit(count);
        validateCursorParameters(before, after, around);

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        List<Message> messages;
        if (isProvided(before)) {
            messages = channel.getHistoryBefore(before, limit).complete().getRetrievedHistory();
        } else if (isProvided(after)) {
            messages = channel.getHistoryAfter(after, limit).complete().getRetrievedHistory();
        } else if (isProvided(around)) {
            messages = channel.getHistoryAround(around, limit).complete().getRetrievedHistory();
        } else {
            messages = channel.getHistory().retrievePast(limit).complete();
        }
        List<String> formatedMessages = formatMessages(messages);
        return "**Retrieved " + messages.size() + " messages:** \n" + String.join("\n", formatedMessages);
    }

    private int parseMessageLimit(String count) {
        if (count == null || count.isBlank()) {
            return 100;
        }

        int limit;
        try {
            limit = Integer.parseInt(count);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("count must be an integer between 1 and 100");
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("count must be between 1 and 100");
        }
        return limit;
    }

    private void validateCursorParameters(String before, String after, String around) {
        if (before != null && before.isBlank()) {
            throw new IllegalArgumentException("before cannot be blank");
        }
        if (after != null && after.isBlank()) {
            throw new IllegalArgumentException("after cannot be blank");
        }
        if (around != null && around.isBlank()) {
            throw new IllegalArgumentException("around cannot be blank");
        }

        int providedCursors = (isProvided(before) ? 1 : 0)
                + (isProvided(after) ? 1 : 0)
                + (isProvided(around) ? 1 : 0);
        if (providedCursors > 1) {
            throw new IllegalArgumentException("before, after, and around are mutually exclusive; provide only one");
        }
    }

    private boolean isProvided(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Adds a reaction (emoji) to a specific message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to which the reaction will be added.
     * @param emoji     The emoji to add as a reaction (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message that was reacted to.
     */
    @Tool(name = "add_reaction", description = "Add a reaction (emoji) to a specific message")
    public String addReaction(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Discord message ID") String messageId,
                              @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.addReaction(Emoji.fromUnicode(emoji)).queue();
        return "Added reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Removes a specified reaction (emoji) from a message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message from which the reaction will be removed.
     * @param emoji     The emoji to remove from the message (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message.
     */
    @Tool(name = "remove_reaction", description = "Remove a specified reaction (emoji) from a message")
    public String removeReaction(@ToolParam(description = "Discord channel ID") String channelId,
                                 @ToolParam(description = "Discord message ID") String messageId,
                                 @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.removeReaction(Emoji.fromUnicode(emoji)).queue();
        return "Removed reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Retrieves attachment metadata from a specific message in a Discord channel.
     *
     * @param channelId    The ID of the channel containing the message.
     * @param messageId    The ID of the message to retrieve attachments from.
     * @param attachmentId Optional ID of a specific attachment (if omitted, returns all).
     * @return A formatted string containing attachment metadata.
     */
    @Tool(name = "get_attachment", description = "Get attachment metadata (filename, size, content type, URLs) from a specific message. Returns info only, does not download files.")
    public String getAttachment(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Discord message ID") String messageId,
                                @ToolParam(description = "Specific attachment ID (omit to get all attachments)", required = false) String attachmentId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }

        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return "This message has no attachments.";
        }

        if (attachmentId != null && !attachmentId.isEmpty()) {
            Message.Attachment attachment = attachments.stream()
                    .filter(a -> a.getId().equals(attachmentId))
                    .findFirst()
                    .orElse(null);
            if (attachment == null) {
                throw new IllegalArgumentException("Attachment not found by attachmentId");
            }
            return formatAttachmentDetail(attachment);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Found ").append(attachments.size()).append(" attachment(s):**\n");
        for (Message.Attachment attachment : attachments) {
            sb.append(formatAttachmentDetail(attachment)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Downloads a message's attachments into the allowed file directory.
     *
     * <p>Deliberately takes IDs rather than a URL. The URLs are resolved here, from Discord,
     * so this is not a general fetch-anything-to-disk tool and cannot be pointed at the host's
     * private network. It also sidesteps the reason a caller would want one: Discord CDN links
     * are signed and expire, so a URL copied out of an earlier tool result is usually dead by
     * the time anyone tries to use it. Re-resolving from the message is always fresh.
     *
     * @param channelId    The ID of the channel containing the message.
     * @param messageId    The ID of the message to download attachments from.
     * @param attachmentId Optional ID of a specific attachment (if omitted, downloads all).
     * @return A formatted list of the saved file paths.
     */
    @Tool(name = "download_attachment", description = "Download a message's attachments to the server's allowed file directory (DISCORD_MCP_FILE_ROOT) and return the saved paths. Use get_attachment instead if you only need metadata. Max 25MB per file.")
    public String downloadAttachment(@ToolParam(description = "Discord channel ID") String channelId,
                                     @ToolParam(description = "Discord message ID") String messageId,
                                     @ToolParam(description = "Specific attachment ID (omit to download all)", required = false) String attachmentId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        // Resolve the root before spending any network calls, so a misconfigured
        // root fails immediately instead of after downloading 25 MB.
        Path root = allowedRoot();

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }

        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return "This message has no attachments.";
        }
        if (attachmentId != null && !attachmentId.isEmpty()) {
            attachments = attachments.stream()
                    .filter(a -> a.getId().equals(attachmentId))
                    .toList();
            if (attachments.isEmpty()) {
                throw new IllegalArgumentException("Attachment not found by attachmentId");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Downloaded ").append(attachments.size()).append(" attachment(s) to ")
                .append(root).append(":**\n");
        long budget = MAX_DOWNLOAD_BUDGET_BYTES;
        for (Message.Attachment attachment : attachments) {
            byte[] bytes = RemoteFetchGuard.fetch(attachment.getUrl(), MAX_UPLOAD_BYTES, "attachment");
            budget -= bytes.length;
            if (budget < 0) {
                throw new IllegalArgumentException(
                        "Message attachments exceed the " + (MAX_DOWNLOAD_BUDGET_BYTES / (1024 * 1024))
                                + " MB total download limit. Pass attachmentId to fetch them one at a time.");
            }
            Path saved = writeIntoAllowedRoot(root, attachment.getId(), attachment.getFileName(), bytes);
            sb.append("- `").append(saved).append("` (").append(formatFileSize(bytes.length)).append(")\n");
        }
        return sb.toString().trim();
    }

    // One message can carry ten attachments, so the per-file cap alone would allow a
    // quarter-gigabyte write from a single call. This is a small droplet.
    private static final long MAX_DOWNLOAD_BUDGET_BYTES = 50L * 1024 * 1024;

    /**
     * Writes one attachment into the allowed root under a name derived from Discord's.
     *
     * <p>The filename comes from whoever uploaded the file, so it is untrusted: it is reduced to
     * a single path component here rather than resolved, because {@code root.resolve("../x")}
     * happily escapes. The attachment ID prefix makes the name collision-free without an
     * overwrite flag — a re-download of the same attachment writes the same bytes to the same
     * place.
     */
    // Package-private so tests can exercise the untrusted-filename cases without a live message.
    Path writeIntoAllowedRoot(Path root, String attachmentId, String fileName, byte[] bytes) {
        Path target = root.resolve(attachmentId + "-" + sanitizeFileName(fileName));
        if (!target.getParent().equals(root)) {
            // Unreachable given sanitizeFileName, and worth failing loudly if that ever changes.
            throw new IllegalArgumentException("Refusing to write outside the allowed directory");
        }
        try {
            // Unlink first, then CREATE_NEW: an ordinary truncating write follows a symlink,
            // so an existing link at this path would redirect the bytes to its target.
            // deleteIfExists removes the link itself, never what it points at.
            Files.deleteIfExists(target);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to save attachment: " + e.getMessage());
        }
        return target;
    }

    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment";
        }
        // Allowlist, not a blocklist of separators: this has to hold on both POSIX and
        // Windows, where '\' and ':' are also separators and device names are reserved.
        String cleaned = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        // A leading dot would produce a hidden file; a name of only dots would resolve
        // to the directory itself.
        cleaned = cleaned.replaceAll("^\\.+", "");
        if (cleaned.isBlank()) {
            return "attachment";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    private String formatAttachmentDetail(Message.Attachment attachment) {
        return String.format(
                "- %s\n  Proxy URL: %s",
                formatAttachmentSummary(attachment),
                attachment.getProxyUrl()
        );
    }

    private String formatAttachmentSummary(Message.Attachment attachment) {
        return String.format(
                "(Attachment ID: %s) `%s` (%s, %s) URL: %s",
                attachment.getId(),
                attachment.getFileName(),
                formatFileSize(attachment.getSize()),
                attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                attachment.getUrl()
        );
    }

    private List<String> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    String authorName = m.getAuthor().getName();
                    String authorId = m.getAuthor().getId();
                    String timestamp = m.getTimeCreated().toString();
                    String content = m.getContentDisplay();
                    String msgId = m.getId();

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format(
                            "- (ID: %s) **[%s]** (Author ID: %s) `%s`: ```%s```",
                            msgId,
                            authorName,
                            authorId,
                            timestamp,
                            content
                    ));

                    List<Message.Attachment> attachments = m.getAttachments();
                    if (!attachments.isEmpty()) {
                        sb.append("\n  Attachments:");
                        for (Message.Attachment attachment : attachments) {
                            sb.append("\n    - ").append(formatAttachmentSummary(attachment));
                        }
                    }

                    return sb.toString();
                }).toList();
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
