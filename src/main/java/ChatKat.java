
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
    // create logback logger. the log that logs back, baby!
    private final static Logger log = LoggerFactory.getLogger(ChatKat.class);
    //
    // initialize variables for Discord Client to facilitate cleanup on exit
    private static DiscordClient client;
    //
    // InfluxDB setup. See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
    // Note: influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
    //       this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
    //       For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered.
    //
    private static InfluxDB influxDB;
    private static BatchPoints batchPoints;
    final static String serverURL = "http://127.0.0.1:8086", username = "root", password = "root";
    final static String databaseName = "ChatKat";
    //
    public static void main(String[] args) {
        // do, or do not. there is no
        try {
            //
            // Build InfluxDB client
            influxDB = InfluxDBFactory.connect(serverURL, username, password);
            //
            // create Database (this will not overwrite existing database on restart)
            influxDB.query(new Query("CREATE DATABASE " + databaseName));
            //
            // set the default database to write message points to
            influxDB.setDatabase(databaseName);
            //
            // enable writing data in batches & set up exception handler to log failed data points
            influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler(
                    (failedPoints, throwable) -> failedPoints.forEach(p -> {
                        log.error(p.toString());
                    }))
            );
            //
            // initialize BatchPoints object to aggregate and reduce http api calls
            batchPoints = BatchPoints.database(databaseName).build();
            //
            // Build DiscordClient
            client = DiscordClientBuilder.create(System.getenv("TOKEN")).build();
            //
            // Get an event dispatcher for readyEvents emitted when the bot logs in
            client.getEventDispatcher().on(ReadyEvent.class)
                    .subscribe(event -> client.getSelf().map(User::getUsername).subscribe(bot ->
                            log.info(String.format("Logged in as %s.", bot))));
            //
            // Get guildCreateEvent dispatcher to back-fill DB
            client.getEventDispatcher().on(GuildCreateEvent.class)
                    .map(GuildCreateEvent::getGuild)
                    .map(guild -> guild.getChannels()
                            .filter(guildChannel -> guildChannel instanceof TextChannel)
                            .map(guildChannel -> {
                                TextChannel textChannel = (TextChannel) guildChannel;
                                return textChannel.getMessagesBefore(Snowflake.of(Instant.now()))
                                        .filter(message -> !message.getAuthor().get().isBot() && message.getContent().isPresent())
                                        .map(message -> {
                                            //
                                            // fetch guildID, channelID, authorID as strings to use as measurement and tag values
                                            // numerical strings break the query, so prepend character to each
                                            String channelID = "c" + message.getChannelId().asString(),
                                                    authorID = "a" + message.getAuthorAsMember().block().getId().asString();
                                            //
                                            // add to write batch
                                            batchPoints.point(Point.measurement("messages")
                                                    .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                                                    .tag("channelID", channelID)
                                                    .tag("authorID", authorID)
                                                    .addField("isValid", 1)
                                                    .build());
                                            return message;
                                        })
                                        .collectList().block();
                            }).collectList().block()).subscribe(list -> log.info("First back-fill event emitted"));
            //
            // Process incoming messages
            // Note - in event of timing conflict with the back-fill process, the batchPoints
            //        object will merge the two records, so there's no risk of data duplication.
            client.getEventDispatcher().on(MessageCreateEvent.class)
                    .map(MessageCreateEvent::getMessage)
                    //
                    // we only care about messages with content and non-bot authors.
                    .filter(message -> !message.getAuthor().get().isBot()
                            && message.getContent().isPresent())
                    .map(message -> {
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
                        return message;
                    })
                    //
                    // now that the data point is in the write batch, check for request syntax
                    .filter(message -> message.getContent().get().startsWith("&Kat"))
                    //
                    // if the message is a request:
                    .map(message -> {
                        //
                        // get params, if any
                        String request = message.getContent().get();
                        //
                        // get guildId to get author nickname later
                        Snowflake guildID = message.getGuild().block().getId();
                        //
                        // fetch channel and display typing status
                        MessageChannel channel = message.getChannel().block();
                        channel.type();
                        //
                        // fetch channelID as strings to use in query
                        // numerical strings break the query, so prepend character to each
                        String channelID = "c" + message.getChannelId().asString();
                        //
                        // write batchPoints to clear cache - ensures that all messages up to request (and possibly
                        // just after) will be included in output
                        influxDB.write(batchPoints);
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
                        // Query database
                        QueryResult queryResult = influxDB.query(new Query("SELECT sum(\"isValid\") FROM messages"
                                + " WHERE channelID = '" + channelID + "'"
                                + "AND time >= " + interval + "ms"
                                + " GROUP BY authorID"
                        ));
                        //
                        // stream the query results to sort by value and convert to list of strings.
                        // then set them to a list for output
                        List<String> outputList = queryResult.getResults().get(0).getSeries()
                                .stream()
                                .sorted((series1, series2)-> series2.getValues().get(0).get(1).toString().compareTo(series1.getValues().get(0).get(1).toString()))
                                //
                                // construct output string for each user and collect to list
                                .map(series -> client.getMemberById(guildID,
                                        Snowflake.of(series.getTags().get("authorID").substring(1))).block().getMention()
                                        + " sent **" + series.getValues().get(0).get(1).toString().split("\\.")[0] + "** messages.")
                                .collect(Collectors.toList());
                        //
                        // prepend number to each entry in list for presentation
                        outputList.forEach((entry)->{
                            int i = outputList.indexOf(entry);
                            outputList.set(i, (i+1) + ". " + entry);
                        });
                        //
                        // join list with \n delimiter to send as one message, then send.
                        channel.createMessage(String.join("\n", outputList)).subscribe();
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
                        // Deleting individual points isn't supported. so we will overwrite the point for the deleted
                        // message, changing the "isValid" field to 0 for deleted messages. To overwrite points, we need
                        // to have the full tag set, but Discord does not serve authorID with MessageDeleteEvents.
                        // This ugly block of code retrieves the authorID from the original record.
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