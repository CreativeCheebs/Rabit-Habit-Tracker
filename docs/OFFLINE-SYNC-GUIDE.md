# Offline first with a sync server: a design guide for a new app

This guide is for building a **new habit tracker** from scratch, in **one
language (Kotlin)**, that works **fully offline** and **syncs through a server**.
It takes everything learned while building sync for JitterBug (a task board
and time log written in Go with SQLite) and writes it down in plain terms, so
the new app gets it right from the first day instead of adding it later.

It is written for a person or an AI that has never seen JitterBug.

---

## 1. Decide this on day one: offline app + sync server

JitterBug started as a web app that talked to one server. Later it got a
phone app, then offline use, then sync. Each step changed what "the data"
meant, and it took a long time to settle on the right shape. The shape is:

> **Every device keeps its own complete copy of the data and always works
> on that copy. A sync server keeps one more copy and passes changes between
> devices. The server is a meeting point, not the place the app reads from.**

```
  Phone (own SQLite)  ⇄  Sync server (own SQLite)  ⇄  Tablet / web (own SQLite)
```

What that means in practice:

- **The app never waits for the network.** Opening the app, ticking a habit or
  editing a reminder all read and write the device's own database, instantly.
- **Sync happens in the background**: on opening the app, a few seconds after a
  change, every few minutes, and from a "Sync now" button. A failed sync just
  waits for the next one.
- **The server is optional for the user.** On first start the app asks:
  *Connect to a server* or *Use offline*. Offline users can connect later.
- **Devices never sync with each other directly**, only through the server.
- **The first connection is a full download.** Show it as a progress screen
  (sending, then downloading %, then saving) and let the user wait for it once.
  After that, syncs are small and silent.

Build these from the first version, even if there is only one device. Adding
sync to an app that was built without it means touching every table and
every screen.

---

## 2. One language: Kotlin everywhere

JitterBug ended up with **Go** on the server and inside the phone, plus
**Kotlin** for the phone's screens and networking. Two languages meant two
builds, a bridge between them, and sync code that only one side could read.

For the new app, use **Kotlin for everything**:

| Part | Kotlin piece |
| --- | --- |
| Screens on Android, iOS, desktop, web | Compose Multiplatform |
| Local database on each device | SQLite through SQLDelight (one set of `.sq` files, drivers for Android, iOS, JVM; the web driver runs SQLite as WebAssembly in the browser) |
| Sync engine (export, merge, apply) | A shared Kotlin module, `sync-core`, in common code |
| Sync server | Ktor (Kotlin server) + SQLite through the same SQLDelight files |
| Talking to JitterBug later | Plain HTTP calls to JitterBug's API (section 9) |

The big win: **the same `sync-core` code runs on the phone and on the
server.** The merge rules are written and tested once.

Notes:
- Compose for web (Kotlin/Wasm) and SQLite in the browser are the newest,
  least proven pieces. Try a small "write, reload, read back" test on the web
  target early, before building screens on it. If it is a struggle, ship
  Android first: the design does not change.
- Keep the app's screens talking **directly to the local database** (through a
  repository layer). JitterBug ran a whole HTTP server inside the phone, with a
  hidden local account; that was only because its Go code was a server. A
  Kotlin app doesn't need that.

---

## 3. The three bookkeeping tables

Sync adds three tables to every database (device and server alike). The
app's own tables (`habit`, `check_in`, `reminder`, …) stay normal.

| Table | Holds | Example |
| --- | --- | --- |
| `sync_meta` | A few settings of this database | `device = 5751cba6106e05db`, `clock = 1793808555617`, `seq = 40`, `token = 12`, `pushed = 7`, `applying = 0` |
| `sync_idmap` | For every row: its local integer id and its **uid**, the id every device agrees on | `('habit', 12, '9265ffe59ad5…')` |
| `sync_field` | For every row and field: when it last changed (`clock`), on which device, and the local counter `seq` | `('habit', '12', 'name', 1793808555617, 'a1b2…', 38)` |

`sync_meta` keys:

- **device**: random id of this database, made once on first start.
- **clock**: the last "time" handed out, in milliseconds (section 5).
- **seq**: a local counter, +1 for every change. It answers "what changed since X?".
- **token** (devices): how far into the server's `seq` this device has received.
- **pushed** (devices): how far into its own `seq` the server already has.
- **applying**: 1 while changes from elsewhere are being written, so they are not recorded as new local changes.

**Why both an id and a uid?** Two phones offline can both create "habit 12".
So local ids can't be shared. Each row gets a random uid (128 bits, hex) that
travels between devices; each database maps uid ↔ its own id in `sync_idmap`.

> Simpler option for a brand-new app: make the **uid the primary key** of every
> table (a text column) and skip `sync_idmap`. JitterBug used a lookup table
> only because it added sync to tables that already had integer ids.

**Built-in rows** (default categories, a starter habit list…) get **fixed
uids** like `b-category-health`, so every device's "Health" is one record.

---

## 4. Recording changes with triggers

The app code never calls "record a change". **SQLite triggers** do it, so
every write is caught, from any screen, import or bulk edit. For each synced
table:

- **AFTER INSERT**: record field `_row` (meaning "all fields of a new row").
- **AFTER UPDATE**: for each column where `NEW.col IS NOT OLD.col`, record that field.
- **AFTER DELETE**: record field `_deleted` (a **tombstone**, kept forever, small).
- Each trigger first bumps `seq` and `clock`, and does nothing while `applying = 1`.

Generate the triggers from one list of synced tables, and re-create them on
every start, so a new column is covered automatically. `sync_field` keeps
**only the latest change per field** (primary key `tbl, key, field`), so it
never grows bigger than the data.

The SQL, for one column (the generator writes one of these per column):

```sql
CREATE TRIGGER sync_habit_update AFTER UPDATE ON habit
WHEN (SELECT value FROM sync_meta WHERE key = 'applying') = '0'
BEGIN
  UPDATE sync_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'seq';
  UPDATE sync_meta SET value = max(CAST(value AS INTEGER) + 1,
    CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)) WHERE key = 'clock';
  INSERT INTO sync_field (tbl, key, field, clock, device, seq)
    SELECT 'habit', CAST(NEW.id AS TEXT), 'name',
      (SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'clock'),
      (SELECT value FROM sync_meta WHERE key = 'device'),
      (SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'seq')
    WHERE NEW.name IS NOT OLD.name
    ON CONFLICT (tbl, key, field) DO UPDATE
      SET clock = excluded.clock, device = excluded.device, seq = excluded.seq;
END;
```

---

## 5. The clock

Each change gets `clock = max(last clock + 1, now in ms)`. When applying
changes from another device, the local clock jumps to at least the largest
clock seen. So the clock never goes backwards, and a phone set to the wrong
date can't win every conflict forever. This is called a *hybrid logical clock*.

Comparing two changes: the higher clock wins; on an equal clock, the higher
device id wins, so every device picks the same winner.

---

## 6. One sync, step by step

One HTTP call: `POST /api/sync`.

1. **Device builds its request**: every row with a field changed after
   `pushed`, made on this device. If `pushed = 0` (never synced) it sends
   everything. It remembers `upto` = its current `seq`.
2. **Device sends** `{device, token, changes: [rows]}`.
3. **Server applies** the rows (section 7), in one transaction.
4. **Server answers** `{token: server's seq, changes: [rows]}`: every row
   changed after the device's `token`, **except changes that came from this
   same device**. If `token = 0`, a full copy (the first download). A header
   with the answer's size before compression lets the device show a percentage.
5. **Device applies** the answer in one transaction, then saves
   `token = answer.token` and `pushed = upto`.

If anything fails, nothing is half-written, and the next sync simply repeats.

**One row on the wire:**

```json
{"t": "check_in", "u": "9265ffe5…",
 "v": {"habit_id": "efdc596b…", "day": "2026-11-04", "done": 1},
 "s": {"_row": {"c": 1793808000000, "d": "a1b2…"}, "done": {"c": 1793808555617, "d": "a1b2…"}}}
```

`t` = table, `u` = uid, `v` = values, `s` = when each field last changed.
**References to other rows travel as uids**, never local ids. If a JSON column
holds ids (for example a filter `{"habits":[3,5]}`), translate those too.

---

## 7. The merge rules

For each incoming row:

1. **Unknown uid** → insert it, store its stamps.
2. **Known uid, but the row is gone here** → it was deleted here. **A delete wins**: skip.
3. **Incoming tombstone** → delete the row here, keep the tombstone.
4. **Both have it** → **field by field, the later change wins.** Rename a
   habit on the phone and change its colour on the web: both survive.
5. **Neither side knows when that field changed** (rows that existed before
   sync, or seeded defaults) → **the server's value wins**, everywhere.
6. **Same stamp, different values** → every side keeps the greater value, so they agree.

Apply rows **in table order**, parents before children (`category` → `habit`
→ `check_in`), and deletes before inserts within a table.

### Rules a habit tracker will need to decide

- **Check-ins have a natural key.** "Habit X on day D" should be one row even
  if two phones tick it offline. Give it the uid `habitUid + ":" + day`, so
  both phones make the same record and the merge is automatic.
- **Counters and streaks are worked out, never stored.** A streak is computed
  from check-ins when shown. If you store "streak = 12" on two devices, they
  will fight. (JitterBug's rule: every derived value is computed on read.)
- **Append-only history** (for example a log of every tick and untick) never
  conflicts: keep every entry from every device, and work out the current
  state from the latest one. JitterBug does this for a task's status.
- **Unique names**: decide what happens when two devices both create
  "Meditate". Either allow duplicates, or merge by name on first sync. Never
  skip silently (see lesson 3).

---

## 8. Lessons learned (don't repeat these)

1. **Android has no temp folder SQLite can find.** Big sorts or transactions
   fail with "disk I/O error" 6410 (`SQLITE_IOERR_GETTEMPPATH`). Run
   `PRAGMA temp_store = MEMORY` when opening every database.
2. **Rows that exist before sync has no stamps.** Decide who wins for them
   from the start; the server is the safe choice. JitterBug first let the
   "greater" value win, and a new phone's default settings overwrote the
   owner's customised views on the live server.
3. **UNIQUE constraints can silently drop rows.** A phone's area "Personal" was
   rejected by a server that already had one, and everything in it was
   dropped too. Merge by name, or show the problem. Never skip silently.
4. **Say where a sync failed**: "This phone's data: …", "Can't reach the
   server: …", or "Server answered …". One vague message hid the real cause
   for a long time.
5. **The first sync is a download, not a sync.** Show progress, give it a long
   timeout (slow links such as Tailscale through a relay), and remember
   whether it finished, so an interrupted one starts again.
6. **Test with two real databases.** Make two databases, change both offline
   (including the same field, a delete against an edit, two new rows with the
   same name), sync both ways twice, and check they are identical row by row.
   Write this test before the screens.

---

## 9. Connecting to JitterBug later

The habit tracker and JitterBug stay **separate apps with separate
databases**. They do **not** sync with each other. They talk only through
**API calls**, like any two web services:

- The habit tracker's server calls JitterBug's HTTP API (for example to log a
  session, or to read a day's totals), signed in with a JitterBug account.
- Keep that code in one small "JitterBug connector" module on the server,
  never inside the sync engine.
- If the habit tracker needs JitterBug data offline, copy what it needs into
  its own tables as ordinary rows, and let its own sync carry them.

---

## 10. Build order for the new app

1. Schema in SQLDelight `.sq` files: app tables + the three sync tables.
2. Trigger generator from the list of synced tables; a test that every write records a change.
3. `sync-core` in shared Kotlin: wire types, export, apply with the merge rules.
4. The two-database test from lesson 6, passing.
5. Ktor sync server with `POST /api/sync`, sign-in, and one database per user.
6. App first start: *Connect to a server* or *Use offline*; the first-download progress screen.
7. Background sync loop, "Sync now", and clear error messages.
8. Then the habit screens, which only ever touch the local database.
