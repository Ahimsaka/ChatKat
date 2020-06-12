ChatKat is the ULTIMATE Discord4j message counting solution, an application with infinite power, and a dear friend. 

She is configured to store message history in an influxDB 1.8 tsdb, reducing response time with minimal storage/memory 
burden. 

#Basic setup: 

To run this bot, you will need: 
* Discord bot token 
* Discord application client ID

[Both  can be obtained via the discord developer portal](https://discord.com/developers/). You'll need to log in to your discord account,
 create a new Application for the client ID, and add a Bot to the application to obtain the bot token. 
You can also configure your bots username and icon. 
 
Use this link (while signed in to a Discord account) to invite the bot to servers: 
* https://discordapp.com/oauth2/authorize?client_id=CLIENTID&scope=bot

Be sure to replace "CLIENTID" with the client ID generated for your Discord application. 

To see and count messages in a channel, your bot will need to be invited with
permissions to 
* View Channel  
* Read Message History

Additionally, she will only respond to requests sent in channels where
she has Send Message permission. If she does not have Send Message permission in a channel
 she can still include it in full server/guild scoreboard output. 
 
## Via Docker-Compose (Recommended):

The recommended usage of this bot is via docker-compose, which generates two docker containers: one for the bot, and a 
second which runs the default influxDB 1.8 docker image. To use this method you'll need:
- [set up docker](https://www.docker.com/get-started) 
- clone the repository to local storage
- open a terminal and navigate to the repository directory (/ChatKat)
- in bash:
       
 `docker build -t chatkat --build-arg BOT_TOKEN="YOUR BOT TOKEN" . `
      
After the image build is complete, in the same terminal window (or another window pointed to the same directory):

  `docker-compose up`
        
### Using a Separate Database:

If you wish to configure the bot to run with a separate database, instead of using docker-compose, you can easily
do so by editing the databaseName, databaseUser, databasePass, and databaseURL properties found in the config.properties 
file, located at `ChatKat/src/main/java/resources/config.properties`.

Your database must run InfluxDB 1.8 (or a compatible earlier build). 
**ChatKat does not work with InfluxDB 2.x**. For more information, see the [influxdb-java library documentation.](https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md)

After you've made the changes, open a terminal window and navigate to the project directory. Then:

`docker build -t chatkat --build-arg BOT_TOKEN="YOUR BOT TOKEN" . `
 
### Using without Docker:

To run the bot without docker, follow the steps for configuring a separate database and ensure that config.properties is 
pointed to the database. The database must be running first, or the bot will exit with an error. 

Next, set an environment variable of BOT_TOKEN="Your Bot Token".  

Open a terminal window and navigate to the project directory. Then launch the bot via the terminal:

`./gradlew run`

# Interacting with the Bot

## On Startup
As soon as the bot is running it will populate the database with message history from all available channels and 
servers. If the bot recieves a request in a channel before it has finished reviewing that channel's history, it will 
respond with a delay message.

![delay message example](https://github.com/Ahimsaka/ChatKat/blob/media/delay-message.png?raw=true)

Note that the bot processes channels on parallel threads.  Scores for channels with shorter histories will not be
 delayed by channels with longer histories. 

## Commands (Case Insensitive):

Commands may be entered into the discord chat for any text channel in which ChatKat has the correct permissions (View Channel, Read Message History, Send Messages).

All commands for this bot must begin with "&kat"

### &kat (Default):

If a message begins with &kat and has no other applicable parameters, it will be treated as the default. The bot 
responds with a ranked list of the number of posts entered by all users in the channel where the request is received.

![basic output example](https://github.com/Ahimsaka/ChatKat/blob/media/basic-output.png?raw=true)

Note that the message can contain any other 
non-parameter text. As long as it begins with &kat. Additional text is ignored, except if it includes a parameter. 

#### Additional parameters: 

All additional parameters must be placed after "&kat" and be preceded by a hyphen. 

##### -help:

Causes the bot to send a simple help message in the channel. This parameter overrides any other parameters. 

`&kat -help`

![help output example](https://github.com/Ahimsaka/ChatKat/blob/media/help.png?raw=true) ![help-override](https://github.com/Ahimsaka/ChatKat/blob/media/help-override.png?raw=true)

##### -server or -guild

Causes the bot to send a cumulative scoreboard that includes results from all available channels on the discord server/guild in 
which the request is received. Output is sent in the same channel as the request. 

By default, this scoreboard includes the full history of the channel. -server and -guild can be combined with any date-range parameter.

![full server default example](https://github.com/Ahimsaka/ChatKat/blob/media/default-server.png?raw=true)


##### Time frame Parameters
 
- -year
- -month
- -week
- -day

Any time frame parameter can be added to a default "&kat" to augment output for a single channel, or combined with 
-server or -guild to augment output for the full guild. If multiple parameters are used, they can be used in any order. 

![single channel and day and week examples](https://github.com/Ahimsaka/ChatKat/blob/media/default-day-week.png?raw=true) 
![single channel month example](https://github.com/Ahimsaka/ChatKat/blob/media/default-month.png?raw=true)
![single channel year example](https://github.com/Ahimsaka/ChatKat/blob/media/default-year.png?raw=true)
![full server year example](https://github.com/Ahimsaka/ChatKat/blob/media/server-year.png?raw=true)

When -server and a timeframe parameter are combined, the order is not considered.

![day then full server example](https://github.com/Ahimsaka/ChatKat/blob/media/day-server.png?raw=true)
![full server then day example](https://github.com/Ahimsaka/ChatKat/blob/media/server-day.png?raw=true)


## Discord Search Bar Conflicts & Debugger Tool

When using ChatKat in a live discord server, you will notice discrepancies between the number of messages reported by a 
search using the Discord app search bar tool and the number of messages reported by ChatKat. 

For performance reasons, the official Discord App stores searchable metadata in a separate database from message history. 
The database that stores the search meta-data is less reliable than the message history database. Regular discord users will
have already noticed that the search bar is sometimes unavailable, though the channels are unaffected. Messages sent while the 
search database is unavailable are not retroactively added to the search database when it comes back online. 

Fortunately for us, ChatKat uses the full message history database. In any instance where the Discord App search bar 
reports a lower number than ChatKat, ChatKat is right. 

When the development of ChatKat began, the developer was not aware of this design trade-off in Discord. To figure out 
why the numbers reported by the bot did not match the numbers reported by the search bar, it was necessary to
design a debugging tool to help compare the messages present in the channel with the messages found and counted by the bot.
This simple debugging tool writes all available metadata for all messages seen by the bot to a timestamped .csv file located 
at `ChatKat/src/main/resources/debug_files/`. This csv file includes unique message IDs, message text, and timestamps, and any other
information required to manually check the channel and confirm that all messages counted by the bot do exist, even those 
that are not visible to the search bar. 

If you would like to test these results for yourself (or if you would like to generate such a csv file for your own purposes), 
simply run the bot with an environment variable DEBUG=true. 

![debugger output csv example](https://github.com/Ahimsaka/ChatKat/blob/media/debugger-csv.png?raw=true)

Because ChatKat continues processing messages indefinitely as long as it runs, the Debug tool is configured to stop writing 
(and flush its cache) when it receives any "&kat" command. 

Note that using the debugger tool while running in a Docker container will write output inside the container. 
It is easier to access the csv if you run the bot directly on the local host. Follow these steps: 
- Start influxDB container with `docker run -p 8086:8086 influxdb:1.8`
- set databaseURL in config.properties to http://127.0.0.1:8086
- set environment variable BOT_TOKEN=your bot token
- set environment variable DEBUG=true
- open terminal window and navigate to project directory. 
- `./gradlew run`

(Alternately, you may find it easier to run the code from an IDE and handle environment variables through the IDE's settings)

# Why InfluxDB 1.8?

At the time of development, there is not an official InfluxDB 2.x docker image listed on dockerhub. 

For any possible expansion or extension in the future, migration to influxDB 2.x and [influxdb-client-java](https://github.com/influxdata/influxdb-client-java)
will be strongly considered. 


