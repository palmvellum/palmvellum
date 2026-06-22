// PalmVellum HotSync conduit.
//
// Runs inside a single live HotSync session (one button press on the Palm):
//
//   1. PULL  — read the named databases off the device into a staging dir
//              as .pdb files (the same filenames the Go card engine expects:
//              MemoDB.pdb, ToDoDB.pdb, DatebookDB.pdb, AddressDB.pdb,
//              MailDB.pdb).
//   2. MERGE — shell out to the Go binary `palmvellum hotsync-merge <dir>`,
//              which runs the EXACT same SyncCardLog engine the card path
//              uses (push → cloud → pull → rewrite .pdb in place). The cloud
//              round-trip is seconds (aiWait=0), so the USB connection stays
//              well within the Palm's session timeout.
//   3. PUSH  — install every (now-merged) .pdb back onto the device.
//
// AI answers for newly-asked memos are NOT waited on here (holding the live
// USB link idle for ~2 min risks a vintage-device timeout); they arrive on
// the next HotSync, exactly like a normal conduit.
//
// Configuration is passed by the Go wrapper via environment variables:
//   PV_STAGE      absolute path to the (empty) staging dir              [required]
//   PV_DBS        comma-separated DB names to sync                      [required]
//   PV_MERGE_BIN  absolute path to the palmvellum binary               [required unless PV_DRY]
//   PV_DRY        "1" → pull only, skip merge + push (transport smoke test)
//
// Invoked by:  palm-sync run <thisfile> --usb

const fs = require('fs');
const path = require('path');
const {spawnSync} = require('child_process');

const {readDbToFile, writeDbFromFile} = require('palm-sync');
// NodeDatabaseStorage isn't re-exported from the package root (only the
// storage *interface* is), so import it from its submodule, like the CLI.
const {
  NodeDatabaseStorage,
  READ_WRITE_TO_BASE_DIR_DIRECTLY,
} = require('palm-sync/dist/database-storage/node-database-storage');

function log(line) {
  // stdout is streamed verbatim into the app's sync log by the Go wrapper.
  process.stdout.write(line + '\n');
}

async function run(dlpConnection) {
  const stage = process.env.PV_STAGE;
  const dbs = (process.env.PV_DBS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const dry = process.env.PV_DRY === '1';

  if (!stage) throw new Error('PV_STAGE not set');
  if (dbs.length === 0) throw new Error('PV_DBS not set');
  fs.mkdirSync(stage, {recursive: true});

  const storage = new NodeDatabaseStorage(stage, READ_WRITE_TO_BASE_DIR_DIRECTLY);

  // ── 1. PULL ────────────────────────────────────────────────────────
  const pulled = [];
  for (const name of dbs) {
    try {
      await readDbToFile(dlpConnection, name, storage);
      pulled.push(name);
      log(`⬇️  pulled ${name}`);
    } catch (e) {
      // A device may simply not have one of the DBs (e.g. no Mail). Skip.
      log(`   (skip ${name}: ${e && e.message ? e.message : e})`);
    }
  }
  if (pulled.length === 0) throw new Error('no databases could be read from the device');

  if (dry) {
    log(`✅ dry run — pulled ${pulled.length} database(s) to ${stage}`);
    return;
  }

  // ── 2. MERGE (Go engine, in place) ─────────────────────────────────
  const bin = process.env.PV_MERGE_BIN;
  if (!bin) throw new Error('PV_MERGE_BIN not set');
  const mergeArgs = ['hotsync-merge', stage];
  if (process.env.PV_MERGE_WAIT) {
    // Hold the live USB link open while the Go side waits for AI answers,
    // so they round-trip back to the device in this same HotSync.
    mergeArgs.push('--wait', process.env.PV_MERGE_WAIT);
    log('☁️  syncing with cloud (waiting for AI answers)…');
  } else {
    log('☁️  syncing with cloud…');
  }
  const r = spawnSync(bin, mergeArgs, {stdio: ['ignore', 'inherit', 'inherit']});
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error(`hotsync-merge exited ${r.status}`);

  // ── 3. PUSH (install merged DBs back) ──────────────────────────────
  // Push every .pdb now in the staging dir, overwriting the device copy.
  // The engine rewrites the same files it read, so this restores the
  // merged result — same semantics as "restore from card".
  const files = fs.readdirSync(stage).filter((f) => f.toLowerCase().endsWith('.pdb'));
  for (const f of files) {
    await writeDbFromFile(dlpConnection, f, storage, {overwrite: true});
    log(`⬆️  installed ${f}`);
  }
  log(`✅ HotSync complete — ${files.length} database(s) updated on the device`);
}

module.exports = {run};
