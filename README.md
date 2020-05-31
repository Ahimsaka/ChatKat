ChatKat is the ULTIMATE Discord4j message counting solution,
an application with infinite power, and a dear friend. 

She is configured to store message history in an influxDB 1.8 tsdb, reducing
response time with minimal storage/memory burden. 

As designed, she stores her influxDB in a docker container on the local host,
that is open on port 8086. This can easily be changed by adjusting the url string 
in ChatKat/src/main/resources/config.properties


NOTES:
    - currently, ChatKat backfills the database every time she is restarted. 
    This should be unnecessary, but has minimal effect. The backfill process starts with 
    the most recent messages, adding each to the batchPoints object synchronously. Since a request 
    writes batchPoints to the database, the unnecessary messages pulled from 
    discord waste processing time, but won't delay output or alter results.
