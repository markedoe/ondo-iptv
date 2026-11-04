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
keys typed in Settings for outside services (the TMDb token), the order of the rows of the Movies and Series tabs
with the rows hidden (Settings → Row order), and the viewer profiles (name, colour, avatar, Kids) with each profile's
own My List, favourite channels, people, lists, progress and order of rows (docs/PROFILES.md).

Stays on each device: the other settings, hidden categories, channel edits, reminders, profile PINs (never sent, salted
or not: see docs/PROFILES.md), "Always open this profile", downloads, the catalogues themselves (each device downloads
its playlists), and file playlists (a file imported on one device is not on the others).

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

**Probes.** Each snapshot sent carries `digest`, a digest of its records and deletions (SHA-256 of the snapshot
without `device`, `deviceName`, `clock`, `digest`, `seen`, `probe` and `unchanged`, deletions in key order, and
without the progress of what plays on the device, saved every few seconds); a server's answer carries its own, of the
data the answer comes from. A device compares a digest only with its own earlier ones and those a peer sent it,
so devices need not compute it byte for byte alike. When a client's data has the digest of the snapshot it last sent
in full to a server (both worked out without what plays on the client now, so a film started since does not count as a
change), and that server answered with a digest, the client sends a **probe** instead: `{"type", "version",
"device", "deviceName", "clock", "digest", "seen": <the server's digest>, "probe": true}`, without records. A server
whose own digest is still `seen` answers `{"type", "version", "device", "deviceName", "digest", "unchanged": true}`,
and nothing is merged; otherwise it answers with its own snapshot. A client still sends its whole snapshot to each
server at least once an hour, except while something plays on it (it then sends a probe). An older server reads a
probe as an empty snapshot, merges it (which removes nothing) and answers in full; an older client never sends one.
A session where nothing changed on either side is thus a few hundred bytes each way.

**When.** A client syncs when it finds a device, then every 60 seconds while the app is open, and a few seconds
after a local change (a playlist, My List, a list, or the end of a playback). After a failed session it finds the
device's address again and tries after 15 seconds, then 1 minute, then every 5 minutes while Bonjour still finds
the device. While a movie or an episode plays on the client, a session of the 60-second rhythm that would send a
snapshot over 64 KiB (not a probe) waits for the next one; one after a change goes. When a session is due is timed on a
clock that never goes back (time since the device started), not on the wall clock, which a time server may move. A change counts when it is made on
the device: what a merge brought travels on with the next regular session, so a merge never calls for another
session. The snapshot, the digest and the merge are worked out off the main thread; a merge whose data changed
meanwhile is done again, and after three tries it waits for the next session.

A session counts for a server (the deletions it sent are had, see **Deleted**) once its answer to a whole snapshot
reached the client: the client closes the connection after reading it. A probe does not count. A client counts it
once it has merged a whole answer.

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
  "rows": [ … ],
  "profiles": [ … ],
  "profileData": [ … ],
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
`position` and `duration` are seconds (numbers). A title is finished (watched) once playback reaches its end credits,
when the file has a chapter for them in its last quarter (named "Credits", "Générique", "Abspann", "Créditos"…);
otherwise at 95 % of its duration, or with 10 minutes or less left once three quarters are seen; with no known
duration, never. Where the credits start is not synced: each device keeps what its own player read in the file
(Apple: MP4 and MOV chapters, MKV chapters, VLC; Android: MP4 chapters, which Media3 reads, and none for MKV). A device
that never played the file judges by the rule by time. Key: `progress:<playlist>|<kind>|<item>|<episode or empty>`;
time: `watchedAt`.

**List** — `{"id", "name", "items": [{"playlist", "kind", "item", "title", "image"?, "addedAt"?}],
"people": [<person>], "updatedAt", "renamedAt"?, "orderedAt"?}`. Key: `list:<id>`; time: `updatedAt` (set when the
list is created, renamed, put in order, or its items or people change). `id` is lowercased. `people` (the actors and
directors in the list) may be missing: a device that does not keep people sends none. Each part has its own time
(rule 7 of the merge): `renamedAt` when the name was set, `orderedAt` when the items were put in order (both
`updatedAt` when missing), and each item's and person's `addedAt` (an item's is the list's `updatedAt` when missing,
as an older version sends it). Taking an item or a person out of a list adds
`listitem:<list id>|<playlist>|<kind>|<item>` or `listitem:<list id>|person:<name>` to `deleted` (list and playlist IDs
lowercased, the name folded as for a person).

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

**Rows** (the order of the rows of a tab, Settings → Row order) — `{"tab", "order", "hidden", "updatedAt"}`. `tab`
is `movie` or `series`; `order` lists the rows of the tab by key, in the viewer's order; `hidden` the keys of the rows
the viewer hid. A row's key is `all` (All movies or All series), `continue` (Continue Watching), `mylist`, `new` (new
movies or series), `trending`, `awards` (award winners: films in Movies, series in
Series), or `cat:<category id>|<name>` for a provider
category: its ID as the playlist gives it (`movie-12`, without control characters or `|`), then its name trimmed, runs
of spaces and control characters made one space, A to Z in lowercase (other letters keep their case). A device finds a
category by the same key, else by its ID, else by its name: a category the provider renumbers or renames keeps its
place. Rows the tab has but `order` does not name come after, as they come (a category new to the playlist goes to the
end); keys no row has (a category gone from the playlist, or of another playlist) are kept, after the row they
followed, for when it comes back. Hidden categories and languages stay hidden whatever the order. Key: `rows:<tab>`;
time: `updatedAt` (set each time the viewer moves, hides or shows a row, or chooses Default order, which writes an empty
`order` and keeps `hidden`). The whole record is one change: a row moved on one device and another hidden on another
at the same time keep only the later change. Readers drop keys that are blank or hold a control character (below
U+0020, or U+007F), keep the first of keys repeated in `order`, and keep `hidden` once each, sorted (UTF-16 code
units). Readers then keep at most 2000 keys in `order` (the first) and in `hidden` (the first once sorted), drop keys
and tabs longer than 256 UTF-16 units, and keep the records of 8 tabs at most: `movie` and `series`, then the others in
UTF-16 order (a merge keeps the same 8). A device keeps and passes on the record of a tab it does not know. A snapshot without `rows` (from an older
version) changes nothing; no record is ever deleted. An older Android device drops `rows` from what it keeps and
passes on: two Apple devices that sync only through it keep each their own order. Each profile has its own order: the
main profile's is `rows`, another profile's is in its `profileData`. The order a device kept before profiles had one
each is the main profile's, with its time. A Kids profile cannot change its order.

**Profiles** (docs/PROFILES.md) — `{"id", "name", "colorIndex", "symbol", "kids", "updatedAt"}`. `id` is a UUID in
lowercase; the main profile's is `00000000-0000-0000-0000-000000000001`. `symbol` is `initial`, an icon name or an
emoji. Never a PIN: a profile's PIN stays on its device. Key: `profile:<id>`; time: `updatedAt` (set when the profile is
added, renamed, or its colour, avatar or Kids switch changes; a new PIN alone is no change). A profile never changed
(the main one as each device makes it, "Me") is not sent. Deleting a profile adds `profile:<id>` to `deleted`; the main
profile is never deleted (a deletion of it is ignored). Readers keep 16 profiles at most: the main one, then by ID.

**Profile data** — `{"profile", "favorites"?, "progress"?, "lists"?, "people"?, "rows"?, "deleted"?}`: the records of a
profile other than the main one, by the same rules as the snapshot's own, with what was deleted in that profile. The
main profile's records are the snapshot's own: an older device, which knows nothing of profiles, reads and writes the
main profile's data, ignores `profiles` and `profileData`, and the format version stays 1. Data sent for the main
profile in `profileData` is ignored. Profiles do not travel through an older device: two devices that only meet
through one share the main profile's data alone. Each profile's deletions learned from another device are kept from
when they were learned, as the main profile's are (see **Deleted**).

Two choices stay with each device (docs/PROFILES.md): a profile made not Kids elsewhere stays Kids on a device where a
parent set a PIN, though it takes the record's time (its own snapshot then says Kids at that time, the merge keeps the
other device's choice, and nothing changes from one session to the next; a later edit of that profile on the protected
device dates it again, and sends Kids back to every device); and, the first time a device receives
profiles, a profile of its own made apart is one with a received profile of the same name (trimmed, whatever its case
and accents) and the same Kids switch: the smaller ID is kept on both devices, the other is deleted (dated), and its
data goes into the one kept.

**Deleted** — the key of each record removed on a device, with the time it was removed. A device keeps each one for
180 days, and longer until every device it had a session with in the last year has had a session with it after the
removal (it remembers, to the day, its last session with each device): a device away for months still gets the
removal, and nothing removed comes back from it. A device not seen for a year holds nothing back. A deletion learned
from another device counts from when it was learned, if that is later than its own time.

**Clock** — the sender's wall clock when it sent the snapshot. Optional, and receivers ignore it (see **Clocks**):
older versions send none.

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
   - playlist: `name`, `kind`, `url`, `username`, `password`, `epgUrl`, `addedAt` (in decimal) joined with a line feed,
     an optional value written `0` when missing and `1` followed by it otherwise;
   - favorite: `title`, `image` (optional, as above);
   - progress: not a string first: the larger `position` wins, then the larger `duration`; then `title`, `subtitle`,
     `image` (optional, as above);
   - person: not a string first: the one with an `image` wins; then the smaller `name`, then the smaller `image`;
   - key: `value`;
   - rows: the number of keys of `order` in decimal, a line feed, then the keys of `order` and of `hidden` joined with a
     line feed (no key holds one).
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
   (latest first) then key, lists by `id`, people by `addedAt` then key, keys by `service`, and rows by `tab`.
7. **Lists merge part by part.** A list stays unless `list:<id>` is deleted at or after its latest `updatedAt`, which
   it keeps. Its name is the one with the latest `renamedAt` (then the smaller name). Its items are those of either
   side, one per key, the latest `addedAt` winning (then the smaller tie string of `title` and `image`, optional as in
   rule 2), without those
   whose `addedAt` is not later than their `listitem:` deletion; its people likewise (the latest `addedAt`, then as
   rule 2 for a person), sorted by `addedAt` then key. The order is the `order` (item keys) of the side with the latest
   `orderedAt` (then the smaller line of those keys joined with a line feed; a list without `order` has the order of
   its `items`), written as it is into the result: the result then does not depend on the order of the merges. The
   items follow it; those not in it come after, by `addedAt` then key. The result always writes `renamedAt`,
   `orderedAt`, `order` and each `addedAt`. An older version still keeps a
   whole list as one record and sends no `listitem:` deletion: an item it took out comes back from a device that
   still has it.
8. **Profiles** merge by rule 2 (tie string: `name`, `colorIndex`, `symbol`, `kids` as `1` or `0`), the main
   profile's deletion ignored. A profile's deletion wins only at or after the profile's latest time: its `updatedAt`
   and every time in its data (as a list's content does, rule 7). A device that kept watching in a profile after
   another deleted it keeps the profile, and brings it back on the other. A profile dropped (deleted at or after that
   time, or past the 16 kept) goes with its data. With three devices, the order of the sessions decides whether data
   older than the deletion comes back with the profile (a device that meets the deleting one first drops it), but every
   device ends with the same state. Each profile kept other than the main one merges its data by rules 2 to 7, as a snapshot of its own
   whose playlists are the merged ones: a favourite or progress of a deleted playlist goes, a replaced playlist's
   becomes the one kept, and a record deleted in that profile (its `deleted`) goes. The result lists profiles and
   profile data by ID.

A device keeps the merged snapshot as its own: it adds the playlists it did not have (and downloads them),
updates the ones that changed, removes the deleted ones, and replaces its My List, favourite channels, people, lists and
progress with the merged ones, saves the keys of the services it uses (removing those deleted), and keeps the
merged order of the rows. It keeps
whatever it does not sync. The progress of a movie or episode playing on the device is not replaced while it
plays: the one received waits, and applies when playback ends if it is the later. Nor is the catalogue reloaded
under the player: when the playlist in use was deleted, replaced, or its address or login changed, the catalogue
follows once playback ends. If the viewer changed something during the
session, the device merges again with its current state, so no change is lost.

## Clocks

Every time is given by the device that makes the change, with a **hybrid logical clock** (`SyncClock`). A TV box can
run with a wrong clock until it reaches a time server (at 1970, or hours off), so the wall clock alone cannot order
changes made on different devices. Each device keeps `last`, the latest time it gave or received (saved with its
sync data, and at launch never earlier than any time it holds):

1. **A change made on the device** (a favourite added or removed, a position, a list edited, a playlist or a key
   saved, any deletion) is dated `max(wall clock, last + 1)`, which becomes `last`. A change made after the device
   saw another one is thus dated after it, whatever its wall clock says: a removal made after an add wins
   everywhere, and a position saved after one received wins over it.
2. **A snapshot received** first loses the records and deletions dated more than **one day** after the receiver's
   wall clock: one device far ahead cannot date its changes in the future, win over every later one, or carry the
   others' clocks along. They are left out, not moved: the device that made them keeps them, and they travel with a
   whole snapshot once their time is within a day of the receiver's clock: the hourly one, or the next after a change
   on either side (in between, sessions are probes, which carry no records). Then `last` becomes the latest time of
   what is left, if that is later.
3. A receiver whose wall clock reads before 2024 is not set: it has no idea of the time. It leaves out what is dated
   after 2029-01-01 or, when that is later, more than two years after `last`: a clock reading 2100, or a damaged time,
   carries no device there.

A sync session never changes a time: a record is kept or replaced as it was dated where it was made. The `clock` of a
snapshot is ignored. Older versions send their wall clock's times and leave nothing out, which the newer ones read
as any other times. "Now", to prune deletions and to note sessions and the deletions learned, is the later of the wall
clock and `last`, but never more than a day after a wall clock that is set, nor after 2024-01-01 when it is not: one
time far ahead cannot make every deletion look old and prune it at once.

Limits: a device whose clock was more than a day ahead keeps dating its changes after its own earlier ones once its
clock is right, so the other devices take them only once that time is within a day of their clocks (an hour later
for a clock that was a day and an hour ahead, a year later for a clock that was a year ahead). A device whose clock is
not set takes in a time up to that bound, and then dates its changes after it. And a device running ahead by less
than a day carries the others' clocks along: until their wall clocks catch up, each change made elsewhere is dated
just after the latest time seen, so a device away from the others meanwhile (a phone out of the home) dates its
changes before those made on that device after their last session, and loses to them whatever the real order. On a
home network, where devices sync every minute, that window is about a minute, but it is as long as the phone is away.

## Privacy

Snapshots hold playlist logins and passwords, and the keys of outside services (the TMDb token), in clear, as the
trusted network was chosen over encryption. They
travel only between Ondo devices with sync on, on the local network; Ondo sends them nowhere else. Turning sync off
stops the server, the advertising and the browsing at once.
