import discord4j.core.DiscordClient;
import discord4j.core.event.domain.message.MessageDeleteEvent;
import discord4j.core.object.entity.GuildChannel;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.MessageChannel;
import discord4j.core.object.entity.TextChannel;
import discord4j.core.object.util.Permission;
import discord4j.core.object.util.Snowflake;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DatabaseHandler {
    final Logger log = LoggerFactory.getLogger(ChatKat.class);
    InfluxDB influxDB;
    BatchPoints batchPoints;
    DiscordClient client;
    HashMap<Snowflake, HashMap<Snowflake, Boolean>> backfilledChannels = new HashMap<>();
    Debugger debugger = null;
    Properties properties;

    DatabaseHandler(DiscordClient client, Properties properties) {
        this.properties = properties;
        if (System.getenv("DEBUG") != null && System.getenv("DEBUG").toLowerCase().equals("true"))
            this.debugger = new Debugger();

        this.client = client;
        // initialize database connection
        this.influxDB = InfluxDBFactory.connect(properties.getProperty("databaseURL"),
                properties.getProperty("databaseUser"),
                properties.getProperty("databasePass"));

        this.influxDB.query(new Query("CREATE DATABASE " + properties.getProperty("databaseName")));
        this.influxDB.setDatabase(properties.getProperty("databaseName"));

        // enable writing data in batches & set up exception handler to log failed data points
        this.influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler(
                (failedPoints, throwable) -> failedPoints.forEach(p -> {
                    log.error("Error inside influxDB enableBatch exception handler  " + p.toString());
                }))
        );

        // initialize BatchPoints object for batch writes.
        this.batchPoints = BatchPoints.database(properties.getProperty("databaseName")).build();
    }

    // convenience method to simplify batch writing and error handling.
    private void writeBatch(BatchPoints batchPoints) {
        if (!batchPoints.getPoints().isEmpty()) {
            try {
                this.influxDB.write(batchPoints);
            } catch (Exception e) {
                log.error("Error inside writeBatch " + e.getCause().getMessage());
            }
        }
    }

    /* convenience method for creating username or tag reference in query output string
     * if setTags = true, returns @mention string. otherwise, attempts to retrieve the user's guild nickname.
     * if the user is no longer a member of the guild, retrieve their username (which is always available). */
    private String getUserLabel(String authorID, Snowflake guildID, Boolean setTags) {
        return this.client.getUserById(Snowflake.of(authorID)).map(author -> {
            if (setTags) {
                return author.getMention();
            } try {
                return author.asMember(guildID).block().getDisplayName();
            } catch (Exception e) {
                return author.getUsername();
            }
        }).block();
    }

    /* accept pre-filtered TextChannel, perform a history search, and fill database. On completion, store the
     *  channelID in backfilledChannels HashMap to prevent premature output */
    void backfillChannel(TextChannel channel){
        // initialize BatchPoints object for each channel to avoid conflicts from access by concurrent threads
        BatchPoints channelBatch = BatchPoints.database(properties.getProperty("databaseName")).build();
        Snowflake channelID = channel.getId();
        Snowflake guildID = channel.getGuildId();
        Instant lastInput = null;

        // Fetch last message in guild table and confirm that table exists
        QueryResult getLast = influxDB.query(new Query(String.format("SELECT last(time) FROM g%s LIMIT 1", guildID.asString())));
        if (getLast.getResults().get(0).getSeries() != null) {
            lastInput = Instant.ofEpochMilli((long) getLast.getResults().get(0).getSeries().get(0).getValues().get(0).get(0));
        }

        // track which channels and guilds have been seen by backfiller.
        if (!backfilledChannels.containsKey(guildID)) backfilledChannels.put(guildID, new HashMap<>());
        backfilledChannels.get(guildID).put(channelID, false);

        Flux<Message> channelHistory;
        /* if the table already exists, get messages after last entry. otherwise, get all messages*/
        if (lastInput == null) {
            channelHistory = channel.getMessagesBefore(Snowflake.of(Instant.now()));
        } else {
            channelHistory = channel.getMessagesAfter(Snowflake.of(lastInput));
        }
        // filter and backfill requested messages.
        channelHistory.filter(message -> !message.getAuthor().get().isBot()
                        && message.getAuthor().isPresent())
                .map(message -> this.addMessage(message, channelBatch))
                .doOnError(error -> log.error("Error in backfillChannel during addMessage :  " + error.getMessage() + "\n"))
                .doOnTerminate(() -> { // on completion, write the batch and mark the channel as backfilled.
                    this.writeBatch(channelBatch);
                    this.backfilledChannels.get(guildID).put(channelID, true);
                })
                .subscribe();
    }

    /*  method overloading on addMessage allows ChatKat.java to call .map(databaseHandler::addMessage)
    *   without accessing databaseHandler.batchPoints directly. the 2 parameter version of addMessage is only
    *   called inside backfillChannel() and by the single parameter version */
    Message addMessage(Message message) {
        return addMessage(message, this.batchPoints);
    }
    Message addMessage(Message message, BatchPoints batch) {
        if (debugger != null) {
            debugger.addMessage(message);
        }
        /* fetch channelID, authorID as strings to use as and tag values
         * numerical strings break the query, so prepend character to each */
        String channelID = "c" + message.getChannelId().asString(),
                authorID = "a" + message.getAuthor().get().getId().asString(),
                guildID = "g" + message.getGuild().block().getId().asString();

        // confirm that the message has content. if not, we'll store as a deleted message
        int isValid = (message.getContent().isPresent()) ? 1 : 0;

        // add message data to batch
        batch.point(Point.measurement(guildID)
                .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("channelID", channelID) // index channelID for channel based searches
                .tag("authorID", authorID)   // index authorID to group search output by author
                .addField("isValid", isValid)   // field isValid stores 1 for valid message or 0 for deleted
                .build());
        return message; // return the message to the flux it was called from to check for requests.
    }

    /* ChatKat only notes message deletions that occur while she is running. If this causes major
     * inconsistencies, operator may wish to drop the database and start from scratch. */
    void deleteMessage(MessageDeleteEvent event) {

        long messageTime = event.getMessageId().getTimestamp().toEpochMilli();

        String channelID = "c" + event.getChannelId().asString(),
                guildID = "g" + ((GuildChannel) event.getChannel().block()).getGuild().block().getId().asString();

        // discord4j doesn't serve authorID with delete events. fetch it from the database to get full tag set.
        String authorID = this.influxDB.query(new Query(
                String.format("SELECT \"isValid\", \"authorID\" FROM %s WHERE channelID = '%s' AND time = %dms",
                        guildID, channelID, messageTime)))
                .getResults().get(0).getSeries().get(0).getValues().get(0).get(2).toString();

        // lazy delete. wait to write to database until this.queryDatabase()
        this.batchPoints.point(Point.measurement(guildID)
                .time(messageTime, TimeUnit.MILLISECONDS)
                .tag("channelID", channelID)
                .tag("authorID", authorID)
                .addField("isValid", 0)
                .build());
    }


    /* Confirm that a message identified as a request is occurred in a valid channel, then send and return a response
    *  if message is invalid, returns the original message. */
    void processRequest(Message message) {
        /* if the debug tool is running, close it on first request to avoid indefinitely
         * writing to csv. */
        if (debugger != null) {
            debugger.close();
            this.debugger = null;
        }

        // Fetch channel to send reply
        MessageChannel channel = message.getChannel().block();

        // if we can't send messages in this channel, don't do anything
        if (!((GuildChannel) channel).getEffectivePermissions(client.getSelfId().get())
                .block().contains(Permission.SEND_MESSAGES))
            return;

        /* the results are usually going to come in fast, so you might not see this often
         * but we'll tell the channel the bot is "typing" for the aesthetic touch */
        channel.type();

        // write batchPoints to ensure results are up to date
        this.writeBatch(this.batchPoints);

        channel.createMessage(answerRequest(message)).block();
    }

    // accepts a Message that has been identified as a valid request for output and returns a String to send in response.
    String answerRequest(Message requestMessage) {
        List<String> requestParams = Arrays.asList(requestMessage.getContent().orElse("")
                .toLowerCase().split("[\\s]"));

        // Case 1 - help requests:
        if (requestParams.contains("-help")) return "Howdy, dumplin'! What can I do ya fer?\n\nType \"&kat\" in any channel"
                + " that I can see, and I'll let you know who has been spending all their time at the watercooler!\n\n"
                + "The default search returns results for the same channel as the request, but you can add a \"-server\""
                + " or \"-guild\" and I'll include results for every available channel on the server. This includes channels to which I can read requestMessage history, but not write.  \n\n"
                + "Include \"-day\", \"-week\", \"-month\", or \"-year\" in your requestMessage to get a count for a shorter " +
                "interval.\n\nIf you're the server's owner, you can also use \"-tag\" or \"-tags\" to mention all the users on the list.";

        // otherwise, get guildID and channel ID
        Snowflake guildID = requestMessage.getGuild().block().getId(), channelID = requestMessage.getChannelId();

        // Case 2 -  request received before the channel has been backfilled:
        if (!backfilledChannels.get(guildID).get(channelID)) return "I'm on my smoke break, henny. Check back in a few.";

        // Case 3 - request for full server history before one or more channels in the server have been backfilled:
        if ((requestParams.contains("-guild") || requestParams.contains("-server"))
                & (!backfilledChannels.get(guildID).entrySet().stream().allMatch(channelSet -> channelSet.getValue().equals(true))))
            return "I'm on my smoke break, henny. Check back in a few.";

        // Case 4 - request received in backfilled channel/server:

        /*  create HashMap for parsing time parameters. this is done inside the method to ensure that
        *  ZonedDateTime.now() equates to the moment of the request (or slightly after) */
        HashMap<String, Instant> setInterval = new HashMap<>(4) {{
            put("-year", ZonedDateTime.now().minusYears(1).toInstant());
            put("-month", ZonedDateTime.now().minusMonths(1).toInstant());
            put("-week", ZonedDateTime.now().minusWeeks(1).toInstant());
            put("-day", ZonedDateTime.now().minusDays(1).toInstant());
        }};

        // check request message for time range parameters
        long interval = 0; // set default interval to Epoch to retrieve full history if no params found.
        for (String s : setInterval.keySet()) {
            if (requestParams.contains(s.toLowerCase()))
                interval = setInterval.get(s).toEpochMilli();
        }

        /* check tag parameters to see if bot should @mention users in output. to prevent abuse, only the guild owner
        * should be able to use this feature */
        final AtomicBoolean outputTags = new AtomicBoolean(false); // default is false.
        if (requestMessage.getGuild().block().getOwnerId().equals(requestMessage.getAuthor().get().getId()))
            outputTags.set(requestParams.contains("-tag") || requestParams.contains("-tags"));

        final AtomicInteger atomicIndex = new AtomicInteger(0); // atomic index for counting in ordered stream

        // String variable used to simplify query String.format(). if guild tag on, use GuildID, else channelID
        String queryKey = (requestParams.contains("-guild") || requestParams.contains("-server")) ?
                "" :
                String.format("channelID = 'c%s' AND", channelID.asString());

        return influxDB.query(new Query(String.format("SELECT sum(\"isValid\") FROM g%s WHERE %s time >= %dms GROUP BY authorID",
                guildID.asString(), queryKey, interval)))
                .getResults().get(0).getSeries().stream() // stream results to sort & parse
                // influxDB schema formally expects a generic Object. cast to Double to prevent compiler errors.
                .sorted((seriesA, seriesB) ->
                        ((Double) seriesB.getValues().get(0).get(1)).compareTo((Double) seriesA.getValues().get(0).get(1)))
                // map results to an output substrings, then collect to final output string.
                .map(series -> String.format("%d. %s sent **%s** messages.",
                            atomicIndex.addAndGet(1),
                            getUserLabel(series.getTags().get("authorID").substring(1), guildID, outputTags.get()),
                            series.getValues().get(0).get(1).toString().split("\\.")[0]))
                .collect(Collectors.joining("\n"));
    }

    public void close() {
        this.writeBatch(this.batchPoints);
        if (this.debugger != null) this.debugger.close();
        this.influxDB.close();
    }
}
