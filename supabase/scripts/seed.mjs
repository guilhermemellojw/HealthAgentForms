#!/usr/bin/env node
/**
 * Seed Supabase a partir dos exports Firestore (shadow — Firebase segue live).
 * Uso:
 *   SUPABASE_URL=https://<ref>.supabase.co SUPABASE_SERVICE_ROLE=<key> npm run seed
 *
 * Ordem: auth.users (Admin API) -> profiles -> agents -> houses/activities/
 * summaries/backups-meta -> metadata/requests/transfers -> Storage backups/*.
 * Idempotente: re-uso de auth users por e-mail + upserts + upload com upsert.
 * Falhas de Storage são coletadas e reportadas (não abortam o seed relacional).
 */
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { homedir } from "node:os";
import { join } from "node:path";
import { createClient } from "@supabase/supabase-js";

const REPO = "/home/guilherme/Documentos/HealthAgentForms";
const snap = JSON.parse(readFileSync(join(REPO, "scripts/firestore_export/snapshot.json"), "utf8"));
const top = JSON.parse(readFileSync(join(REPO, "scripts/firestore_export/toplevel.json"), "utf8"));

const URL = process.env.SUPABASE_URL;
const KEY = process.env.SUPABASE_SERVICE_ROLE;
if (!URL || !KEY) {
  console.error("Defina SUPABASE_URL e SUPABASE_SERVICE_ROLE.");
  process.exit(1);
}
const sb = createClient(URL, KEY, { auth: { persistSession: false } });

// ---- coercions -------------------------------------------------------------
const epochToIso = (v) => {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? new Date(n).toISOString() : null;
};
const isoOrNull = (v) => {
  if (!v) return null;
  const d = new Date(v);
  return isNaN(d) ? null : d.toISOString();
};
const txt = (v) => (v === null || v === undefined ? null : String(v));
const num = (v, dflt = 0) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : dflt;
};
const bool = (v) => (v === true ? true : v === false ? false : null);
const clean = (o) => {
  const out = {};
  for (const [k, v] of Object.entries(o)) if (v !== undefined) out[k] = v;
  return out;
};
const chunk = (arr, n) => {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
};
// PostgREST exige chaves uniformes por lote (ausentes viram NULL explícito,
// quebrando NOT NULL e DEFAULTs). Agrupa por assinatura de chaves.
const upsertUniform = async (table, rows, onConflict, size = 500) => {
  const groups = new Map();
  for (const r of rows) {
    const sig = Object.keys(r).sort().join("|");
    if (!groups.has(sig)) groups.set(sig, []);
    groups.get(sig).push(r);
  }
  for (const g of groups.values()) {
    for (const c of chunk(g, size)) {
      const { error } = await sb.from(table).upsert(c, { onConflict });
      if (error) throw new Error(`${table} upsert: ${error.message}`);
    }
  }
};
const anomalies = [];
const note = (m) => {
  anomalies.push(m);
  console.log("  ! " + m);
};

// ---- 1. auth users ----------------------------------------------------------
const emailByUid = {}; // firebase uid -> email
for (const u of top.users) if (u.email) emailByUid[u.id] = String(u.email).toLowerCase();
for (const r of top.access_requests) {
  if (r.email && r.uid && !emailByUid[r.uid]) emailByUid[r.uid] = String(r.email).toLowerCase();
}
const emails = new Map(); // lower email -> display name
const wantEmail = (email, name) => {
  if (!email) return;
  const e = String(email).toLowerCase();
  if (!emails.has(e)) emails.set(e, name || null);
};
for (const u of top.users) wantEmail(u.email, u.displayName);
for (const a of snap) wantEmail(a.agent?.email, a.agent?.agentName);
for (const r of top.access_requests) wantEmail(r.email, r.displayName || r.requestedName);

console.log(`auth: ${emails.size} e-mails distintos`);
const { data: existing } = await sb.auth.admin.listUsers({ perPage: 1000 });
const authIdByEmail = new Map((existing?.users || []).map((u) => [String(u.email).toLowerCase(), u.id]));
for (const [email, name] of emails) {
  if (authIdByEmail.has(email)) continue;
  const { data, error } = await sb.auth.admin.createUser({
    email,
    email_confirm: true,
    user_metadata: { name: name || email },
  });
  if (error) {
    // corrida/re-tentativa: busca de novo antes de falhar
    const { data: relist } = await sb.auth.admin.listUsers({ perPage: 1000 });
    const found = (relist?.users || []).find((u) => String(u.email).toLowerCase() === email);
    if (!found) throw new Error(`createUser ${email}: ${error.message}`);
    authIdByEmail.set(email, found.id);
  } else {
    authIdByEmail.set(email, data.user.id);
  }
}
console.log(`auth: ok (${authIdByEmail.size} usuários)`);

const profileIdByFirebaseUid = {};
const profileIdByEmail = {};
for (const [email, id] of authIdByEmail) profileIdByEmail[email] = id;

// ---- 2. profiles ------------------------------------------------------------
const profileRows = [];
const pushProfile = (firebaseUid, { email, displayName, photoUrl, role, isAuthorized, agentName, isPreRegistered, requireDataReset, createdAt }) => {
  const em = email ? String(email).toLowerCase() : null;
  const id = em ? profileIdByEmail[em] : null;
  if (!id) {
    note(`profile sem auth user (e-mail ausente?): firebase_uid=${firebaseUid}`);
    return;
  }
  profileIdByFirebaseUid[firebaseUid] = id;
  profileRows.push(
    clean({
      id,
      firebase_uid: firebaseUid,
      email: em,
      display_name: displayName ?? null,
      photo_url: photoUrl ?? null,
      role: ["ADMIN", "SUPERVISOR", "AGENT"].includes(role) ? role : "AGENT",
      is_authorized: isAuthorized === true,
      agent_name: agentName ? String(agentName).toUpperCase() : null,
      is_pre_registered: isPreRegistered === true,
      require_data_reset: requireDataReset === true,
      created_at: epochToIso(createdAt) || undefined,
    })
  );
};
for (const u of top.users) {
  pushProfile(u.id, {
    email: u.email || emailByUid[u.id],
    displayName: u.displayName,
    photoUrl: u.photoUrl,
    role: u.role,
    isAuthorized: u.isAuthorized,
    agentName: u.agentName,
    isPreRegistered: u.isPreRegistered,
    requireDataReset: u.require_data_reset ?? u.requireDataReset,
    createdAt: u.createdAt,
  });
  if (!u.email && emailByUid[u.id]) note(`users/${u.id.slice(0, 6)} sem e-mail; suprido via access_requests (${emailByUid[u.id]})`);
}
// agents sem linha em users -> perfil a partir do doc do agente
for (const a of snap) {
  if (profileIdByFirebaseUid[a.id]) continue;
  pushProfile(a.id, {
    email: a.agent?.email,
    agentName: a.agent?.agentName,
    photoUrl: a.agent?.photoUrl,
    role: "AGENT",
    isAuthorized: true,
    createdAt: a.agent?.createdAt,
  });
  note(`agents/${a.id.slice(0, 6)} sem linha users; perfil criado pelo e-mail do agente`);
}
await upsertUniform("profiles", profileRows, "id", 100);
console.log(`profiles: ok (${profileRows.length})`);

// ---- 3. agents ---------------------------------------------------------------
const agentRows = snap.map((a) => {
  const em = a.agent?.email ? String(a.agent.email).toLowerCase() : null;
  const id = em ? profileIdByEmail[em] : null;
  if (!id) note(`agents/${a.id.slice(0, 6)} sem perfil (e-mail ${em}); produção órfã ignorada`);
  return id
    ? clean({
        id,
        firebase_uid: a.id,
        email: em,
        agent_name: a.agent?.agentName ?? null,
        photo_url: a.agent?.photoUrl ?? null,
        is_pre_registered: a.agent?.isPreRegistered === true,
        last_sync_time: epochToIso(a.agent?.lastSyncTime),
        last_pull: null,
        app_version_code: a.agent?.appVersionCode ?? null,
        app_version_name: a.agent?.appVersionName ?? null,
      })
    : null;
}).filter(Boolean);
await upsertUniform("agents", agentRows, "id", 100);
console.log(`agents: ok (${agentRows.length})`);
const agentIdByFirebaseUid = Object.fromEntries(agentRows.map((r) => [r.firebase_uid, r.id]));

// ---- 4. houses ---------------------------------------------------------------
const DATE_RE = /^[0-9]{2}-[0-9]{2}-[0-9]{4}$/;
let housesOk = 0;
for (const a of snap) {
  const agentId = agentIdByFirebaseUid[a.id];
  if (!agentId) continue;
  const rows = [];
  for (const h of a.houses || []) {
    if (!DATE_RE.test(h.data || "")) {
      note(`house ${h.id} agente ${a.id.slice(0, 6)} data inválida (${h.data}); ignorada`);
      continue;
    }
    rows.push(
      clean({
        agent_id: agentId,
        natural_key: h.id,
        data_text: h.data,
        street_name: h.streetName ?? null,
        number: txt(h.number),
        block_number: txt(h.blockNumber),
        block_sequence: txt(h.blockSequence),
        sequence: h.sequence ?? null,
        complement: h.complement ?? null,
        visit_segment: h.visitSegment ?? null,
        list_order: h.listOrder ?? null,
        situation: txt(h.situation),
        property_type: txt(h.propertyType),
        com_foco: bool(h.comFoco),
        a1: num(h.a1), a2: num(h.a2), b: num(h.b), c: num(h.c),
        d1: num(h.d1), d2: num(h.d2), e: num(h.e),
        eliminados: num(h.eliminados), larvicida: num(h.larvicida),
        latitude: h.latitude ?? null, longitude: h.longitude ?? null,
        focus_capture_time: isoOrNull(h.focusCaptureTime),
        observation: txt(h.observation),
        municipio: txt(h.municipio), bairro: txt(h.bairro),
        categoria: txt(h.categoria), zona: txt(h.zona),
        tipo: txt(h.tipo), atividade: txt(h.atividade), ciclo: txt(h.ciclo),
        localidade_concluida: bool(h.localidadeConcluida),
        quarteirao_concluido: bool(h.quarteiraoConcluido),
        agent_name: txt(h.agentName), agent_uid: txt(h.agentUid),
        client_uuid: txt(h.uuid),
        edited_by_admin: h.editedByAdmin === true,
        created_at: epochToIso(h.createdAt),
        last_sync_time: epochToIso(h.lastSyncTime),
        updated_at: isoOrNull(h.lastUpdated) || undefined,
      })
    );
  }
  await upsertUniform("houses", rows, "agent_id,natural_key");
  housesOk += rows.length;
}
console.log(`houses: ok (${housesOk})`);

// ---- 5. day_activities --------------------------------------------------------
let actOk = 0;
for (const a of snap) {
  const agentId = agentIdByFirebaseUid[a.id];
  if (!agentId) continue;
  const rows = (a.activities || []).map((d) =>
    clean({
      agent_id: agentId,
      date_text: d.id,
      status: txt(d.status),
      is_closed: bool(d.isClosed),
      is_manual_unlock: bool(d.isManualUnlock),
      agent_name: txt(d.agentName), agent_uid: txt(d.agentUid),
      edited_by_admin: d.editedByAdmin === true,
      updated_at: isoOrNull(d.lastUpdated) || undefined,
    })
  );
  await upsertUniform("day_activities", rows, "agent_id,date_text");
  actOk += rows.length;
}
console.log(`day_activities: ok (${actOk})`);

// ---- 6. monthly_summaries ------------------------------------------------------
let sumOk = 0;
for (const a of snap) {
  const agentId = agentIdByFirebaseUid[a.id];
  if (!agentId) continue;
  const rows = (a.summaries || []).map((s) =>
    clean({
      agent_id: agentId,
      month_year: s.id,
      treated_count: num(s.treatedCount), focus_count: num(s.focusCount),
      total_houses: num(s.totalHouses), days_worked: num(s.daysWorked),
      situation_counts: s.situationCounts || {}, property_type_counts: s.propertyTypeCounts || {},
      updated_at: isoOrNull(s.lastUpdated) || undefined,
    })
  );
  if (!rows.length) continue;
  await upsertUniform("monthly_summaries", rows, "agent_id,month_year");
  sumOk += rows.length;
}
console.log(`monthly_summaries: ok (${sumOk})`);

// ---- 7. backups (metadados; storage_path reescrito p/ uuid canônico) ------------
// Regra de retenção: 50 últimos por agente (metadados + arquivos).
let bakOk = 0;
const storageJobs = [];
const backupsByAgent = new Map();
for (const b of top.backups || []) {
  if (!backupsByAgent.has(b.agentId)) backupsByAgent.set(b.agentId, []);
  backupsByAgent.get(b.agentId).push(b);
}
const keptBackups = [];
for (const [aid, list] of backupsByAgent) {
  list.sort((x, y) => num(y.timestamp ?? y.id) - num(x.timestamp ?? x.id));
  keptBackups.push(...list.slice(0, 50));
  if (list.length > 50) note(`backups agente ${aid.slice(0, 6)}: ${list.length} -> 50 (retenção)`);
}
for (const b of keptBackups) {
  const agentId = agentIdByFirebaseUid[b.agentId];
  if (!agentId) {
    note(`backup ${b.id} agente ${b.agentId?.slice(0, 6)} sem perfil; ignorado`);
    continue;
  }
  bakOk++;
  storageJobs.push({
    agentId, ts: b.id,
    firebasePath: b.storagePath || `backups/${b.agentId}/${b.id}.json`,
    destPath: `${agentId}/${b.id}.json`,
    row: clean({
      agent_id: agentId, ts: num(b.timestamp ?? b.id, 0),
      storage_path: `backups/${agentId}/${b.id}.json`,
      house_count: num(b.houseCount), activity_count: num(b.activityCount),
      agent_name: txt(b.agentName),
    }),
  });
}
await upsertUniform("backups", storageJobs.map((j) => j.row), "agent_id,ts");
console.log(`backups meta: ok (${bakOk})`);

// ---- 8. metadata / access_requests / day_transfers ------------------------------
const metaRows = [];
for (const m of top.metadata || []) {
  const { id, ...rest } = m;
  metaRows.push({ key: id, value: rest });
}
if (metaRows.length) await upsertUniform("metadata", metaRows, "key");
console.log(`metadata: ok (${metaRows.length}) [locations ausente no Firestore — não fabricado]`);

const reqRows = (top.access_requests || []).map((r) =>
  clean({
    id: r.id,
    requester_id: profileIdByFirebaseUid[r.uid] ?? null,
    email: r.email ?? null, display_name: r.displayName ?? null,
    requested_name: r.requestedName ?? null, status: r.status || "PENDING",
    created_at: epochToIso(r.timestamp) || undefined,
  })
);
if (reqRows.length) await upsertUniform("access_requests", reqRows, "id");
console.log(`access_requests: ok (${reqRows.length})`);

const trRows = (top.day_transfers || []).map((t) =>
  clean({
    id: t.id,
    from_agent_id: agentIdByFirebaseUid[t.fromUid] ?? profileIdByFirebaseUid[t.fromUid] ?? null,
    to_agent_id: t.toUid && agentIdByFirebaseUid[t.toUid] ? agentIdByFirebaseUid[t.toUid] : null,
    from_name: txt(t.fromName), to_name: txt(t.toAgentName), to_key: t.toKey,
    from_date_text: t.fromDate, final_date_text: t.finalDate || null,
    status: t.status, house_count: num(t.houseCount),
    offered_at: epochToIso(t.offeredAt) || undefined,
    accepted_at: epochToIso(t.acceptedAt),
  })
);
if (trRows.length) await upsertUniform("day_transfers", trRows, "id");
console.log(`day_transfers: ok (${trRows.length})`);

// ---- 9. Storage: Firebase -> Supabase -------------------------------------------
const require = createRequire(import.meta.url);
const toolsPath = (() => {
  const cands = [
    `${process.env.NPM_CONFIG_PREFIX || "/home/guilherme/.nvm/versions/node/v24.21.0"}/lib/node_modules/firebase-tools/lib/auth.js`,
    "/usr/lib/node_modules/firebase-tools/lib/auth.js",
    "/usr/local/lib/node_modules/firebase-tools/lib/auth.js",
  ];
  try {
    const { execSync } = require("child_process");
    cands.unshift(join(execSync("npm root -g", { encoding: "utf8" }).trim(), "firebase-tools/lib/auth.js"));
  } catch (_) {}
  return cands.find((c) => {
    try { require("node:fs").existsSync(c); return true; } catch (_) { return false; }
  });
})();
let fbToken = null;
if (toolsPath) {
  const { getGlobalDefaultAccount, getAccessToken } = require(toolsPath);
  const acc = getGlobalDefaultAccount();
  if (acc?.tokens?.refresh_token) {
    ({ access_token: fbToken } = await getAccessToken(acc.tokens.refresh_token, ["https://www.googleapis.com/auth/cloud-platform"]));
  }
}
if (!fbToken) {
  note("sem sessão Firebase CLI: arquivos de Storage NÃO copiados (rode firebase login e reexecute)");
} else {
  const BUCKET = "healthagentforms.firebasestorage.app";
  const dl = async (fbPath) => {
    const url = `https://firebasestorage.googleapis.com/v0/b/${BUCKET}/o/${encodeURIComponent(fbPath)}?alt=media`;
    const r = await fetch(url, { headers: { Authorization: "Bearer " + fbToken } });
    if (!r.ok) throw new Error(`download ${r.status}`);
    return Buffer.from(await r.arrayBuffer());
  };
  let okN = 0, failN = 0;
  const CONC = 8;
  for (const c of chunk(storageJobs, CONC)) {
    await Promise.all(c.map(async (j) => {
      try {
        const buf = await dl(j.firebasePath);
        const { error } = await sb.storage.from("backups").upload(j.destPath, buf, {
          contentType: "application/json", upsert: true,
        });
        if (error) throw new Error(error.message);
        okN++;
      } catch (e) {
        failN++;
        note(`storage ${j.firebasePath}: ${e.message}`);
      }
    }));
    process.stdout.write(`\r  storage: ${okN} ok / ${failN} falhas`);
  }
  console.log(`\nstorage: ${okN} ok / ${failN} falhas`);
}

console.log(`\nSEED CONCLUÍDO — anomalias: ${anomalies.length}`);
