# Example application

# Introduction

Music-tracker is a fully functional example application that demonstrates the capabilities of the Kvasir data broker.
Visit its repository at [kvasir-music-tracker](https://gitlab.ilabt.imec.be/kvasir/music-tracker-demo) for up-to-date
information and source code.

It consists of three components:

1. The `spotify-client` is a Java application that can communicate directly with the Spotify process running on your
   system (this should be supported on Windows, macOS and Linux distros that use systemd). It detects which track is
   playing, and sends [ListenAction](https://schema.org/ListenAction) events to a Kvasir [Slice](Slices.md) that was
   preconfigured for the application.
2. The `py-change-processor` is a Python script that subscribes to the configured Kvasir Slice and listens for
   new [ListenAction](https://schema.org/ListenAction) events via HTTP Server-Sent-Events (SSE). For each track played,
   the script will fetch artist and album metadata (release date, length of tracks, cover art) from the open music
   encyclopedia [MusicBrainz](https://musicbrainz.org), transform this data into RDF and upload it to Kvasir (which
   validates and stores it into the Knowledge Graph for the Pod). The change-processor also maintains a play count for
   each track/album/artist. In a real-life application, this feature would probably be performed by an additional
   processing component, but in order to not overcomplicate the example, we opted for a single processing component.
   Album cover art is stored via the Pod's built-in S3 API.
3. The `web-app` is a single-page web application built using [Vue.js](https://vuejs.org). It visualizes the recently
   played tracks and allows you to browse the various artists and albums that are present in the Knowledge Graph for the
   demo user Alice.

![](overview.png)

# Running the application

Make sure you have Docker Desktop installed, with Docker host networking enabled:

![](docker_host_networking.png)

## Starting the main components

We've bundled a Compose file that can be used to start a Kvasir server and all of its dependecies, initialized
with a demo pod for a test user `alice` at `http://localhost:8080/alice` and preconfigured clients for the various
components of the music-tracker.

```bash
cd compose
docker compose up -d
```

Running this command will also build and start the `py-change-processor` and the `web-app`.

## Configuring the Kvasir Slice

At this time, Kvasir does not support booting with preconfigured Slice definitions. However, the music-tracker
application requires a specific Slice definition to exist.

To setup the Slice, go to http://localhost:8081/ and login into the `alice` pod using the demo credentials
`alice:alice` (username:password). Next, click on `Slices` in the top menu-bar and then click on `Create Slice` in the
top right of the screen.

Enter `music-tracker` as name for the Slice, and then copy the content
of [kvasir-slice/context.jsonld](https://gitlab.ilabt.imec.be/kvasir/music-tracker-demo/-/blob/main/kvasir-slice/context.jsonld)
in the `@context` area
and [kvasir-slice/schema.sdl](https://gitlab.ilabt.imec.be/kvasir/music-tracker-demo/-/blob/main/kvasir-slice/schema.sdl)
in the `schema` area respectively.

The page should now look like this:

![](create-slice.png)

Feel free to inspect the GraphQL schema. It defines which GraphQL query operations can be performed and the types that
are returned (and thus which data fields are accessible), which mutations can be performed (and which input data is
supported) and which subscription events are supported. The schema also maps these types to RDF either explicitly (via
`@class` or `@predicate` directives) or implicitly by specifiying a prefix (followed by an underscore) before a type or
field name.

Kvasir can automatically handle incoming data, GraphQL queries on the Slice Knowledge Graph and Server-Sent-Event
subscriptions, based purely on the contents of the scheme definition.

**Now don't forget to register the Slice by clicking `Save`!**

## Starting the Spotify client

Now the only thing left is to feed the application with some data. This is done by starting the spotify-client. This
component is not included in the docker compose setup, because it requires direct access to the Spotify process running
on your OS.

Execute the following commands:

```
cd spotify-client
./mvnw quarkus:dev
```

Once the process is successfully launched, you should see console logs referring to the music that is playing in your
Spotify client. For example:

```
2025-05-13 14:00:49,534 INFO  [kva.dem.mus.spo.SpotifyListenerSetup$setup$1] (pool-8-thread-1) Now playing: I. The Planet - Norma Jean (length: 00:03:02.951)
2025-05-13 14:00:49,876 INFO  [kva.dem.mus.spo.cli.MusicBrainzClient] (vert.x-eventloop-thread-0) Found 6 matching recordings for I. The Planet by Norma Jean ([{id=c4ca0e74-4d76-49b1-a1cf-c1f0d546b98e, release=Polar Similar, length=182952}, {id=cccb1de2-d9c3-4e3d-9f6f-f2cc6c42c19a, release=Meridional, length=242000}, {id=c73e5c95-d3b3-4a33-bf71-2b9b39ca226a, release=Come Into My Room, length=0}, {id=3f7afb7a-a65d-4a1b-98bb-1b7a09fc2f65, release=Come Into My Room, length=0}, {id=78e0598c-a2c8-4e9b-86d0-24de7c2755db, release=Do You Want to Party?, length=337000}, {id=384cbd5e-1fb4-499a-ae8d-27acc7879e38, release=Friday Nite, length=0}])
2025-05-13 14:00:49,877 INFO  [kva.dem.mus.spo.cli.MusicBrainzClient] (vert.x-eventloop-thread-0) Selecting recording c4ca0e74-4d76-49b1-a1cf-c1f0d546b98e as its length is closest to the track length
2025-05-13 14:03:53,293 INFO  [kva.dem.mus.spo.SpotifyListenerSetup$setup$1] (pool-8-thread-1) Now playing: Blemish - Greyhaven (length: 00:03:20.657)
2025-05-13 14:03:53,556 INFO  [kva.dem.mus.spo.cli.MusicBrainzClient] (vert.x-eventloop-thread-0) Found 2 matching recordings for Blemish by Greyhaven ([{id=26d84210-574f-4741-bdcb-6fddb3980edc, release=Equal Vision Records Summer Sampler 2018, length=202640}, {id=ee65feea-0a35-423c-9dcb-9f2b681cb8d0, release=Empty Black, length=200000}])
2025-05-13 14:03:53,557 INFO  [kva.dem.mus.spo.cli.MusicBrainzClient] (vert.x-eventloop-thread-0) Selecting recording ee65feea-0a35-423c-9dcb-9f2b681cb8d0 as its length is closest to the track length
```

You can now visit the web-app at http://localhost:5173 and authenticate using alice to browse the music-tracker data.

# Known limitations

* Access control in Kvasir will be implemented via Slices, and although the example application already uses Slices for
  most of its interactions, fine-grained access control in Kvasir is not yet implemented at this time (i.e. the
  application could access data in different Slices or via the global Knowledge Graph API, this will not be allowed in
  future releases).
* Retrieving the profile picture for an artist is a naive implementation that does not always produce reliable results.
  The logic simply searches for the name of the artist in combination with the keyword `band` and then downloads the
  thumbnail for the article that is returned.
* Kvasir supports time-travel for queries, allowing you to query the state of the server at a specific timestamp in the
  past. Our goal is to integrate this feature in the web-app, allowing you to view your music library or favourite
  tracks at certain points in time, but this is currently not implemented.

# Troubleshooting

* **When browsing to the Kvasir UI at http://localhost:8081/, it does not show Alice's pod.** => Check if Docker host
  networking is enabled.
* **The spotify-client is producing ListenActions, but the web-app dashboard remains empty.** => Check if the
  py-change-processor is operating correctly (by checking the logs of its container). Try restarting it! Alternatively:
  try clicking on the menu tabs at the top. Someties viewing the page in incognito mode can also work.
* **I've made changes to the py-change-processor or web-app, but these are not showing up when running the deployment.**
  => Run `cd compose && docker compose up -d --build`, this will trigger a rebuild of those components.