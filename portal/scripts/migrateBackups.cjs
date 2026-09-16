/**
 * Migration script: Add Firestore metadata for existing Storage backups
 *
 * Makes portal timeline work exactly like Android: reads metadata from
 * agents/{uid}/backups/{timestamp}. Legacy Storage-only backups lack this
 * metadata, so this script creates it.
 *
 * Auth: reuses the refresh token stored by `firebase login`
 * (~/.config/configstore/firebase-tools.json). No service account key needed.
 *
 * Run: node scripts/migrateBackups.js [--dry-run]
 */

const fs = require("fs");
const os = require("os");
const path = require("path");

const PROJECT_ID = "healthagentforms";
const BUCKET = "healthagentforms.firebasestorage.app";
// Public OAuth client used by firebase-tools itself
const CLIENT_ID = "563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com";
const CLIENT_SECRET = "j9iVZfS8kkCEFUPaAeJV0sAi";

const DRY_RUN = process.argv.includes("--dry-run");

function loadRefreshToken() {
  const cfgPath = path.join(os.homedir(), ".config", "configstore", "firebase-tools.json");
  if (!fs.existsSync(cfgPath)) {
    throw new Error(`Firebase CLI config not found at ${cfgPath}. Run 'firebase login' first.`);
  }
  const cfg = JSON.parse(fs.readFileSync(cfgPath, "utf8"));
  const token = cfg.tokens?.refresh_token;
  if (!token) throw new Error("No refresh_token in firebase-tools.json. Run 'firebase login' first.");
  return token;
}

async function getAccessToken(refreshToken) {
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      refresh_token: refreshToken,
      grant_type: "refresh_token",
    }),
  });
  if (!res.ok) throw new Error(`Token exchange failed: ${res.status} ${await res.text()}`);
  const data = await res.json();
  return data.access_token;
}

async function listBackupFiles(accessToken) {
  const files = [];
  let pageToken = null;
  do {
    const url = new URL(`https://storage.googleapis.com/storage/v1/b/${BUCKET}/o`);
    url.searchParams.set("prefix", "backups/");
    url.searchParams.set("maxResults", "1000");
    if (pageToken) url.searchParams.set("pageToken", pageToken);
    const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (!res.ok) throw new Error(`Storage list failed: ${res.status} ${await res.text()}`);
    const data = await res.json();
    for (const item of data.items || []) {
      if (item.name.endsWith(".json")) files.push(item.name);
    }
    pageToken = data.nextPageToken || null;
  } while (pageToken);
  return files;
}

async function downloadObject(name, accessToken) {
  const url = `https://storage.googleapis.com/storage/v1/b/${BUCKET}/o/${encodeURIComponent(name)}?alt=media`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (!res.ok) throw new Error(`Download failed (${name}): ${res.status}`);
  return res.text();
}

function firestoreDocUrl(uid, ts) {
  return `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/agents/${encodeURIComponent(uid)}/backups/${ts}`;
}

async function docExists(uid, ts, accessToken) {
  const res = await fetch(firestoreDocUrl(uid, ts), {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (res.status === 200) return true;
  if (res.status === 404) return false;
  throw new Error(`Firestore read failed: ${res.status} ${await res.text()}`);
}

async function createMetadataDoc(uid, ts, fields, accessToken) {
  // Firestore REST create document: POST without documentId auto-generates;
  // we want explicit id -> use PATCH on the doc URL (create or overwrite).
  const body = {
    fields: {
      timestamp: { integerValue: String(ts) },
      storagePath: { stringValue: `backups/${uid}/${ts}.json` },
      houseCount: { integerValue: String(fields.houseCount) },
      activityCount: { integerValue: String(fields.activityCount) },
      agentName: { stringValue: fields.agentName || "Desconhecido" },
    },
  };
  const res = await fetch(firestoreDocUrl(uid, ts), {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Write failed: ${res.status} ${await res.text()}`);
}

async function main() {
  console.log(`Migration ${DRY_RUN ? "(DRY RUN) " : ""}started for project ${PROJECT_ID}`);
  const refreshToken = loadRefreshToken();
  const accessToken = await getAccessToken(refreshToken);
  console.log("Authenticated via Firebase CLI refresh token.");

  const files = await listBackupFiles(accessToken);
  console.log(`Found ${files.length} backup .json files in Storage.`);

  let migrated = 0;
  let skipped = 0;
  let failed = 0;

  for (const name of files) {
    // Path format: backups/{uid}/{timestamp}.json
    const parts = name.split("/");
    if (parts.length !== 3) {
      console.log(`Skipping unexpected path: ${name}`);
      continue;
    }
    const uid = parts[1];
    const tsStr = path.basename(parts[2], ".json");
    const ts = Number(tsStr);
    if (!Number.isFinite(ts)) {
      console.log(`Skipping non-timestamp filename: ${name}`);
      continue;
    }

    try {
      if (await docExists(uid, tsStr, accessToken)) {
        skipped++;
        continue;
      }

      const json = await downloadObject(name, accessToken);
      const data = JSON.parse(json);
      const houseCount = Array.isArray(data.houses) ? data.houses.length : 0;
      const activityCount = Array.isArray(data.dayActivities) ? data.dayActivities.length : 0;
      const agentName =
        (typeof data.sourceAgentName === "string" && data.sourceAgentName) ||
        (typeof data.agentName === "string" && data.agentName) ||
        "Desconhecido";

      console.log(`Migrating ${name}: agent=${agentName}, houses=${houseCount}, activities=${activityCount}`);
      if (!DRY_RUN) {
        await createMetadataDoc(uid, ts, { houseCount, activityCount, agentName }, accessToken);
      }
      migrated++;
    } catch (err) {
      failed++;
      console.error(`Failed on ${name}:`, err.message);
    }
  }

  console.log(`\nDone. migrated=${migrated} skipped(already existed)=${skipped} failed=${failed}${DRY_RUN ? " (dry run, nothing written)" : ""}`);
}

main().catch((err) => {
  console.error("Migration failed:", err.message);
  process.exit(1);
});
