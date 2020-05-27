
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageDeleteEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.User;

import discord4j.core.object.util.Snowflake;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatKat {
    //
    // Initialize logger
    final static Logger log = LoggerFactory.getLogger(ChatKat.class);
    //
    // initialize BatchPoints object to write to database.
    final static BatchPoints batchPoints = BatchPoints.database(System.getenv("databaseName")).build();

    private static void addMessage(Message message) {
        //
        // fetch channelID, authorID as strings to use as and tag values
        // **numerical strings break the query, so prepend character to each
        String channelID = "c" + message.getChannelId().asString(),
                authorID = "a" + message.getAuthorAsMember().block().getId().asString();
        //
        // add message data Point to batch
        batchPoints.point(Point.measurement("messages")
                .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("channelID", channelID)
                .tag("authorID", authorID)
                .addField("isValid", 1)
                .build());
    }
    //
    public static void main(String[] args) {
        /*
        InfluxDB: See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
        Note: influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
              this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
              For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered.
        */
        // Build influxDB client.
        final InfluxDB influxDB = InfluxDBFactory.connect(System.getenv("serverURL"),
                System.getenv("databaseUser"),
                System.getenv("databasePass"));
        //
        // Build DiscordClient
        final DiscordClient client = DiscordClientBuilder.create(System.getenv("TOKEN")).build();
        //
        try {
            //
            // create Database (influxDB create query is idempotent)
            influxDB.query(new Query("CREATE DATABASE " + System.getenv("databaseName")));
            //
            // set the default database to write message points to
            influxDB.setDatabase(System.getenv("databaseName"));
            //
            // enable writing data in batches & set up exception handler to log failed data points
            influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler(
                    (failedPoints, throwable) -> failedPoints.forEach(p -> {
                        log.error(p.toString());
                    }))
            );
            //
            // Get an event dispatcher for readyEvents emitted when the bot logs in
            client.getEventDispatcher().on(ReadyEvent.class)
                    .subscribe(event -> client.getSelf().map(User::getUsername).subscribe(bot ->
                            log.info(String.format("Logged in as %s.", bot))));
            //
            // Get guildCreateEvent dispatcher to back-fill DB
            client.getEventDispatcher().on(GuildCreateEvent.class)
                    .map(GuildCreateEvent::getGuild)
                    .flatMap(Guild::getChannels)
                    .filter(guildChannel -> guildChannel instanceof TextChannel)
                    .map(guildChannel -> (TextChannel) guildChannel)
                    .flatMap(textChannel -> textChannel.getMessagesBefore(Snowflake.of(Instant.now())))
                    .filter(message -> !message.getAuthor().get().isBot() && message.getContent().isPresent())
                    .subscribe(message -> addMessage(message));
            //
            // Process incoming messages
            // Note - in event of timing conflict with the back-fill process, the batchPoints
            //        object will merge the two records, so there's no risk of data duplication.
            client.getEventDispatcher().on(MessageCreateEvent.class)
                    .map(MessageCreateEvent::getMessage)
                    //
                    // we only care about messages with content and non-bot authors.
                    .filter(message -> !message.getAuthor().get().isBot() && message.getContent().isPresent())
                    .map(message -> {
                        addMessage(message);
                        return message;
                    })
                    //
                    // now that the data point is in the write batch, check for request syntax
                    .filter(message -> message.getContent().get().startsWith("&Kat"))
                    //
                    // if the message is a request:
                    .map(message -> {
                        //
                        // write batchPoints to clear cache - ensures that all messages up to request (and possibly
                        // just after) will be included in output
                        influxDB.write(batchPoints);
                        //
                        // fetch channel and display typing status
                        MessageChannel channel = message.getChannel().block();
                        channel.type();
                        //
                        // get params, if any
                        String request = message.getContent().get();
                        //
                        // create HashMap for parsing time parameters
                        HashMap<String, Instant> setInterval = new HashMap<>(4);
                        setInterval.put("year",  ZonedDateTime.now().minusYears(1).toInstant());
                        setInterval.put("month", ZonedDateTime.now().minusMonths(1).toInstant());
                        setInterval.put("week",  ZonedDateTime.now().minusWeeks(1).toInstant());
                        setInterval.put("day",   ZonedDateTime.now().minusDays(1).toInstant());
                        //
                        // set default interval (long 0 will be interpreted as Epoch time 0 which is like 1970)
                        // so no params should return all history.
                        long interval = 0;
                        //
                        // loop through the input string and see if any string following a hyphen
                        // matches a time interval parameter.
                        // in case of multiple matches, the last input will be used.
                        for (String s : request.split("-")) {
                            if (setInterval.containsKey(s.toLowerCase())) interval = setInterval.get(s.toLowerCase()).toEpochMilli();
                        }
                        //
                        // fetch channelID as strings to use in query
                        // numerical strings break the query, so prepend character to each
                        String channelID = "c" + message.getChannelId().asString();
                        //
                        // Query database
                        QueryResult queryResult = influxDB.query(new Query("SELECT sum(\"isValid\") FROM messages"
                                + " WHERE channelID = '" + channelID + "'"
                                + "AND time >= " + interval + "ms"
                                + " GROUP BY authorID"
                        ));
                        //
                        // get guildId to get author's guild nickname
                        Snowflake guildID = message.getGuild().block().getId();
                        //
                        // stream the query results to sort by value and convert to list of strings.
                        // then set them to a list for output
                        List<QueryResult.Series> seriesList = queryResult.getResults().get(0).getSeries();
                        StringBuilder output = new StringBuilder();

                        seriesList.sort((series1, series2)-> series2.getValues().get(0).get(1).toString()
                                        .compareTo(series1.getValues().get(0).get(1).toString()));

                        for (int i = 0; i < seriesList.size(); i++) {
                            output.append(i + 1).append(". ").append(client.getMemberById(guildID,
                                    Snowflake.of(seriesList.get(i).getTags().get("authorID").substring(1))).block().getMention()).append(" sent **").append(seriesList.get(i).getValues().get(0).get(1).toString().split("\\.")[0]).append("** messages. \n");
                        }
                        //
                        // join list with \n delimiter to send as one message, then send.
                        channel.createMessage(output.toString()).subscribe();
                        return Mono.empty();
                    })
                    .onErrorContinue((t, o) -> log.error("Error while processing event", t))
                    .subscribe();
            //
            // get eventDispatcher for deleted messages to remove them from the database
            client.getEventDispatcher().on(MessageDeleteEvent.class)
                    .subscribe(event -> {
                        //
                        // get timestamp as long
                        long messageTime = event.getMessageId().getTimestamp().toEpochMilli();
                        //
                        // get channelID
                        String channelID = "c" + event.getChannelId().asString();
                        //
                        // Lazy Delete
                        String authorID = influxDB.query(new Query("SELECT \"isValid\", \"authorID\" FROM messages"
                                + " WHERE channelID = '" + channelID + "'"
                                + " AND time = " + messageTime + "ms")).getResults().get(0).getSeries().get(0).getValues().get(0).get(2).toString();
                        //
                        // overwrite the point. No need to write this to the database. batchPoints is written
                        // before output is served. (and if the deleted message hasn't been written to the DB yet
                        // then batchPoints will take care of the overwrite internally).
                        batchPoints.point(Point.measurement("messages")
                                .time(messageTime, TimeUnit.MILLISECONDS)
                                .tag("channelID", channelID)
                                .tag("authorID", authorID)
                                .addField("isValid", 0)
                                .build());
                    });

            // log client in and block so that main thread doesn't exit until instructed.
            client.login().block();
        } catch (Exception e) {
            log.error(e.toString());
        } finally {
            client.logout();
            influxDB.write(batchPoints);
            influxDB.close();
        }
    }
}