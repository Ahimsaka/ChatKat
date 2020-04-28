import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.util.Snowflake;
import discord4j.gateway.json.dispatch.MessageCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Map.Entry.comparingByValue;


/*
*
* START OVER:
*   -- recalculate every time (calc only based on parameters)
*   -- no history
*
* First 3 users get usernames and embeds.
*
* PARAMETERS
*   Full Channel
*   One Category
*   Single User/Category or # of users
*
* !daily 10
* !weekly 3
* !monthly
* !yearly 5
* etc.
*
* if results are limited by param, we should still scrape
* full channel since it doesn't take that long and saves time on repeat calls
*
* Rewrite print statements to allow a counter & to allow printing one grouping at a time
* Should be able to print:
*   ? Full Guild - all channels
*   One Category at a Time - self expl.
*   Or any above, with output limit.
*
*
* an Owner Only mode:         // Get the bot owner ID to filter commands
        AtomicLong ownerId = new AtomicLong();
        Flux.first(client.getEventDispatcher().on(ReadyEvent.class),
                client.getEventDispatcher().on(ResumeEvent.class)).next()
                .flatMap(evt -> client.getApplicationInfo()).map(ApplicationInfo::getOwnerId).map(Snowflake::asLong)
                .subscribe(ownerId::set);
*
*
* printUser, printChannel, printAll satisfy these requirements with the right params
* so it's time to add params.
*
*
* KeyListener to get Ctrl+some char to quit.
* Docstrings
*
*
* */


public class ChatKat {
    static String token = Token.token();
    public static Logger log = LoggerFactory.getLogger("name");
    public static DiscordClient client;
    public static HashMap<Channel, Instant> history = new HashMap();

    private static class Scoreboard {
        public static HashMap<User, Integer> daily = new HashMap<User, Integer>();
        public static HashMap<User, Integer> sevenDay = new HashMap<User, Integer>();
        public static HashMap<User, Integer> monthly = new HashMap<User, Integer>();
        public static HashMap<User, Integer> yearly = new HashMap<User, Integer>();
        public static HashMap<User, Integer> all = new HashMap<User, Integer>();
        public static Message last;
        public static Snowflake created;
        static MessageChannel channel;

        public Scoreboard(Message message) {
            setCreated(message);
            setLast(message);
            channel = message.getChannel().block();
        }

        public static void setLast(Message message) {
            last = message;
        }
        public Message getLast() {
            return last;
        }

        public static void setCreated(Message message) {
            created = message.getId();
        }
        public Snowflake getCreated() {
            return created;
        }

        public static void increment(Message message, Scoreboard s) {
            log.info("Update Increment Increment");

            User user = message.getAuthor().get();
            Instant m = message.getTimestamp();

            LocalDateTime last = LocalDateTime.ofInstant(s.getLast().getTimestamp(), ZoneId.systemDefault());
            LocalDateTime mTime = LocalDateTime.ofInstant(m, ZoneId.systemDefault());

            if (!s.all.containsKey(user)) s.all.put(user, 0);
            s.all.put(user, s.all.get(user) + 1);

            if (mTime.getYear() == last.getYear()) {
                if (!s.yearly.containsKey(user)) s.yearly.put(user, 0);
                s.yearly.put(user, s.yearly.get(user) + 1);
            }

            if (mTime.getMonth() == last.getMonth()) {
                if (!s.monthly.containsKey(user)) s.monthly.put(user, 0);
                s.monthly.put(user, s.monthly.get(user) + 1);
            }

            if (mTime.plus(Duration.ofDays(7)).isAfter(last)) {
                if (!s.sevenDay.containsKey(user)) s.sevenDay.put(user, 0);
                s.sevenDay.put(user, s.sevenDay.get(user) + 1);
            }

            if (mTime.getDayOfYear() == last.getDayOfYear()) {
                if (!s.daily.containsKey(user)) s.daily.put(user, 0);
                s.daily.put(user, s.daily.get(user) + 1);
            }

        }

        public Integer i = 1;

        public String printUser(User user, Integer count, String suffix) {
            String prefix = i == 1 ? "**Your Queen**" : i == 2 ? "**Runner-up**" : i == 3 ? "**2nd Runner-up**" : i.toString() + ".  ";
            String report = prefix + "  <@" + user.getId().asString() + "> *has posted* **" + count + " times** *" + suffix + (i == 3 ? "*\n" : "*");
            i++;
            return report;
        }

        public String printCategory(HashMap<User, Integer> board, String title, String suffix) {
            i = 1;
            List<String> report = board.entrySet().stream()
                    .sorted(comparingByValue(Comparator.reverseOrder()))
                    .map(entry -> printUser(entry.getKey(), entry.getValue(), suffix))
                    .collect(Collectors.toList());
            return "__" + title + "__\n\n" + String.join("\n", report) + "\n\n";
        }

        public void printAll() {
            log.info("I be printing all");
            String report = printCategory(daily, "Daily Ranks", "today!")
                    + printCategory(sevenDay, "Last 7 Days", "in the last 7 days!")
                    + printCategory(monthly, "This Month", "this month!")
                    + printCategory(yearly, "This Year", "this year!")
                    + printCategory(all, "All Time", "since the channel was created.");
            sendMessage(channel, report, 3000);
        }
    }

    public static void main(String[] args) {
        try {



            client = DiscordClientBuilder.create(token).build();
            String bot = client.getSelf().block().getUsername();

            // Create event handlas
            List<EventHandler> eventHandlers = new ArrayList<>();
            eventHandlers.add(new Counter());


            Flux<ReadyEvent> ready = client.getEventDispatcher().on(ReadyEvent.class);

            ready.subscribe(r -> {
                        log.info("Logged in as " + bot + ".");
                    });

            Flux<MessageCreateEvent> getMessages = client.getEventDispatcher().on(MessageCreateEvent.class);
            getMessages
                    .delaySubscription(ready)
                    .filter(event -> event.getMessage().getAuthor().isPresent() &&
                                    !event.getMessage().getAuthor().get().isBot() &&
                                    event.getMessage().getContent().isPresent())
                    .flatMap(event -> Mono.whenDelayError(eventHandlers.stream()
                            .map(handler -> handler.onMessageCreate(event))
                            .collect(Collectors.toList())))
                    .onErrorContinue((t, o) -> log.error("Error while processing event", t))
                    .subscribe();

            Flux<MessageCreateEvent> tester = Flux.first(getMessages).map(event -> {
                log.info(event.getMessage().getContent().get());
                return event;
            });
            tester.subscribe();

            /*
             * On Guild Create:
             *     - Start making scoreboards for each channel in guild.
             *
             *   -filter the parameters
             *   -print scoreboard based on params.
             *
             *  THIS IS WHERE I NEED TO PARSE PARAMETERS
             * -X number of results to show (default is ten)
             * -all (show all results -- overrides number)
             * -me (just return user's scores)
             * -guild(return total between all avail. MessageChannel in guild (default is current channel only)
             * -tag (tag winners)
             * -dm
             *
             *
             */

            client.login().block();
        } catch (Exception e) {
            log.error(e.toString());
        }


    }

    static abstract class EventHandler {
        public abstract Mono<Void> onMessageCreate(MessageCreateEvent event);
    }

    static class Counter extends EventHandler {
        @Override
        public Mono<Void> onMessageCreate(MessageCreateEvent event) {

            Message message = event.getMessage();
            MessageChannel channel = message.getChannel().block();

            if (history.containsKey(channel)) {
                if (history.get(channel).plus(1, ChronoUnit.HOURS).compareTo(message.getTimestamp()) >= 0){
                    String outString = "Nuh-uh, hunty.  I'm off the clock.  Check back at "+history.get(channel).plus(1, ChronoUnit.HOURS).toString();
                    sendMessage(channel, outString, 200);
                    return Mono.empty();
                }
            }
            client.getGuilds().join()
            history.put(channel, message.getTimestamp());

            Scoreboard s = new Scoreboard(message);

            Flux<Message> messagesToCount = channel.getMessagesBefore(message.getId());
            // Args: -<int--ignored without:> -<UNIT: d, w, m, y>
            // if TimeArgs, messagesToCount.takeWhile(msg ->
            //                          message.getTimestamp().minus(int, UNIT).compareTo(msg.getTimestamp()) >=0)
            // default counts all.
            // printAll handles itself in this case.
            messagesToCount
                    .flatMap(msg -> Mono.just(msg).subscribeOn(Schedulers.elastic()))
                    .doOnNext(msg -> s.increment(msg, s))
                    .collectList()
                    .onErrorContinue((t, o) -> log.error("Error while processing event", t))
                    .doFinally(finished -> {
                        // PRINT ARGS here, just filter it inside of the object with arguments.
                        s.printAll();
                    })
                    .subscribe();
            return Mono.empty();
        }
    }



    /*static class GetScoreboard extends EventHandler {
        @Override
        public Mono<Void> onMessageCreate(MessageCreateEvent event) {
            log.info("GetScoreboard");
            Message message = event.getMessage();
            log.info("Message retrieved.");
            return message.getChannel()
                    .filter(channel->history.containsKey(channel))
                    .flatMap(channel -> Mono.whenDelayError(channel
                            .getMessagesAfter(history.get(channel).last.getId())
                            .takeWhile(ms->ms.getId().compareTo(history.get(channel).last.getId())<=0)
                            .collect(Collectors.toList())
                            .flatMap(list -> Mono.whenDelayError(list.stream()
                                    .map(m -> {
                                        log.info(m.getContent().get());
                                        if (!history.containsKey(m)) Scoreboard.increment(m);
                                        else Scoreboard.increment(m, history.get(channel));
                                        return Mono.empty();
                                    }).collect(Collectors.toList()))).map(voidList -> {
                                history.get(channel).printAll();
                                return Mono.empty();
                            })))
                    .onErrorContinue((t, o) -> log.error("Error while processing event", t))
                    .then();
            message.getChannel().map(channel -> {
                if (history.containsKey(message)){
                    Scoreboard scoreboard = history.get(message);
                    message.getChannel().subscribe(MessageChannel::getMessagesBefore)
                } else {
                    Scoreboard scoreboard = new Scoreboard(message);
                }})
                    .subscribe(scoreboard -> {
                        scoreboard.printAll();
            });
        }
    }
    */


    public static Pattern summons = Pattern.compile("\\A[\\s\\.]*&+[\\s.]*[kK]+[[aA]*[tT]*]*");




    public static void sendMessage(MessageChannel channel, String message, Integer millis) {
        try {
            channel.type();
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            log.error(e.toString());
        }
        channel.createMessage(message).subscribe();
    }

}

