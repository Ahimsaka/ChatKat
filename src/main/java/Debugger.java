import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.TextChannel;
import discord4j.core.object.util.Snowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

public class Debugger {
    final Logger log = LoggerFactory.getLogger(ChatKat.class);
    File debugFile;
    BufferedWriter debugWriter;

    Debugger() {
        try {
            debugWriter = new BufferedWriter(new FileWriter("src" + File.separator + "main" + File.separator
                    + "resources" + File.separator + "debug_files" + File.separator
                    + Instant.now().getEpochSecond() + "_debug.csv", true));
            debugWriter.append(
                    "GuildID,"
                            + "GuildName,"
                            + "ChannelID,"
                            + "ChannelName,"
                            + "MessageID,"
                            + "TimeStamp,"
                            + "MessageType,"
                            + "MessageClass,"
                            + "AuthorUsername,"
                            + "AuthorID,"
                            + "Content,"
                            + "Embeds,"
                            + "Attachment,"
                            + "MessageType,"
                            + "MessageClass,"
                            + "EditTimeStamp,"
                            + "HashCode,"
                            + "MessageReference Bool,"
                            + "WebhookID else Epoch Snowflake,"
                            + "\n");
        } catch (IOException e) {
            log.error("Debuggerer launch IOException: " + e.getMessage());
        }
    }
    String stringer(String input) {
        return input
                .replaceAll("\"", "\"\"\"")
                .replaceAll(",", " ")
                .replaceAll("\n", " ")
                + ",";
    }
    void addMessage(Message message) {
        if (message.getContent().isPresent()) {
            Guild guild = message.getGuild().block();
            TextChannel channel = (TextChannel) message.getChannel().block();

            try {
                debugWriter.append(guild.getId().toString() + ","
                        + this.stringer(guild.getName())
                        + this.stringer(channel.getId().toString())
                        + this.stringer(channel.getName())
                        + this.stringer(message.getId().toString())
                        + this.stringer(message.getTimestamp().toString())
                        + this.stringer(message.getType().toString())
                        + this.stringer(message.getClass().toString())
                        + this.stringer(message.getAuthor().get().getUsername())
                        + this.stringer(message.getAuthor().get().getId().toString())
                        + this.stringer(message.getContent().orElse("NO CONTENT"))
                        + this.stringer(message.getEmbeds().toString())
                        + this.stringer(message.getAttachments().toString())
                        + this.stringer(message.getType().toString())
                        + this.stringer(message.getClass().toString())
                        + this.stringer(message.getEditedTimestamp().orElse(Instant.EPOCH).toString())
                        + this.stringer(String.valueOf(message.hashCode()))
                        + this.stringer(String.valueOf(message.getMessageReference().isPresent()))
                        + this.stringer(message.getWebhookId().orElse(Snowflake.of(Instant.EPOCH)).toString())
                        + "\n");
            } catch (IOException e) {
                log.error("debugwriter IOexception: " + e.getMessage());
            }
        }
    }
    void close(){
        try {
            this.debugWriter.flush();
            this.debugWriter.close();
        } catch (IOException e) {
            log.error("DebugWriter Close exception: " + e.getMessage());
        }
    }
}
