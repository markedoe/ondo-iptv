# Sync on the local network (version 1)

Ondo devices on the same Wi-Fi or wired network keep their playlists, My List, lists and playback progress in
step while the app is open on them. Nothing goes through the internet: devices find each other with Bonjour
(DNS-SD) and talk directly, and only to addresses of the local network. The network is trusted: there is no
pairing and no encryption, so it is off until the viewer turns it on, on each device (Settings → Sync on this
network).

This document is the specification. The Kotlin implementation is in
`android/core/src/main/kotlin/com/markedoe/ondo/core/sync/`, the Swift one in
`Packages/IPTVCore/Sources/IPTVCore/Sync/`. Both read the same example files in `fixtures/sync/`, and each merges the
snapshots the other wrote in `fixtures/sync/interop/` (see its README).

## Roles

- **Android** (TV, phone, tablet) is a server: while Ondo is open and sync is on, it listens on a TCP port and
  advertises it. It also connects to the other Android devices it finds.
- **iPhone, iPad and Mac** are clients only: they connect to the Android devices they find. (The Mac app may not
  accept connections: the App Store edition has no `network.server` entitlement.) Two Apple devices therefore
  sync through an Android device, not with each other.

## What syncs and what stays

Synced: the playlists (name, kind, address, login and password, guide address), My List and favourite channels,
the actors and directors kept in My List, the lists (collections) with the people in them, playback progress (where each movie and episode was left, and what was finished), and the
keys typed in Settings for outside services (the TMDb token, and the subtitle services' keys and OpenSubtitles account
with the Addic7ed switch), the order of the rows of the Home, Movies and Series tabs with the rows hidden (Settings →
Row order), and the viewer profiles (name, colour, avatar, Kids) with each profile's own My List, favourite channels,
people, lists, progress, order of rows and hidden categories, languages and styles (docs/PROFILES.md), and the lists
shared with every profile.

Stays on each device: the other settings, hidden channels and channel edits, the category locks (Apple), reminders, profile PINs (never sent, salted
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
  "hiddenCategories": [ … ],
  "profiles": [ … ],
  "profileData": [ … ],
  "household": { "lists": [ … ], "deleted": { … } },
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
"people": [<person>], "updatedAt", "renamedAt"?, "orderedAt"?, "order"?, "placedAt"?}`. Key: `list:<id>`; time: `updatedAt` (set when the
list is created, renamed, put in order, or its items or people change). `id` is lowercased. `people` (the actors and
directors in the list) may be missing: a device that does not keep people sends none. Each part has its own time
(rule 7 of the merge): `renamedAt` when the name was set, `orderedAt` when the items were put in order (both
`updatedAt` when missing), and each item's and person's `addedAt` (an item's is the list's `updatedAt` when missing,
as an older version sends it). Taking an item or a person out of a list adds
`listitem:<list id>|<playlist>|<kind>|<item>` or `listitem:<list id>|person:<name>` to `deleted` (list and playlist IDs
lowercased, the name folded as for a person). `placedAt` is when the list was last moved into the space it is in (see
**Shared lists**); a list never moved has none, and it is written only when set.

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

The subtitle services searched from the player sync the same way, one record per field, each merged on its own (the
later save wins, a removal after it clears it on every device): `opensubtitles` (OpenSubtitles' API key),
`opensubtitles-username` and `opensubtitles-password` (the optional account), `subdl` and `subsource` (their API
keys), and `addic7ed`, whose `value` is `on` or `off` (turning Addic7ed off is a value, never a removal; there is no
record until it was switched once). A password is kept as typed, spaces included; readers leave out a key that holds
a space or a control character, and a password that holds a control character. Each device keeps them where it
keeps these keys (the keychain on Apple, a private file of the app on Android), never in the settings it sends, and
dates once, when it starts, those saved before they synced. A save dates every field of the service with one same time
(the key and the account travel as one, never paired with another device's), whichever field changed. A device that
kept these records, before this version, among the keys it only passes on moves them to where it keeps them when it
starts, once. These keys, like the TMDb token, are every profile's: a removal goes into `deleted` (the snapshot's own),
whichever profile is open. Only what the user typed travels: never the session
OpenSubtitles gives after a login. A device leaves a service whose key is being checked as it is until the check ends;
the key then saved is dated after what sync brought. A device of an earlier version keeps and passes them on as the
keys of services it does not know.

**Rows** (the order of the rows of a tab, Settings → Row order) — `{"tab", "order", "hidden", "updatedAt"}`. `tab`
is `movie`, `series` or `home`; `order` lists the rows of the tab by key, in the viewer's order; `hidden` the keys of
the rows the viewer hid. In Movies and Series, a row's key is `all` (All movies or All series), `continue` (Continue
Watching), `mylist`, `new` (new movies or series), `trending`, `awards` (award winners: films in Movies, series in Series), or
`cat:<category id>|<name>` for a provider category. Home's rows (below its carousel, which stays at the top) are
`continue-movies` and `continue-series` (the movies and series in progress), `mylist`, `new-movies`, `new-series`,
`trending`, `collections` (the viewer's lists, on Apple devices) and `channels` (the channels in My List, or those
watched lately); by default in that order, `channels` right after `mylist` on a playlist without movies or series. A
device that does not show a row of Home keeps its key where it is, as for a category gone. A category's key holds its
ID as the playlist gives it (`movie-12`, without control characters or `|`), then its name trimmed, runs of spaces and
control characters made one space, A to Z in lowercase (other letters keep their case). A device finds a
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
UTF-16 order (a merge keeps the same 8; `home` is among the others). A device keeps and passes on the record of a tab it
does not know: a version from before Home's order passes `rows:home` on as it came. A snapshot without `rows` (from an older
version) changes nothing; no record is ever deleted. An older Android device drops `rows` from what it keeps and
passes on: two Apple devices that sync only through it keep each their own order. Each profile has its own order: the
main profile's is `rows`, another profile's is in its `profileData`. The order a device kept before profiles had one
each is the main profile's, with its time. A Kids profile cannot change its order.

**Hidden categories** (the categories, languages and countries, and styles a profile hides: Settings → Categories,
"Show only your languages?") — `{"kind", "value", "hiddenAt"}`, one record for each thing hidden:

- `kind` `category`: one category of a playlist; `value` is `<playlist id>|<category id>`, the playlist's ID in lowercase,
  then the category's ID as both apps make it from the playlist: `<type>-<the provider's ID>` for Xtream (`movie-12`,
  `live-7`), `<type>-<hash of its name>` for M3U (the same `StableHash` on both platforms), the ID the titles name for a
  category the panel does not list. Both platforms make the same ID from the same playlist, and the playlist's ID is the
  one sync gives it (rule 4 of the merge makes one ID of the same playlist added twice), so the key is the same on every
  device. Not the category's name: a panel can give two categories the same name, the app's own placeholder names
  ("Other", "Uncategorized") are shown translated, and a category the provider renames would show again.
- `kind` `tag`: a language or country tag, hidden in every playlist; `value` is the tag as both apps read it from the
  names, in capitals (`PT` for "|PT| Filmes", `EX-YU`).
- `kind` `word`: a style or a word, hidden in every playlist; `value` as both apps keep it (`manga`, a word typed in
  lowercase without accents).

Key: `hide:<kind>:<value>`; time: `hiddenAt` (set when it is hidden). Showing it again removes the record and adds its key
to `deleted`, dated then. When more than 50 categories of one playlist and type are shown again at once (Show all on a
large panel), the device adds instead one marker for that playlist and type, `hide:category:<playlist id>|<type>-*`
(`movie-*`, `live-*`, `series-*`), dated then: it shows again every category of that playlist whose ID starts with
`<type>-` and whose `hiddenAt` is not later (rule 3, as for its own key), and the device dates the categories of that
type it still hides just after it. Thousands of categories shown again thus add one key per type to `deleted`, not one
per category; 50 or fewer add one key each. The marker reaches further than the categories the viewer chose, in two
ways: a category of that playlist and type that another device hid before the marker, and had not synced yet, is shown
again everywhere for good; and a device that meets this one only through an older version (which passes the marker on
but not the categories dated after it) shows again, until it meets a newer device, the categories this one kept hidden.
A Kids profile on a device where a parent set a PIN keeps its own (below). Each record
merges on its own (rule 2): hiding a category on one device and showing a language
again on another keep both changes; a showing again later than the hide wins, a hide later than the showing again wins,
and on equal times the showing again wins (rule 3). Readers drop records whose `kind` or `value` is blank, holds a control
character (below U+0020, or U+007F) or is longer than 256 UTF-16 units, whose `kind` holds `:`, and a `category` whose
`value` has no `|`; they lowercase the playlist ID of a `category`, and of `hide:category:` keys in `deleted`. Readers then
keep the 5000 keys hidden last (the later `hiddenAt` first, then the smaller key in UTF-16 code units), in the snapshot's
list and in each profile's (a merge keeps the same 5000); the device keeps its own past that limit, unless shown again. A
device keeps and passes on the records of a kind it does not know. A category of a deleted playlist goes with it (rule 5);
one of a replaced playlist becomes the kept one's (rule 1). The list is left out when it is empty, in the snapshot and in
`profileData`: a snapshot without any is written, and digested, as an older version writes it. A snapshot without
`hiddenCategories` (from an older version) shows nothing again: only `deleted` does. An older version drops the list from
what it keeps and passes on, but keeps the `hide:` keys of `deleted` as any other and passes them on: a showing again
travels through it, a hide does not (it travels once the devices meet a newer one), and nothing is lost. Each profile has
its own: the main profile's is `hiddenCategories`, another profile's is in its `profileData`. A Kids profile cannot change
its own (a parent can, docs/PROFILES.md); on a device where a parent set a PIN, a Kids profile keeps what it hides there
when another device (where no PIN guards it) shows it again: the merge keeps the later showing again, the device keeps its
hide with its own time, and nothing changes from one session to the next, as for the Kids switch; a hide received always
applies. Categories that look adult stay hidden in a Kids profile whatever the list says. The categories of a file
playlist, on its device only, are not sent.

**Profiles** (docs/PROFILES.md) — `{"id", "name", "colorIndex", "symbol", "kids", "updatedAt"}`. `id` is a UUID in
lowercase; the main profile's is `00000000-0000-0000-0000-000000000001`. `symbol` is `initial`, an icon name or an
emoji. Never a PIN: a profile's PIN stays on its device. Key: `profile:<id>`; time: `updatedAt` (set when the profile is
added, renamed, or its colour, avatar or Kids switch changes; a new PIN alone is no change). A profile never changed
(the main one as each device makes it, "Me") is not sent. Deleting a profile adds `profile:<id>` to `deleted`; the main
profile is never deleted (a deletion of it is ignored). Readers keep 16 profiles at most: the main one, then by ID.

**Profile data** — `{"profile", "favorites"?, "progress"?, "lists"?, "people"?, "rows"?, "hiddenCategories"?, "deleted"?}`: the records of a
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

**Shared lists** (docs/PROFILES.md, "Shared lists") — `household`: `{"lists"?, "deleted"?}`, the lists shared with
every profile and what was deleted among them (`listitem:<id>|…` keys, and `list:<id>` from a list deleted for every
profile), merged by the same rules as a profile's lists (rules 2 to 7, as a space of its own whose playlists are the merged
ones) and by rule 9. Left out when there is none: a snapshot without shared lists is written, and digested, as before. A
shared list is in no profile's data: it is in the household, the same list for every profile.

Two keys of the snapshot's own `deleted` (the main profile's, which every version keeps and passes on, whatever the key)
note what happens to shared lists:

- `listmoved:<space>|<id>`: list `id` moved out of `space` (`household`, or a profile's ID, the main profile's
  `00000000-0000-0000-0000-000000000001` included) at that time. It is never `list:<id>`: an older device would apply that
  deletion to its copy and pass it on.
- `sharedlistdeleted:<id>`: shared list `id` deleted for every profile at that time.

What each change does:

- **Sharing** a list of a profile (the main one's or another's) moves it, whole and under the same ID, at two times
  taken one after the other: `listmoved:<that profile>|<id>` at the first, and the household has it at the second, as its
  `updatedAt` and its `placedAt`. The names, items, people and times of its parts do not change, and the deletions of
  its items (`listitem:<id>|…`) go with it.
- **Taking a list back** (the switch turned off, by any profile that may edit shared lists) moves it the other way, into
  the profile that takes it back: `listmoved:household|<id>`, then that profile has it later, with `placedAt`. The other
  profiles lose it.
- **Deleting** a shared list deletes it for every profile: `sharedlistdeleted:<id>`, and `list:<id>` in the household's
  `deleted`.
- A Kids profile sees the shared lists (without what its rules hide) and changes none of them.

Rule 9 of the merge makes a list end in one space, and never loses one that was not deleted. A device that still has the
list where it was merges it into the list where it went once it has both, changes included (made before or after the
move). Until then it keeps it where it was.

**Older devices.** Shared lists do not travel through an older device: it ignores `household` and `placedAt`. It keeps and
passes on the two keys above, and loses nothing:

- It keeps its own copy of a list a profile shared, as that profile's list: it cannot show a shared list, and removing the
  copy would lose the list there. Each device that shares lists merges that copy into the shared list again, changes made
  on the older device included, so nothing changes from one session to the next.
- Two devices that share lists and only meet through an older one (an iPhone and an iPad behind an Android TV not yet
  updated) never lose a list:
  - A list shared on one stays where it was on the other (in that profile's lists, not shared) until the two meet, or meet
    another device that shares lists. It is then shared, once, on both.
  - A list taken back on one goes into that profile on the other too: the move out of the household outdates its shared
    copy (`fixtures/sync/interop/old-relay-*`).

The household's deletions learned from another device are kept from when they were learned, as a profile's are (see
**Deleted**).

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
     line feed (no key holds one);
   - hidden category: none (two records of the same key and time are the same).
3. A record whose time is not later than its key's deletion time is dropped (on equal times, the deletion wins).
4. **The same playlist added twice** (on two devices before they synced): playlists of the same `kind`, `url`
   (trimmed, then compared ignoring case) and `username` are one playlist. The one with the smallest `addedAt`
   (then the smallest `id`) stays; each other one is dropped and its `id` replaced by the kept one in every
   favourite, progress and list item, `playlist:<dropped id>` is added to `deleted` with the dropped
   playlist's `updatedAt`, and `<dropped id>` → `<kept id>` to `replaced`. Records that now share a key are merged
   again by rule 2.
5. A favourite, progress, list item or hidden category whose playlist is deleted (`playlist:<id>` is in `deleted` and no kept
   playlist has that ID) is dropped. One whose playlist is simply unknown (a file playlist of another device) is
   kept.
6. The result lists playlists by `addedAt` then `id`, favorites by `addedAt` then key, progress by `watchedAt`
   (latest first) then key, lists by `id`, people by `addedAt` then key, keys by `service`, rows by `tab`, and hidden
   categories by key.
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
9. **A list is in one space.** The spaces are the household (the shared lists), the main profile (the snapshot's own
   lists) and each profile kept other than the main one. This rule applies to a list found in two spaces or more (moved on
   one device while another still had it where it was), or deleted for every profile. Such a list ends in one space:
   - **Deleted copies.** A space's copies are deleted when their latest `updatedAt` is not after that space's own
     `list:<id>` deletion, or not after the list's deletion for every profile (the later of `sharedlistdeleted:<id>` and the
     household's `list:<id>`). A list deleted for every profile thus goes from every profile, unless changed after the
     deletion (as rule 7).
   - **Where it goes.** Of the spaces left, those whose copies are later than the list's move out of them
     (`listmoved:<space>|<id>`) keep it. The one it was moved into last wins: the latest `placedAt` of its copies (0 when
     none), then the household, the main profile, the other profiles by ID. When every space left was moved out of (the
     copy where it went did not reach this device), the list stays where it is, by the same order: a move never loses a
     list.
   - **What it holds.** The list there is every copy of the spaces left, merged by rule 7, without the items whose
     `listitem:` deletion, in any space, is not earlier than their `addedAt`. A change made where the list was, before or
     after a move this device had not heard of, is carried, whatever the order of the merges. Its `placedAt` is the latest
     of them.
   - **Nothing else.** No other space gets a deletion: an older device keeps its copy (see **Shared lists**).

A device keeps the merged snapshot as its own: it adds the playlists it did not have (and downloads them),
updates the ones that changed, removes the deleted ones, and replaces its My List, favourite channels, people, lists (each profile's and the shared ones) and
progress with the merged ones, saves the keys of the services it uses (removing those deleted), keeps the
merged order of the rows, and hides what the merge hides (with its own past the limit of 5000, unless shown again since).
It keeps
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
   saved, a category, language or style hidden or shown again, any deletion) is dated `max(wall clock, last + 1)`, which becomes `last`. A change made after the device
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

Snapshots hold playlist logins and passwords, and the keys of outside services (the TMDb token, the subtitle services'
keys and OpenSubtitles login), in clear, as the
trusted network was chosen over encryption. They
travel only between Ondo devices with sync on, on the local network; Ondo sends them nowhere else. Turning sync off
stops the server, the advertising and the browsing at once.
