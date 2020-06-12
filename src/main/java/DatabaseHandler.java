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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZonedDateTime;
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
        this.influxDB = InfluxDBFactory.connect(properties.getProperty("serverURL"),
                properties.getProperty("databaseUser"),
                properties.getProperty("databasePass"));
        // idempotent query creates database if it doesn't exist
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

    /* accept pre-filtered TextChannel, perform a history search, and fill database. On completion, store the
     *  channelID in this.backfilledChannels to prevent premature output */
    TextChannel backfillChannel(TextChannel channel){
        // initialize BatchPoints object for each channel to avoid conflicts during simultaneous processing
        BatchPoints channelBatch = BatchPoints.database(properties.getProperty("databaseName")).build();
        Snowflake channelID = channel.getId();
        Snowflake guildID = channel.getGuildId();

        if (!backfilledChannels.containsKey(guildID)) backfilledChannels.put(guildID, new HashMap<>());
        backfilledChannels.get(guildID).put(channelID, false);

        channel.getMessagesBefore(Snowflake.of(Instant.now()))
                .filter(message -> !message.getAuthor().get().isBot()
                        //&& message.getContent().isPresent()
                        && message.getAuthor().isPresent())
                .map(message -> this.addMessage(message, channelBatch))
                .doOnError(error -> log.error("Error in Add Message :  " + error.getMessage() + "\n"))
                .doOnTerminate(() -> {
                    this.backfilledChannels.get(guildID).put(channelID, true);
                    this.writeBatch(channelBatch);
                })
                .subscribe();
        return channel;
    }

    // convenience method to avoid manipulating object properties directly
    private void writeBatch(BatchPoints batchPoints) {
        if (!batchPoints.getPoints().isEmpty()) {
            try {
                this.influxDB.write(batchPoints);
            } catch (Exception e) {
                log.error("Error inside writeBatch " + e.getCause().getMessage());
            }
        }
    }

    // convenience method for creating username or tag reference in query output string
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

    /* adding second BatchPoints to segregate backfill from forward recording. This method uses
     * method overloading to preserve the original function will enabling the new BatchPoints */
    Message addMessage(Message message) {
        return addMessage(message, this.batchPoints);
    }
    Message addMessage(Message message, BatchPoints batch) {
        if (debugger != null) {
            debugger.addMessage(message);
        }
        /* fetch channelID, authorID as strings to use as and tag values
         **numerical strings break the query, so prepend character to each */
        String channelID = "c" + message.getChannelId().asString(),
                authorID = "a" + message.getAuthor().get().getId().asString(),
                guildID = "g" + message.getGuild().block().getId().asString();

        int isValid;

        if (message.getContent().isPresent()) {
            isValid = 1;
        } else {
            isValid = 0;
        }
        // add message data to batch
        batch.point(Point.measurement("messages")
                .time(message.getTimestamp().toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("channelID", channelID)
                .tag("guildID", guildID)
                .tag("authorID", authorID)
                .addField("isValid", isValid)
                .build());
        return message;
    }

    /* ChatKat only notes message deletions that occur while she is running. If this causes major
     * inconsistencies, user may wish to start database from scratch. */
    void deleteMessage(MessageDeleteEvent event) {
        long messageTime = event.getMessageId().getTimestamp().toEpochMilli();
        String channelID = "c" + event.getChannelId().asString();
        String guildID = "g" + ((GuildChannel) event.getChannel().block()).getGuild().block().getId().asString();

        // discord4j doesn't serve authorID with delete events. fetch it from the database.
        String authorID = this.influxDB.query(new Query("SELECT \"isValid\", \"authorID\" FROM messages"
                + " WHERE channelID = '" + channelID + "'"
                + " AND time = " + messageTime
                + "ms")).getResults().get(0).getSeries().get(0).getValues().get(0).get(2).toString();

        // lazy delete. wait to write to database until this.queryDatabase()
        this.batchPoints.point(Point.measurement("messages")
                .time(messageTime, TimeUnit.MILLISECONDS)
                .tag("channelID", channelID)
                .tag("authorID", authorID)
                .tag("guildID", guildID)
                .addField("isValid", 0)
                .build());
    }

    // send query (if applicable) and return response)
    String answerQuery(Message message) {
        List<String> requestParams = Arrays.asList(message.getContent().orElse("")
                .toLowerCase().split("[\\s]"));

        // set helpMessage for help requests
        if (requestParams.contains("-help")) return "Howdy, dumplin'! What can I do ya fer?\n\nType \"&kat\" in any channel"
                + " that I can see, and I'll let you know who has been spending all their time at the watercooler!\n\n"
                + "The default search returns results for the same channel as the request, but you can add a \"-server\""
                + " or \"-guild\" and I'll include results for every available channel on the server. This includes channels to which I can read message history, but not write.  \n\n"
                + "Include \"-day\", \"-week\", \"-month\", or \"-year\" in your message to get a count for a shorter " +
                "interval.\n\nIf you're the server's owner, you can also use \"-tag\" or \"-tags\" to mention all the users on the list.";

        // get guildID and channel ID
        Snowflake guildID = message.getGuild().block().getId(), channelID = message.getChannelId();

        // if the channel hasn't been backfilled, send a delay message.
        if (!backfilledChannels.get(guildID).get(channelID)) return "I'm on my smoke break, henny. Check back in a few.";

        /* if the request is for the server history and not all channels have been backfilled send
         * the delay message */
        if ((requestParams.contains("-guild") || requestParams.contains("-server"))
                & (!backfilledChannels.get(guildID).entrySet().stream().allMatch(channelSet -> channelSet.getValue().equals(true))))
            return "I'm on my smoke break, henny. Check back in a few.";

        /* create HashMap for parsing time parameters. each query creates a new OutputBuilder, so
         * ZonedDateTime.now() equates to the moment of the request (or slightly after) */
        HashMap<String, Instant> setInterval = new HashMap<String, Instant>(4) {{
            put("-year", ZonedDateTime.now().minusYears(1).toInstant());
            put("-month", ZonedDateTime.now().minusMonths(1).toInstant());
            put("-week", ZonedDateTime.now().minusWeeks(1).toInstant());
            put("-day", ZonedDateTime.now().minusDays(1).toInstant());
        }};

        long interval = 0; // set default interval to 0 epochtime to retrieve full history
        for (String s : setInterval.keySet()) {
            if (requestParams.contains(s.toLowerCase()))
                interval = setInterval.get(s).toEpochMilli();
        }

        final AtomicBoolean outputTags = new AtomicBoolean(false);

        if (message.getGuild().block().getOwnerId().equals(message.getAuthor().get().getId()))
            outputTags.set(requestParams.contains("-tag") || requestParams.contains("-tags"));

        final AtomicInteger currentIndex = new AtomicInteger(0); // index for counting inside sort stream

        return influxDB.query(new Query(String.format("SELECT sum(\"isValid\") FROM messages WHERE %s' AND time >= %dms GROUP BY authorID",
                // if guild tag use guildID tag as search key, else use channelID
                (requestParams.contains("-guild") || requestParams.contains("-server")) ?
                        String.format("guildID = 'g%s", guildID.asString()) :
                        String.format("channelID = 'c%s", channelID.asString()),
                interval)))
                .getResults().get(0).getSeries().stream() // stream results to sort & parse
                // influxDB schema formally expects a generic Object. cast to Double to prevent compiler errors.
                .sorted((seriesA, seriesB) -> ((Double) seriesB.getValues().get(0).get(1)).compareTo((Double) seriesA.getValues().get(0).get(1)))
                // map each result to an output string, then collect to a final string.
                .map(series -> String.format("%d. %s sent **%s** messages.",
                            currentIndex.addAndGet(1),
                            getUserLabel(series.getTags().get("authorID").substring(1), guildID, outputTags.get()),
                            series.getValues().get(0).get(1).toString().split("\\.")[0]))
                .collect(Collectors.joining("\n"));
    }

    // Parse request parameters and return the appropriate message
    Message parseRequest(Message message) {
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
            return message;

        // write batchPoints to ensure results are up to date
        this.writeBatch(this.batchPoints);

        return channel.createMessage(answerQuery(message)).block();
    }

    public void close() {
        this.writeBatch(this.batchPoints);
        if (this.debugger != null) this.debugger.close();
        this.influxDB.close();
    }
}
