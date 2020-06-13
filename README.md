# ChatKat is the ULTIMATE Discord4j message counting solution, and a dear friend. 

ChatKat is configured to store message history in an influxDB 1.8 tsdb, reducing response time with minimal storage/memory 
burden. Development was made possible by [Discord4j, a comprehensive reactive library for development with the Discord Bot API](https://github.com/Discord4J/Discord4J).

# Basic setup: 

To run this bot, you will need: 
* Discord bot token 
* Discord application client ID

[Both  can be obtained via the discord developer portal](https://discord.com/developers/). You'll need to log in to your discord account,
 create a new Application for the client ID, and add a Bot to the application to obtain the bot token. 
You can also configure your bot's username and icon. 
 
Use this link (while signed in to a Discord account) to invite the bot to servers: 
* https://discordapp.com/oauth2/authorize?client_id=CLIENTID&scope=bot
* Be sure to replace "CLIENTID" with the client ID generated for your Discord application. 

To see and count messages in a channel, your bot will need permissions to 
* View Channel  
* Read Message History

The bot will only respond to requests sent in channels where
she has **Send Message** permission set. Without it, she can still read and count messages in that channel 
for full server output requests. 
 
### Run Via Docker-Compose (Recommended):

The recommended usage of this bot is via docker-compose, which generates two docker containers: one for the bot, and a 
second which runs the default influxDB 1.8 docker image. To use this method you'll need:
- [set up docker](https://www.docker.com/get-started) 
- clone the repository to local storage
- open a terminal and navigate to the repository directory (/ChatKat)
- in bash:
       
 `docker build -t chatkat --build-arg BOT_TOKEN="YOUR BOT TOKEN" . `
      
After the image build is complete, in the same terminal window (or another window pointed to the same directory):

  `docker-compose up`
        
### Run Using a Separate Database:

If you wish to configure the bot to run with a separate database, instead of using docker-compose, you can easily
do so by editing the databaseName, databaseUser, databasePass, and databaseURL properties found in the config.properties 
file, located at `ChatKat/src/main/java/resources/config.properties`.

Your database must run InfluxDB 1.8 (or a compatible earlier build). 
**ChatKat does not work with InfluxDB 2.x**. For more information, see the [influxdb-java library documentation.](https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md)

After you've made the changes, rebuild the docker image. Open a terminal window and navigate to the project directory, then:

`docker build -t chatkat --build-arg BOT_TOKEN="YOUR BOT TOKEN" . `

Instead of using docker-compose, run the new image with docker and make it available on port 80 for HTTP messages:

`docker run -p 80:80 chatkat`
 
### Run Without Docker:

While not recommended, it is possible to run the bot without using docker. 

* Follow the steps listed above for configuring a separate database. **The database must be running first, 
or the bot will exit with an error.** 
* Set an environment variable of BOT_TOKEN="Your Bot Token".  
* Open a terminal window and navigate to the project directory. Then launch the bot via the terminal: `./gradlew run`

# Interacting with the Bot on Discord

## Startup
When the bot launches it will begin to populate the database with message history from all available channels and 
servers. If the bot receives a request in a channel before it has finished reviewing that channel's history, it will 
respond with a delay message:

![delay message example](https://github.com/Ahimsaka/ChatKat/blob/media/delay-message.png?raw=true)

Note that the bot processes channels on parallel threads.  Scores for channels with shorter histories will not be
 delayed by channels with longer histories. 

## Commands (All Commands are Case Insensitive):

The bot receives commands in the discord chat for any text channel in which ChatKat has the correct permissions 
(View Channel, Read Message History, Send Messages).

All commands begin with **&kat**

### &kat (Default):

If a message begins with **&kat** and has no other applicable parameters, it will be treated as the default. The bot 
responds with a ranked list of the number of posts entered by all users in the channel where the request occurred.

![basic output example](https://github.com/Ahimsaka/ChatKat/blob/media/basic-output.png?raw=true)

Note that the message is unaffected by non-parameter text that occurs after **&kat**: 

![basic output example with horseplay](https://github.com/Ahimsaka/ChatKat/blob/media/basic-output-horseplay.png?raw=true)

### Additional parameters: 

All additional parameters must be placed after "&kat" and are preceded by a hyphen. 

#### -help:

Causes the bot to send a simple help message in the channel. This parameter overrides any other parameters. 

![help output example](https://github.com/Ahimsaka/ChatKat/blob/media/help.png?raw=true) 
![help-override](https://github.com/Ahimsaka/ChatKat/blob/media/help-override.png?raw=true)

#### -server or -guild

Causes the bot to send a cumulative scoreboard containing the sum of results from all available channels on the discord 
server/guild that hosts the channel where the request occurred.

![full server default example](https://github.com/Ahimsaka/ChatKat/blob/media/default-server.png?raw=true)

By default, this scoreboard includes the full history of the channel, but it can be combined with any time frame parameter.

#### Time frame Parameters
 
- -year
- -month
- -week
- -day

Any time frame parameter can be added to a default **&kat** request to augment output for a single channel, or combined with 
**-server** or **-guild** to augment output for the full guild. 

![single channel and day and week examples](https://github.com/Ahimsaka/ChatKat/blob/media/default-day-week.png?raw=true) 
![single channel month example](https://github.com/Ahimsaka/ChatKat/blob/media/default-month.png?raw=true)
![single channel year example](https://github.com/Ahimsaka/ChatKat/blob/media/default-year.png?raw=true)
![full server year example](https://github.com/Ahimsaka/ChatKat/blob/media/server-year.png?raw=true)

When **-server** and a timeframe parameter are both included, the order does not alter the output.

![day then full server example](https://github.com/Ahimsaka/ChatKat/blob/media/day-server.png?raw=true)
![full server then day example](https://github.com/Ahimsaka/ChatKat/blob/media/server-day.png?raw=true)

#### -tags or -tag

This parameter can be combined with any other parameters (except -help) to @mention the discord users in the output. 

To prevent abuse, the -tags/-tag parameter is only available to the server owner. If included by another user, it is ignored. 

![default search with tags](https://github.com/Ahimsaka/ChatKat/blob/media/default-tags.png?raw=true)

## Discord Search Bar Conflicts & Debugger Tool

When using ChatKat in a live Discord server, you will notice discrepancies between the number of messages reported by a 
search using the Discord app search bar, and the number of messages reported by ChatKat. 

For performance reasons, the official Discord App stores searchable metadata in a separate database from message history. 
The search metadata database is less reliable than the message history database. Regular discord users will already know 
that the search bar is often unavailable at times when sending and reading new messages in channels is unaffected. Messages 
sent while the search database is down are not retroactively added when it comes back online, so they don't ever show up in 
the search bar.

Fortunately for us, ChatKat uses the full message history database. In any instance where the Discord App search bar 
reports a lower number than ChatKat, ChatKat is right. 

When development of ChatKat began, the developer was not aware of this design trade-off in Discord. To determine 
why the numbers reported by the bot did not always match the numbers reported by the search bar, it was necessary to
design a debugging tool to aid in comparing the messages present in the channel with the messages found and counted by the bot.
This simple tool writes metadata for all available messages to a timestamped .csv file located at 
`ChatKat/src/main/resources/debug_files/`. This file contains sufficient information to manually confirm that all messages
counted do exist as valid messages in the channels where they are recorded. 

![debugger output csv example](https://github.com/Ahimsaka/ChatKat/blob/media/debugger-csv.png?raw=true)

If you would like to test these results for yourself (or if you would like to generate such a csv file for your own purposes), 
simply run the bot with an environment variable DEBUG=true. 

Note that using the debugger tool while running in a Docker container will write output inside the container. 
It is easier to access the csv if you run the bot directly on the local host. Follow these steps: 
- Start influxDB container with `docker run -p 8086:8086 influxdb:1.8`
- set databaseURL in config.properties to http://127.0.0.1:8086
- set environment variable BOT_TOKEN=your bot token
- set environment variable DEBUG=true
- open terminal window and navigate to project directory. 
- `./gradlew run`
- Because ChatKat continues processing messages indefinitely while it runs, the Debug tool is configured to stop writing 
  (and flush its cache) when it receives any "&kat" command. If no command arrives, the csv will not include the full record. 

(Alternately, you may find it easier to run the code from an IDE and handle environment variables through the IDE's settings)

# Why InfluxDB 1.8?

At the time of development, there is not an official InfluxDB 2.x docker image listed on dockerhub. 

For any possible expansion or extension in the future, migration to influxDB 2.x and [influxdb-client-java](https://github.com/influxdata/influxdb-client-java)
will be strongly considered. 


