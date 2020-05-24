
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
import java.time.temporal.ChronoUnit;
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
    // and access from event handlers:
    private static DiscordClient client;
    //
    // InfluxDB setup. See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
    // Note: influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
    //       this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
    //       For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered.
    private static InfluxDB influxDB;
    private static BatchPoints batchPoints;
    final static String serverURL = "http://127.0.0.1:8086", username = "root", password = "root";
    final static String databaseName = "ChatKat";
    //
    // you know this one, friend. it's a classic
    public static void main(String[] args) {
        //
        // do, or do not. there is no
        try {
            //
            // Build InfluxDB client
            influxDB = InfluxDBFactory.connect(serverURL, username, password);
            //
            // create data base
            // *** keep an eye on this. it appears that this won't drop existing data if
            // *** the container
            influxDB.query(new Query("CREATE DATABASE " + databaseName));
            //
            // set the default database to write message points to
            influxDB.setDatabase(databaseName);
            //
            // enable writing data in batches & set up exception handler to log failed data points
            // (which do not stop/affect the batch overall)
            // we'll stick with the autogen retention policy because the data is simple and uniform
            // and small enough at observed scales not to be worth aggregating. If that changes
            // consider aggregating to a sum per series after 365 days.
            influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler(
                    (failedPoints, throwable) -> failedPoints.forEach(p -> {
                        log.info(p.toString());
                    }))
            );
            //
            // initialize BatchPoints object to aggregate data points to reduce http api calls
            batchPoints = BatchPoints.database(databaseName)
                    .build();
            //
            // Build DiscordClient
            client = DiscordClientBuilder.create(System.getenv("TOKEN")).build();
            //
            // MESSAGE EVENT ROUTING
            client.getEventDispatcher().on(MessageCreateEvent.class)
                    //
                    // we only care about messages with content and non-bot authors.
                    .filter(event -> //event.getMessage().getAuthor().isPresent() &&
                            //!event.getMessage().getAuthor().get().isBot() &&
                            event.getMessage().getContent().isPresent())
                    .map(event -> {
                        //
                        // fetch message
                        Message message = event.getMessage();
                        //
                        // fetch guildID, channelID, authorID as strings to use as measurement and tag values
                        // numerical strings break the query, so prepend character to each
                        String guildID = "g" + message.getGuild().block().getId().asString(),
                                channelID = "c" + message.getChannelId().asString(),
                                authorID = "a" + message.getAuthorAsMember().block().getId().asString();
                        //
                        // CHECK NOW IF THIS IS A REQUEST AND ADD REQUEST true/false AS A FIELD FOR RATE LIMITING
                        // add message data Point to batch
                        batchPoints.point(Point.measurement(guildID)
                                .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                                .tag("channelID", channelID)
                                .tag("authorID", authorID)
                                // temporary for testing/debug only
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
                        // set interval
                        // ***** UPDATE TO BE VARIABLE BASED ON PARAMS
                        long interval = message.getTimestamp().minus(10, ChronoUnit.MINUTES).toEpochMilli();
                        //
                        // Query database
                        // ***** Update to be variable based on Params.
                        QueryResult queryResult = influxDB.query(new Query("SELECT count(*) FROM " + guildID
                                + " WHERE channelID = '" + channelID + "'"
                                //+ "AND time >= " + interval + "ms"
                                + " GROUP BY authorID"
                        ));
                        //
                        // stream the query results to sort them by value and convert to list of strings.
                        // **** I think that if we add 1 higher level of streaming we can make this
                        // **** logic applicable to full guild & channel results. if not, we'll have to abstract
                        // **** this logic into a helper method.
                        List<String> output = queryResult.getResults().get(0).getSeries()
                                .stream()
                                .sorted((series1, series2)-> series2.getValues().get(0).get(1).toString().compareTo(series1.getValues().get(0).get(1).toString()))
                                //
                                // construct output string for each user and collect to list
                                .map(series -> client.getMemberById(Snowflake.of(guildID.substring(1)), Snowflake.of(series.getTags().get("authorID").substring(1))).block().getMention()
                                        + " " + series.getValues().get(0).get(1).toString().split("\\.")[0])
                                .collect(Collectors.toList());
                        //
                        // prepend number to each entry in list for presentation
                        output.forEach((entry)->{
                            int i = output.indexOf(entry);
                            output.set(i, (i+1) + ". " + entry);
                        });
                        //
                        // join list with \n delimiter to send as one message, then send.
                        channel.createMessage(String.join("\n", output)).subscribe();
                        return Mono.empty();
                    })
                    .onErrorContinue((t, o) -> log.error("Error while processing event", t))
                    .subscribe();
            //
            // Get guildCreateEvent dispatcher to backfill DB
            client.getEventDispatcher().on(GuildCreateEvent.class)
                    .map(GuildCreateEvent::getGuild)
                    .map(guild -> guild)
                    .map(guild -> guild.getChannels()
                            .filter(guildChannel -> guildChannel instanceof TextChannel)
                            .map(guildChannel -> {
                                TextChannel textChannel = (TextChannel) guildChannel;
                                return textChannel.getMessagesBefore(Snowflake.of(Instant.now()))
                                        .filter(message -> //message.getAuthor().isPresent() &&
                                                //!message.getAuthor().get().isBot() &&
                                                message.getContent().isPresent())
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
                            }).collectList().block()).subscribe(list -> log.info("Backfill emitted"));

            //
            // Get an event dispatcher for readyEvents emitted when the bot logs in
            client.getEventDispatcher().on(ReadyEvent.class)
                    //
                    // backfill database before logging client in.
                    // NOTA BENE: influxDB merges identical points, and the timestamps are based on the
                    // the unique snowflakes. as a result, we do NOT need to wait until after backfill
                    // to start adding new messages to the batchPoints
                    //
                    // fetch the # of guilds available to client to determine when to log in.
                    // THE FOLLOWING BLOCK CURRENTLY CREATES A LIST OF
                    .subscribe();

            /*
            Instead of blocking the login/login notice until the ready event has completed,
            can we have the message counter and the message parser set up as two separate
            event listeners, with the parser set to delay subscription until the ReadyEvent
            has completed? It's not ideal behavior because the bot will show as online before it
            is ready to accept input, but it's a lot easier and it is a relatively minor
            use case in production (only affects a the time period between launch and the end of the backfill
            which shouldn't be an insane amount of time under light load. Once it's working,
            we can go back to figuring out how to set invisible or set do not disturb status during the wait.
            */

            client.getSelf().map(User::getUsername).subscribe(bot ->
                    log.info(String.format("Logged in as %s.", bot)));

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
