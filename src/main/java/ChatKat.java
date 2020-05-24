
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                                            String guildID = "g" + message.getGuild().block().getId().asString(),
                                                    channelID = "c" + message.getChannelId().asString(),
                                                    authorID = "a" + message.getAuthorAsMember().block().getId().asString();
                                            //
                                            // add to write batch
                                            batchPoints.point(Point.measurement(guildID)
                                                    .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                                                    .tag("channelID", channelID)
                                                    .tag("authorID", authorID)
                                                    // temporary for testing/debug only
                                                    .addField("text", message.getContent().get())
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
                        // fetch guildID, channelID, authorID as strings to use as measurement and tag values
                        // **numerical strings break the query, so prepend character to each
                        String guildID = "g" + message.getGuild().block().getId().asString(),
                                channelID = "c" + message.getChannelId().asString(),
                                authorID = "a" + message.getAuthorAsMember().block().getId().asString();
                        //
                        // add message data Point to batch
                        batchPoints.point(Point.measurement(guildID)
                                .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                                .tag("channelID", channelID)
                                .tag("authorID", authorID)
                                .addField("text", message.getContent().get())
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
                        // fetch channel
                        MessageChannel channel = message.getChannel().block();
                        //
                        // fetch guildID, channelID as strings to use in query
                        // numerical strings break the query, so prepend character to each
                        String guildID = "g" + message.getGuild().block().getId().asString(),
                                channelID = "c" + message.getChannelId().asString();
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
                        QueryResult queryResult = influxDB.query(new Query("SELECT count(*) FROM " + guildID
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
                                .map(series -> client.getMemberById(Snowflake.of(guildID.substring(1)),
                                        Snowflake.of(series.getTags().get("authorID").substring(1))).block().getMention()
                                        + " sent *" + series.getValues().get(0).get(1).toString().split("\\.")[0] + "* messages.")
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
            // Get an event dispatcher for readyEvents emitted when the bot logs in
            client.getEventDispatcher().on(ReadyEvent.class)
                    .subscribe(event -> client.getSelf().map(User::getUsername).subscribe(bot ->
                            log.info(String.format("Logged in as %s.", bot))));
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

            // ?? DURING LAUNCH
            //    ?? - logic to look for existing image, if present, and load it onto the DB
            // ON LAUNCH
            //     ?? - client.updatePresence(Presence.invisible())
            //      - BLOCK REQUEST PARSER UNTIL AFTER BACKFILL
            //      - BACKFILL AVAILABLE TEXT CHANNELS & OPEN MESSAGE COUNTER. IF DB exists,
            //      - query db for most recent point for each channel and backfill after that
            //      - write batchpoints after backfill
            //      - when batchpoints write completes for all channels open request parser
            // ON ALL MESSAGES FROM CHANNEL
            //      FILTER OUT BOT MESSAGES, MESSAGES WITHOUT AUTHOR, MESSAGES WITHOUT CONTENT
            //      - add message to batchPoints
            //      - send message to request parser (handle the blocking elsewhere, this function
            //             shouldn't need to know if the parser is open for bisnasty)
            // INSIDE REQUEST PARSER - ON MESSAGES IDENTIFIED AS REQUESTS
            //      IF BLOCKED BY BACKFILL IN CHANNEL (OR IF GUILD REQUEST IN GUILD CO-CHANNEL
            //          - IGNORE
            //      ELSE
            //      - check "requests" table (which we should make with a different batchwriter but n
            //          not really batchwriter because we should write each one as we get it
            //      - use retention policy to rate limit per channel/ user, use field value to
            //            send an explanation *once* per channel or per user (same user, second channel is still no)
            //          - ignore all subsequent calls from user/channel until record expires.
            //      ELSE
            //      -Output
            // ON MESSAGE DELETION (ALSO CHECK IF MESSAGE EDIT TO NO CONTENT CAN BE DIFFERENT JUST IN CASE)
            //      - if deleted message is older than 1 year, decrement the aggregate of that measurement/series
            //      - if not, convert snowflake to timestamp & delete series entry for that timestamp (use full
            //          series information for that delete, just in case. I know the snowflakes are unique but i'm not
            //          sure if they convert to unique EpochMilli, so channel/measurement isn't enough (need author)
            // ?? QUICK GOOD FUN ??
            // ??   - if Billob, Solohan, or Bill enter a voicechannel, use RNG w/ 1/100 chance to enter voicechannel
            // ??   - and play Never Going to Give You Up
