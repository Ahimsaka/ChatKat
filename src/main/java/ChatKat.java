import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageDeleteEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.User;

import discord4j.core.object.util.Permission;
import discord4j.core.object.util.PermissionSet;

import java.io.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* InfluxDB: See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
  Note: influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
   this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
   For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered. */

public class ChatKat {

    public static void main(String[] args) {

        final Logger log = LoggerFactory.getLogger(ChatKat.class);

        Properties properties = new Properties();
        // Stream config.properties file to collect database name, user, password, and URL strings
        try (InputStream propStream = new FileInputStream("src" + File.separator + "main" + File.separator
                + "resources" + File.separator + "config.properties")) {
            properties.load(propStream);
        } catch (IOException e) {
            log.error("Error inside properties stream " + e.getMessage());
            return;
        }

        // initialize DatabaseHandler object and DiscordClient
        final DiscordClient client = DiscordClientBuilder.create(System.getenv("BOT_TOKEN")).build();
        final DatabaseHandler databaseHandler = new DatabaseHandler(client, properties);

        try {
            // Get an event dispatcher for readyEvent on login
            client.getEventDispatcher().on(ReadyEvent.class)
                    .subscribe(event -> client.getSelf().map(User::getUsername).subscribe(bot ->
                            log.info(String.format("Connected as %s.", bot))));

            // Get guildCreateEvent dispatcher to back-fill DB
            client.getEventDispatcher().on(GuildCreateEvent.class)
                    .map(GuildCreateEvent::getGuild)
                    .flatMap(Guild::getChannels)
                    .filter(guildChannel -> guildChannel instanceof TextChannel)
                    .filter(guildChannel -> {
                        PermissionSet permissions = guildChannel.getEffectivePermissions(client.getSelfId().get()).block();
                        return (permissions.contains(Permission.READ_MESSAGE_HISTORY) && permissions.contains(Permission.VIEW_CHANNEL));
                    })
                    .map(guildChannel -> (TextChannel) guildChannel)
                    .map(channel -> {
                        log.info(channel.getName());
                        return channel;
                    })
                    .map(databaseHandler::backfillChannel)
                    .subscribe();

            // get MessageCreateEvent dispatcher to count incoming messages.
            client.getEventDispatcher().on(MessageCreateEvent.class)
                    .map(MessageCreateEvent::getMessage)
                    .filter(message -> !message.getAuthor().get().isBot()
                            //&& message.getContent().isPresent()
                            && message.getAuthor().isPresent())
                    .map(databaseHandler::addMessage)
                    .filter(message -> message.getContent().get().toLowerCase().startsWith("&kat"))
                    // if message is a request:
                    .subscribe(databaseHandler::parseRequest);

            // get eventDispatcher for deleted messages to remove them from the database
            client.getEventDispatcher().on(MessageDeleteEvent.class)
                    .subscribe(databaseHandler::deleteMessage);

            // log client in and block so that main thread doesn't exit until instructed.
            client.login().block();
        } catch (Exception e) {
            log.error("Error in out try: " + e.toString());
        } finally {
            log.info("Closing up shop.");
            client.logout();
            databaseHandler.close();
        }
    }
}

