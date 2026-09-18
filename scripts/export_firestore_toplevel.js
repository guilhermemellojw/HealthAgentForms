#!/usr/bin/env node
/**
 * Export top-level Firestore collections for Supabase seeding (read-only).
 * Complementa export_firestore_snapshot.js (que cobre apenas agents/*).
 *
 * Usage: node scripts/export_firestore_toplevel.js
 * Output: scripts/firestore_export/toplevel.json
 *   { users, metadata, access_requests, day_transfers, admins, supervisors, admin_audit }
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
const OUT_FILE = path.join(__dirname, "firestore_export", "toplevel.json");
const COLLECTIONS = ["users", "metadata", "access_requests", "day_transfers", "admins", "supervisors", "admin_audit"];

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

  const out = {};
  for (const col of COLLECTIONS) {
    out[col] = [];
    await fetchAll(access_token, `${BASE}/${col}?pageSize=300`, out[col]);
    console.log(`${col}: ${out[col].length}`);
  }

  // agents/{uid}/backups (metadados dos backups; snapshot.js não cobre)
  out.backups = [];
  let agentIds = [];
  try {
    const snap = JSON.parse(
      fs.readFileSync(path.join(__dirname, "firestore_export", "snapshot.json"), "utf8")
    );
    agentIds = snap.map((a) => a.id);
  } catch (_) {}
  for (const aid of agentIds) {
    const docs = [];
    await fetchAll(access_token, `${BASE}/agents/${aid}/backups?pageSize=200`, docs);
    for (const d of docs) out.backups.push({ agentId: aid, ...d });
  }
  console.log(`backups: ${out.backups.length}`);
  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, JSON.stringify(out, null, 2));
  console.log(`\nSaved: ${OUT_FILE}`);
}

main().catch((e) => {
  console.error("ERR:", e.message);
  process.exit(1);
});
