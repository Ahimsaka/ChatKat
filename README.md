ChatKat is the ULTIMATE Discord4j message counting solution,
an application with infinite power, and a dear friend. 

She is configured to store message history in an influxDB 1.8 tsdb, reducing
response time with minimal storage/memory burden. 

As designed, she stores her influxDB in a docker container on the local host,
that is open on port 8086. This can easily be changed by adjusting the url string 
in Chatkat/src/java/ChatKat.java. 

WISHLIST:
 ON LAUNCH
 - BLOCK REQUEST PARSER UNTIL AFTER BACKFILL
    - Currently, it is possible for an error to occur when if a discord user sends a request
       before the lady finishes getting ready. 
 - Start back-fill from most recent message on relaunch with existing database
    - currently, ChatKat backfills the database every time she is restarted. 
    This should be unnecessary, but has minimal effect. The backfill process starts with 
    the most recent messages, adding each to the batchPoints object synchronously. Since a request 
    writes batchPoints to the database, the unnecessary messages pulled from 
    discord waste processing time, but won't delay output or alter results.
 - We may be able to use a scheduled query to clear all points where "isValid" = 0. But points are light, and I 
 would rather err in favor if keeping unnecessary data unless the size of the database becomes a problem. If it ain't broken etc. etc. 


          // ?? QUICK GOOD FUN ??
            // ??   - if Billob, Solohan, or Bill enter a voicechannel, use RNG w/ 1/100 chance to enter voicechannel
            // ??   - and play Never Going to Give You Up
