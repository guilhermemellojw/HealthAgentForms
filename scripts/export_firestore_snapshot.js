#!/usr/bin/env node
/**
 * Export Firestore snapshot for auditing (read-only).
 * Uses the Firebase CLI session token (guigomelo9@gmail.com) to authenticate.
 *
 * Usage:
 *   node scripts/export_firestore_snapshot.js
 *
 * Output: scripts/firestore_export/snapshot.json
 *   { agents: [{ id, doc, houses: [...], activities: [...], summaries: [...] }] }
 */
const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

function resolveFirebaseTools() {
  const candidates = [];
  try {
    const root = execSync("npm root -g", { encoding: "utf8" }).trim();
    candidates.push(path.join(root, "firebase-tools", "lib", "auth.js"));
  } catch (_) {}
  candidates.push("/usr/lib/node_modules/firebase-tools/lib/auth.js");
  candidates.push("/usr/local/lib/node_modules/firebase-tools/lib/auth.js");
  for (const c of candidates) {
    if (fs.existsSync(c)) return require(c);
  }
  throw new Error("firebase-tools not found. Install with: npm install -g firebase-tools");
}
const firebaseToolsAuth = resolveFirebaseTools();
const { getGlobalDefaultAccount, getAccessToken } = firebaseToolsAuth;

const BASE = "https://firestore.googleapis.com/v1/projects/healthagentforms/databases/(default)/documents";
const OUT_DIR = path.join(__dirname, "firestore_export");
const OUT_FILE = path.join(OUT_DIR, "snapshot.json");

function decodeValue(v) {
  if (!v || typeof v !== "object") return v;
  if ("stringValue" in v) return v.stringValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return Number(v.doubleValue);
  if ("booleanValue" in v) return v.booleanValue;
  if ("nullValue" in v) return null;
  if ("timestampValue" in v) return v.timestampValue;
  if ("arrayValue" in v) return (v.arrayValue.values || []).map(decodeValue);
  if ("mapValue" in v) return decodeFields(v.mapValue.fields);
  return JSON.stringify(v);
}

function decodeFields(fields) {
  const out = {};
  for (const [k, v] of Object.entries(fields || {})) out[k] = decodeValue(v);
  return out;
}

function decodeDoc(doc) {
  const name = doc.name.split("/");
  return { id: name[name.length - 1], ...decodeFields(doc.fields) };
}

async function fetchAll(token, url, out) {
  let next = url;
  while (next) {
    const res = await fetch(next, { headers: { Authorization: "Bearer " + token } });
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`${res.status} ${res.statusText} for ${next}: ${body.slice(0, 300)}`);
    }
    const body = await res.json();
    for (const d of body.documents || []) out.push(decodeDoc(d));
    next = body.nextPageToken ? `${url}&pageToken=${body.nextPageToken}` : null;
  }
}

async function main() {
  const acc = getGlobalDefaultAccount();
  if (!acc?.tokens?.refresh_token) {
    console.error("No Firebase CLI session. Run: firebase login");
    process.exit(1);
  }
  const { access_token } = await getAccessToken(acc.tokens.refresh_token, [
    "https://www.googleapis.com/auth/cloud-platform",
  ]);

  const agentsOut = [];
  await fetchAll(access_token, `${BASE}/agents?pageSize=300`, agentsOut);
  console.log(`agents: ${agentsOut.length}`);

  const snapshot = [];
  for (const agent of agentsOut) {
    const prefix = `${BASE}/agents/${agent.id}`;
    const houses = [];
    const activities = [];
    const summaries = [];
    await fetchAll(access_token, `${prefix}/houses?pageSize=500`, houses);
    await fetchAll(access_token, `${prefix}/day_activities?pageSize=500`, activities);
    await fetchAll(access_token, `${prefix}/monthly_summaries?pageSize=200`, summaries);
    snapshot.push({ id: agent.id, agent, houses, activities, summaries });
    console.log(
      `  ${agent.id.slice(0, 8)}... houses=${houses.length} activities=${activities.length} summaries=${summaries.length}`
    );
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(OUT_FILE, JSON.stringify(snapshot, null, 2));
  console.log(`\nSaved: ${OUT_FILE}`);
  process.exit(0);
}

main().catch((e) => {
  console.error("ERR:", e.message);
  process.exit(1);
});