ChatKat is the ULTIMATE Discord4j message counting solution,
an application with infinite power, and a dear friend. 

She is configured to store message history in an influxDB 1.8 tsdb, reducing
response time with minimal storage/memory burden. 

As designed, she stores her influxDB in a docker container on the local host,
that is open on port 8086. This can easily be changed by adjusting the url string 
in ChatKat/src/main/resources/config.properties .


Type "&kat" in any channel ChatKat has read and create message permissions to, she'll return a scoreboard

The default search returns results for the same channel as the request, but you can add a "-server" or 
"-guild" to include results for every available channel on the server.

Include "-day", "-week", "-month", or "-year" in the message to get a count for a shorter interval.

If you're the server's owner, you can also use "-tag" to mention all the users on the list.

ChatKat can record messages for any servers with where she has permission to read message history and view channel. 
(But she does need send message permission in the channel where the request arrives, or she won't do a thing).


The Debugger tool can be used to generate a csv file of all available metadata for any messages
present in the available channels at launch. It will continue to write to the .csv file until 
receiving a query in Discord (a message beginning with "&kat").


Uses InfluxDB:1.8 with influxdb-java 2.19: See: https://github.com/influxdata/influxdb-java/blob/master/MANUAL.md
  
influxdb-java is used due to ease of configuration with influxDB 1.x, which is used because
   this is a limited scope application and influxDB 2.x does not yet have an official dockerhub image.
   For any further development, conversion to influxdb-client-java and InfluxDB 2.x should be considered. */


NOTES:
    - currently, ChatKat backfills the database every time she is restarted. 
    This should be unnecessary, but has minimal effect. The backfill process starts with 
    the most recent messages, adding each to the batchPoints object synchronously. Since a request 
    writes batchPoints to the database, the unnecessary messages pulled from 
    discord waste processing time, but won't delay output or alter results.


