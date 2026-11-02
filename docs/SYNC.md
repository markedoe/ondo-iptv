# Sync on the local network (version 1)

Ondo devices on the same Wi-Fi or wired network keep their playlists, My List, lists and playback progress in
step while the app is open on them. Nothing goes through the internet: devices find each other with Bonjour
(DNS-SD) and talk directly, and only to addresses of the local network. The network is trusted: there is no
pairing and no encryption, so it is off until the viewer turns it on, on each device (Settings → Sync on this
network).

This document is the specification. The Kotlin implementation is in
`android/core/src/main/kotlin/com/markedoe/ondo/core/sync/`, the Swift one in
`Packages/IPTVCore/Sources/IPTVCore/Sync/`. Both read the same example files in `fixtures/sync/`.

## Roles

- **Android** (TV, phone, tablet) is a server: while Ondo is open and sync is on, it listens on a TCP port and
  advertises it. It also connects to the other Android devices it finds.
- **iPhone, iPad and Mac** are clients only: they connect to the Android devices they find. (The Mac app may not
  accept connections: the App Store edition has no `network.server` entitlement.) Two Apple devices therefore
  sync through an Android device, not with each other.

## What syncs and what stays

Synced: the playlists (name, kind, address, login and password, guide address), My List and favourite channels,
the actors and directors kept in My List, the lists (collections) with the people in them, playback progress (where each movie and episode was left, and what was finished), and the
keys typed in Settings for outside services (the TMDb token).

Stays on each device: the other settings, hidden categories, channel edits, reminders, profiles, downloads, the catalogues
themselves (each device downloads its playlists), and file playlists (a file imported on one device is not on the
others). On Apple devices, only the main profile syncs; kids profiles do not.

## Discovery

Service type `_ondo._tcp`. An Android device with sync on advertises the service name `Ondo-<first 8 characters
of its device ID>` with TXT records:

- `d`: its device ID, a random UUID made once per installation (lowercase);
- `v`: `1`;
- `n`: a name for the device list (the device's name in the system settings, or its model; at most 40
  characters).

Devices are recognised by `d`. A device ignores its own `d`.

## Local addresses only

A device sends or accepts nothing unless the other end's address is local. Both sides check, with the same rule
(`SyncNetwork.isLocal`): IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16` (link-local) and
`127.0.0.0/8`; IPv6 `fc00::/7` (unique local), `fe80::/10` (link-local) and `::1`; an IPv4 address mapped in IPv6
(`::ffff:a.b.c.d`) is checked as IPv4. Anything else is refused:

- the server closes a connection from another address before reading it;
- a client connects only to an address found by Bonjour, and only if it is local (never to an address typed in).

The apps also refuse a loopback address (`127.0.0.0/8`, `::1`, also written `::ffff:127.0.0.1`), in both
directions: at that address is another app of the same device, not another device (`LocalSync.isPeerAddress` on
Android, `LocalSync.isLocal` on Apple devices; the shared rule keeps loopback for the tests). And the Android app syncs only while a Wi-Fi or wired network is connected, with or
without internet: on mobile data alone, private addresses belong to the carrier's network, not to the viewer's home.

## Transport

TCP. A **frame** is a 4-byte big-endian length followed by that many bytes of UTF-8 JSON. A frame over 16 MiB is
refused and ends the session.

One session is one exchange: the client connects, sends its snapshot (one frame), the server merges it with its
own, keeps the result, and answers with the merged snapshot (one frame); the client keeps the answer and closes.
Connecting gets 10 seconds and the whole session 30 seconds. A server handles at most 4 sessions at once and
closes further connections.

**When.** A client syncs when it finds a device, then every 60 seconds while the app is open, and a few seconds
after a local change (a playlist, My List, a list, or the end of a playback). After a failed session it finds the
device's address again and tries after 15 seconds, then 1 minute, then every 5 minutes while Bonjour still finds
the device.

Between two Android devices, the one with the smaller device ID (as a string) connects when it finds the other and
every 60 seconds; either one connects a few seconds after a change of its own. An Android device that keeps what
an iPhone, iPad or Mac sent thus passes it on to the other Android devices.

## Snapshot

```json
{
  "type": "ondo-sync",
  "version": 1,
  "device": "<uuid>",
  "deviceName": "Living room TV",
  "playlists": [ … ],
  "favorites": [ … ],
  "progress": [ … ],
  "lists": [ … ],
  "people": [ … ],
  "keys": [ … ],
  "deleted": { "<key>": 1760000000000 },
  "replaced": { "<playlist id>": "<playlist id>" },
  "clock": 1760000000000
}
```

Times are milliseconds since 1970 (UTC), as integers. Playlist IDs are UUIDs written in lowercase; readers lowercase
them. Optional fields are left out when they have no value. Readers ignore fields they do not know. A snapshot with
another `type` or a `version` other than 1 is refused (the server answers nothing and closes).

**Playlist** — `{"id", "name", "kind": "m3u" | "xtream", "url", "username"?, "password"?, "epgUrl"?, "addedAt",
"updatedAt"}`. For Xtream, `url` is the server address and `username` and `password` the login; for M3U, `url` is
the playlist address. Key: `playlist:<id>`; time: `updatedAt` (set when the playlist is added or edited).

**Favorite** (My List and favourite channels) — `{"playlist", "kind": "live" | "movie" | "series", "item",
"title", "image"?, "addedAt"}`. Key: `favorite:<playlist>|<kind>|<item>`; time: `addedAt`.

**Progress** — `{"playlist", "kind": "movie" | "series", "item", "episode"?, "title", "subtitle"?, "image"?,
"position", "duration", "watchedAt"}`. `item` is the movie or the series, `episode` the episode of a series;
`position` and `duration` are seconds (numbers). A title is finished at 95 % of its duration. Key:
`progress:<playlist>|<kind>|<item>|<episode or empty>`; time: `watchedAt`.

**List** — `{"id", "name", "items": [{"playlist", "kind", "item", "title", "image"?}], "people": [<person>],
"updatedAt"}`. Key: `list:<id>`; time: `updatedAt` (set when the list is created, renamed or its items or people
change). `id` is lowercased. `people` (the actors and directors in the list) may be missing: a device that does not
keep people sends none.

**Person** (an actor or a director kept in My List, whatever the playlist) — `{"name", "image"?, "addedAt"}`.
`image` is their photo (a web address). Key: `person:<name>` with only the letters A to Z in lowercase (the same on
every platform; other letters keep their case); time: `addedAt` (removing them adds the key to `deleted`). Neither
platform keeps a TMDb person ID: a person is their name. A snapshot without `people` (from a device that does not
keep them) removes none: only `deleted` does. Readers fold the names of `person:` keys in `deleted` the same way.

**Key** (the key or token of an outside service, typed in Settings) — `{"service", "value", "updatedAt"}`.
`service` names the service: `tmdb` for TMDb (its API Read Access Token, or its API Key); `value` is the key as
saved (no spaces around it). Key: `key:<service>`; time: `updatedAt` (set when the key is saved; removing it adds
`key:<service>` to `deleted`). A key is checked with its service on the device where it is typed; the other
devices use it as it comes, and their Settings say so when the service refuses it later. A device keeps and passes
on the keys of services it does not know. A snapshot without `keys` (from a device that does not sync them) removes
nothing: only `deleted` does.

**Deleted** — the key of each record removed on a device, with the time it was removed. A device keeps these for
180 days.

**Clock** — the sender's clock when it sent the snapshot (an answer carries the server's). Optional: older versions
send none.

**Replaced** — playlist IDs that became another playlist (rule 4 of the merge): the old ID, then the ID that
replaced it. A device keeps an entry as long as the deletion of the old playlist.

## Merge

`merge(a, b)` gives the same result whatever the order of `a` and `b`, and merging the result again changes
nothing.

1. `deleted`: every key of either side, with the later time. `replaced`: every entry of either side (on a
   conflict, the smaller new ID). Wherever a record names a playlist, an ID found in `replaced` is replaced by its
   new ID, again while the result is in `replaced` (at most 10 times).
2. For each key present in either side, the record with the later time wins. On equal times, the record whose
   **tie string** is smaller (compared as UTF-16 code units, as both Kotlin and Swift strings compare) wins:
   - playlist: `name`, `url`, `username`, `password`, `epgUrl` joined with a line feed (missing values as empty);
   - favorite: `title`;
   - progress: not a string: the larger `position` wins, then the smaller `title`;
   - list: not a string first: the list with more `people` wins (a device that does not keep people sends the list
     without them at the same time); then `name`, each item's key (`<playlist>|<kind>|<item>`) in order, and each
     person as `<name>|<image or empty>|<addedAt>` in order, joined with a line feed;
   - person: not a string first: the one with an `image` wins; then the smaller `name`, then the smaller `image`;
   - key: `value`.
3. A record whose time is not later than its key's deletion time is dropped (on equal times, the deletion wins).
4. **The same playlist added twice** (on two devices before they synced): playlists of the same `kind`, `url`
   (trimmed, then compared ignoring case) and `username` are one playlist. The one with the smallest `addedAt`
   (then the smallest `id`) stays; each other one is dropped and its `id` replaced by the kept one in every
   favourite, progress and list item, `playlist:<dropped id>` is added to `deleted` with the dropped
   playlist's `updatedAt`, and `<dropped id>` → `<kept id>` to `replaced`. Records that now share a key are merged
   again by rule 2.
5. A favourite, progress or list item whose playlist is deleted (`playlist:<id>` is in `deleted` and no kept
   playlist has that ID) is dropped. One whose playlist is simply unknown (a file playlist of another device) is
   kept.
6. The result lists playlists by `addedAt` then `id`, favorites by `addedAt` then key, progress by `watchedAt`
   (latest first) then key, lists by `id`, people by `addedAt` then key, and keys by `service`.

A device keeps the merged snapshot as its own: it adds the playlists it did not have (and downloads them),
updates the ones that changed, removes the deleted ones, and replaces its My List, favourite channels, people, lists and
progress with the merged ones, and saves the keys of the services it uses (removing those deleted). It keeps
whatever it does not sync. If the viewer changed something during the
session, the device merges again with its current state, so no change is lost.

## Clocks

Times are each device's wall clock. A TV box can run with a wrong clock until it reaches a time server, so a device
that receives a snapshot first puts its times on its own clock (`onReceiverClock`):

1. When the snapshot has a `clock` more than 5 minutes away from the receiver's, every time in it moves by the
   difference (an answer's times thus come back to the client's clock). A time that would move past the receiver's
   clock plus 5 minutes while it was not there before keeps its value: it was stamped before the sender's clock went
   wrong.
2. Then every record and deletion still dated more than 5 minutes after the receiver's clock is left out: a clock
   running ahead cannot date a change in the future and win over every later one (a snapshot from an older version,
   without `clock`, goes only through this step). The records are left out, not moved: the device that made them
   keeps them, they travel once their time has come, and nothing is deleted because it is missing.

A record is only replaced by one with a strictly later time, so a device's own records never go back to an older
version. Times move by a difference measured once per session, so a device's own records can come back a few
milliseconds apart: the same content.

## Privacy

Snapshots hold playlist logins and passwords, and the keys of outside services (the TMDb token), in clear, as the
trusted network was chosen over encryption. They
travel only between Ondo devices with sync on, on the local network; Ondo sends them nowhere else. Turning sync off
stops the server, the advertising and the browsing at once.
