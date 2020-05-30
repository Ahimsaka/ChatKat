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
import discord4j.core.object.util.Snowflake;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/* InfluxDB: See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
  Note: influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
   this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
   For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered. */
public class ChatKat {
    public static void main(String[] args) {
        final Logger log = LoggerFactory.getLogger(ChatKat.class);

        Properties properties = new Properties();
        // Stream config.properties file to collect database name, user, password, and URL strings
        try (InputStream propStream = new FileInputStream("src" + File.separator + "main" + File.separator + "resources" + File.separator + "config.properties")) {
            properties.load(propStream);
        } catch (IOException e) {
            log.error(e.getMessage());
            return;
        }

        // setup database handler convenience class
        final class DatabaseHandler {
            InfluxDB influxDB;
            BatchPoints batchPoints;
            // create HashMap for parsing time parameters
            HashMap<String, Instant> setInterval = new HashMap<>(4){{
                put("year", ZonedDateTime.now().minusYears(1).toInstant());
                put("month", ZonedDateTime.now().minusMonths(1).toInstant());
                put("week", ZonedDateTime.now().minusWeeks(1).toInstant());
                put("day", ZonedDateTime.now().minusDays(1).toInstant());
            }};

            DatabaseHandler() {
                // initialize database connection
                this.influxDB = InfluxDBFactory.connect(properties.getProperty("serverURL"),
                        properties.getProperty("databaseUser"),
                        properties.getProperty("databasePass"));
                // idempotent query creates database if it doesn't exist
                this.influxDB.query(new Query("CREATE DATABASE " + properties.getProperty("databaseName")));
                this.influxDB.setDatabase(properties.getProperty("databaseName"));
                // enable writing data in batches & set up exception handler to log failed data points
                this.influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler(
                        (failedPoints, throwable) -> failedPoints.forEach(p -> {
                            log.error(p.toString());
                        }))
                );
                // initialize BatchPoints object for batch writes.
                this.batchPoints = BatchPoints.database(properties.getProperty("databaseName")).build();
            }
            // convenience method to avoid manipulating object properties directly
            private void writeBatch() {
                try {
                    this.influxDB.write(this.batchPoints);
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
            // convenience method for creating username or tag reference in query output string
            private String getUserLabel(String authorID, DiscordClient client, Snowflake guildID, Boolean setTags) {
                return client.getUserById(Snowflake.of(authorID)).map(author -> {
                    if (setTags) {
                        return author.getMention();
                    } try {
                        return author.asMember(guildID).block().getDisplayName();
                    } catch (Exception e) {
                        log.error(e.getMessage());
                        return author.getUsername();
                    }
                }).block();
            }

            private Message addMessage(Message message) {
                /* fetch channelID, authorID as strings to use as and tag values
                   **numerical strings break the query, so prepend character to each */
                    String channelID = "c" + message.getChannelId().asString(),
                        authorID = "a" + message.getAuthor().get().getId().asString();
                // add message data to batch
                this.batchPoints.point(Point.measurement("messages")
                        .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                        .tag("channelID", channelID)
                        .tag("authorID", authorID)
                        .addField("isValid", 1)
                        .build());
                return message;
            }
            private void deleteMessage(MessageDeleteEvent event) {
                long messageTime = event.getMessageId().getTimestamp().toEpochMilli();
                String channelID = "c" + event.getChannelId().asString();

                // discord4j doesn't serve authorID with delete events. fetch it from the database.
                String authorID = this.influxDB.query(new Query("SELECT \"isValid\", \"authorID\" FROM messages"
                        + " WHERE channelID = '" + channelID + "'"
                        + " AND time = " + messageTime + "ms")).getResults().get(0).getSeries().get(0).getValues().get(0).get(2).toString();

                // lazy delete. wait to write to database until this.queryDatabase()
                this.batchPoints.point(Point.measurement("messages")
                        .time(messageTime, TimeUnit.MILLISECONDS)
                        .tag("channelID", channelID)
                        .tag("authorID", authorID)
                        .addField("isValid", 0)
                        .build());
            }


            private Message queryDatabase(Message message, DiscordClient client, Mono<MessageChannel> channelMono) {
                MessageChannel channel = channelMono.block();
                // write batchPoints to clear cache - ensures that all messages up to request will be included
                this.writeBatch();
                // fetch channelID to use in query
                String channelID = "c" + message.getChannelId().asString();
                // get guildId to get author's guild nickname/tag
                Snowflake guildID = message.getGuild().block().getId();
                // get params, if any
                String request = message.getContent().get();

                // set default @mention option - true except if debug
                boolean setTags = false;
                // set default interval (long 0 will be interpreted as Epoch time 0 which is like 1970)
                long interval = 0;
                class Counter {
                    int value;
                    Counter() {
                        value = 0;
                    }
                    int inc() {
                        this.value = this.value + 1;
                        return this.value;
                    }
                    String val() {
                        return String.valueOf(this.value);
                    }
                }
                /* loop through the input string for time params or 'nt' (no tags)
                   in case of multiple time param matches, the last input will be used. */
                for (String s : request.split("-")) {
                    if (this.setInterval.containsKey(s.toLowerCase()))
                        interval = this.setInterval.get(s.toLowerCase()).toEpochMilli();
                    else if (s.toLowerCase().startsWith("tag")) {
                        setTags = true;
                    }
                }
                Counter i = new Counter();
                StringBuilder output = new StringBuilder();
                final boolean tags = setTags;

                this.influxDB.query(
                        new Query("SELECT sum(\"isValid\")"
                        + " FROM messages"
                        + " WHERE channelID = '" + channelID + "'"
                        + "AND time >= " + interval + "ms"
                        + " GROUP BY authorID"
                )).getResults().get(0).getSeries().stream()
                        .sorted((seriesA, seriesB) -> (int)((Double) seriesB.getValues().get(0).get(1) - (Double) seriesA.getValues().get(0).get(1)))
                        .forEachOrdered(series -> {
                            i.inc();

                            output.append(i.val()).append(".").append(new String(new char[5 - i.val().length()]).replace("\0", " "))
                            .append(this.getUserLabel(series.getTags().get("authorID").substring(1), client, guildID, tags))
                            .append(" sent **") // surround # output with ** to bold the text
                            // append # of messages found
                            .append(series.getValues().get(0).get(1).toString().split("\\.")[0])
                            .append("** messages. \n");
                        });
                log.info(output.toString());
                return channel.createMessage(output.toString()).block();
            }

            public void close() {
                this.writeBatch();
                this.influxDB.close();
            }
        }

        // initialize DatabaseHandler object and DiscordClient
        final DatabaseHandler databaseHandler = new DatabaseHandler();
        final DiscordClient client = DiscordClientBuilder.create(System.getenv("BOT_TOKEN")).build();

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
                        return (permissions.contains(Permission.READ_MESSAGE_HISTORY) && permissions.contains(Permission.SEND_MESSAGES) && permissions.contains(Permission.VIEW_CHANNEL));
                    })
                    .map(guildChannel -> (TextChannel) guildChannel)
                    .flatMap(textChannel -> textChannel.getMessagesBefore(Snowflake.of(Instant.now())))
                    .filter(message -> !message.getAuthor().get().isBot() && message.getContent().isPresent() && message.getAuthor().isPresent())
                    .subscribe(databaseHandler::addMessage);

            client.getEventDispatcher().on(MessageCreateEvent.class)
                    .map(MessageCreateEvent::getMessage)
                    .filter(message -> !message.getAuthor().get().isBot() && message.getContent().isPresent() && message.getAuthor().isPresent())
                    .map(databaseHandler::addMessage)
                    .filter(message -> message.getContent().get().toLowerCase().startsWith("&kat")) // if message is a request:
                    .subscribe(message -> databaseHandler.queryDatabase(message, client, message.getChannel()));

            // get eventDispatcher for deleted messages to remove them from the database
            client.getEventDispatcher().on(MessageDeleteEvent.class)
                    .subscribe(databaseHandler::deleteMessage);

            // log client in and block so that main thread doesn't exit until instructed.
            client.login().block();
        } catch (Exception e) {
            log.error(e.toString());
        } finally {
            log.info("Closing up shop.");
            client.logout();
            databaseHandler.close();
        }
    }
}