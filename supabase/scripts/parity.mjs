#!/usr/bin/env node
/**
 * Harness de paridade Firestore x Supabase (gate da Fase 0).
 * Uso:
 *   SUPABASE_URL=... SUPABASE_SERVICE_ROLE=... SUPABASE_PUBLISHABLE_KEY=... \
 *   SUPABASE_DB_URL='postgres://...' npm run parity
 * Compara contagens + conteúdo campo-a-campo + RLS/guards. Exit 1 se divergir.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createClient } from "@supabase/supabase-js";
import pg from "pg";

const REPO = "/home/guilherme/Documentos/HealthAgentForms";
const snap = JSON.parse(readFileSync(join(REPO, "scripts/firestore_export/snapshot.json"), "utf8"));
const top = JSON.parse(readFileSync(join(REPO, "scripts/firestore_export/toplevel.json"), "utf8"));

const sb = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE, { auth: { persistSession: false } });
const anon = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_PUBLISHABLE_KEY, { auth: { persistSession: false } });

let failures = 0;
const check = (name, cond, detail = "") => {
  console.log(`${cond ? "PASS" : "FAIL"}  ${name}${detail ? " — " + detail : ""}`);
  if (!cond) failures++;
};
const tableCount = async (t) => {
  const { count, error } = await sb.from(t).select("*", { count: "exact", head: true });
  if (error) throw new Error(t + ": " + error.message);
  return count;
};

// ---- 1. contagens ------------------------------------------------------------
const srcHouses = snap.reduce((n, a) => n + (a.houses || []).length, 0);
const srcAct = snap.reduce((n, a) => n + (a.activities || []).length, 0);
const srcSum = snap.reduce((n, a) => n + (a.summaries || []).length, 0);
const srcEmails = new Set();
for (const u of top.users) if (u.email) srcEmails.add(String(u.email).toLowerCase());
for (const a of snap) if (a.agent?.email) srcEmails.add(String(a.agent.email).toLowerCase());
for (const r of top.access_requests) if (r.email) srcEmails.add(String(r.email).toLowerCase());
check(`profiles = ${srcEmails.size} e-mails distintos`, (await tableCount("profiles")) === srcEmails.size);
const srcAgentsWithEmail = snap.filter((a) => a.agent?.email).length; // sem e-mail = sem perfil (seed ignora)
check(`agents = ${srcAgentsWithEmail} (com e-mail)`, (await tableCount("agents")) === srcAgentsWithEmail);
check(`houses = ${srcHouses}`, (await tableCount("houses")) === srcHouses, `src=${srcHouses}`);
check(`day_activities = ${srcAct}`, (await tableCount("day_activities")) === srcAct);
check(`monthly_summaries = ${srcSum}`, (await tableCount("monthly_summaries")) === srcSum);
check("backups meta = retenção 50/agente (poda aplicada)", await (async () => {
  // reconstrói o conjunto esperado com a mesma regra do seed
  const byAgent = new Map();
  for (const b of top.backups || []) {
    if (!byAgent.has(b.agentId)) byAgent.set(b.agentId, []);
    byAgent.get(b.agentId).push(b);
  }
  const { data: agents } = await sb.from("agents").select("id,firebase_uid");
  const fbToUuid = Object.fromEntries(agents.map((a) => [a.firebase_uid, a.id]));
  const expected = new Map(); // uuid -> Set(ts)
  for (const [aid, list] of byAgent) {
    const uuid = fbToUuid[aid];
    if (!uuid) continue; // sem perfil: seed ignora
    list.sort((x, y) => Number(y.timestamp ?? y.id) - Number(x.timestamp ?? x.id));
    expected.set(uuid, new Set(list.slice(0, 50).map((b) => String(b.timestamp ?? b.id))));
  }
  const { data: rows } = await sb.from("backups").select("agent_id,ts");
  const actual = new Map();
  for (const r of rows || []) {
    if (!actual.has(r.agent_id)) actual.set(r.agent_id, new Set());
    actual.get(r.agent_id).add(String(r.ts));
  }
  let ok = actual.size === expected.size;
  for (const [uuid, set] of expected) {
    const got = actual.get(uuid) || new Set();
    if (got.size !== set.size || [...set].some((ts) => !got.has(ts))) { ok = false; break; }
  }
  return ok;
})(), "conjuntos (agente,ts) idênticos à fonte");
check("metadata = 2", (await tableCount("metadata")) === 2);
check("access_requests = 2", (await tableCount("access_requests")) === 2);
check("day_transfers = 1", (await tableCount("day_transfers")) === 1);
{
  const { data: agents } = await sb.from("agents").select("id");
  let filesTotal = 0, rowsTotal = 0, orphans = [], strays = [];
  for (const a of agents || []) {
    const { data: listed } = await sb.storage.from("backups").list(a.id, { limit: 1000 });
    const files = new Set((listed || []).filter((e) => e.id !== null).map((e) => e.name.replace(/\.json$/, "")));
    const { data: rows } = await sb.from("backups").select("ts").eq("agent_id", a.id);
    const tsSet = new Set((rows || []).map((r) => String(r.ts)));
    filesTotal += files.size; rowsTotal += tsSet.size;
    for (const ts of tsSet) if (!files.has(ts)) orphans.push(`${a.id.slice(0, 6)}/${ts}`);
    for (const f of files) if (!tsSet.has(f)) strays.push(`${a.id.slice(0, 6)}/${f}`);
  }
  if (orphans.length) console.log(`  (aviso) metadados sem arquivo (órfãos na origem): ${orphans.length}`);
  check("storage consistente (sem arquivos sem metadado)", strays.length === 0, `files=${filesTotal} rows=${rowsTotal} órfãos-aviso=${orphans.length}`);
}

// ---- 2. conteúdo: tabelas pequenas integral ------------------------------------
const norm = (v) => (v === undefined ? null : v);
// JSONB normaliza ordem das chaves: comparar canonicamente
const canon = (o) => JSON.stringify(
  Object.keys(o || {}).sort().reduce((acc, k) => ((acc[k] = o[k]), acc), {})
);
const emailByUid = {};
for (const r of top.access_requests) if (r.email && r.uid) emailByUid[r.uid] = String(r.email).toLowerCase();
const cmpObj = (label, a, b, fields) => {
  for (const f of fields) {
    const x = norm(a[f]);
    const y = norm(b[f]);
    if (String(x) !== String(y)) {
      check(`${label}.${f}`, false, `firestore=${JSON.stringify(x)} supabase=${JSON.stringify(y)}`);
      return;
    }
  }
  check(label, true);
};
{
  const { data: profiles } = await sb.from("profiles").select("*");
  const byFb = Object.fromEntries(profiles.map((p) => [p.firebase_uid, p]));
  for (const u of top.users) {
    const p = byFb[u.id];
    if (!p) { check(`profiles users/${u.id.slice(0, 6)} existe`, false); continue; }
    cmpObj(`profiles ${u.id.slice(0, 6)}`, {
      email: ((u.email || emailByUid[u.id] || "")).toLowerCase(), role: u.role,
      is_authorized: u.isAuthorized === true,
      agent_name: u.agentName ? String(u.agentName).toUpperCase() : null,
      display_name: u.displayName ?? null,
    }, p, ["email", "role", "is_authorized", "agent_name", "display_name"]);
  }
}
{
  const { data: sums } = await sb.from("monthly_summaries").select("*,agents!inner(firebase_uid)");
  const byKey = Object.fromEntries(sums.map((s) => [`${s.agents.firebase_uid}/${s.month_year}`, s]));
  for (const a of snap) for (const s of a.summaries || []) {
    const r = byKey[`${a.id}/${s.id}`];
    if (!r) { check(`summary ${a.id.slice(0, 6)}/${s.id}`, false, "ausente"); continue; }
    cmpObj(`summary ${a.id.slice(0, 6)}/${s.id}`, {
      treated: s.treatedCount, focus: s.focusCount, total: s.totalHouses, days: s.daysWorked,
      sit: canon(s.situationCounts), prop: canon(s.propertyTypeCounts),
    }, {
      treated: r.treated_count, focus: r.focus_count, total: r.total_houses, days: r.days_worked,
      sit: canon(r.situation_counts), prop: canon(r.property_type_counts),
    }, ["treated", "focus", "total", "days", "sit", "prop"]);
  }
}

// ---- 3. conteúdo: houses amostra 300 -------------------------------------------
{
  const all = snap.flatMap((a) => (a.houses || []).map((h) => ({ aid: a.id, h })));
  const sample = all.filter((_, i) => i % Math.max(1, Math.floor(all.length / 300)) === 0).slice(0, 300);
  const { data: agents } = await sb.from("agents").select("id,firebase_uid");
  const aidMap = Object.fromEntries(agents.map((a) => [a.firebase_uid, a.id]));
  let okN = 0;
  for (const { aid, h } of sample) {
    const { data: r, error } = await sb.from("houses")
      .select("*").eq("agent_id", aidMap[aid]).eq("natural_key", h.id).maybeSingle();
    if (error || !r) { check(`house ${h.id.slice(0, 8)}`, false, error?.message || "ausente"); continue; }
    const pairs = [
      [h.data, r.data_text], [h.streetName ?? null, r.street_name], [String(h.number ?? ""), r.number],
      [h.situation ?? null, r.situation], [h.bairro ?? null, r.bairro],
      [Number(h.a1 || 0), r.a1], [Number(h.e || 0), r.e],
      [Number(h.larvicida || 0), Number(r.larvicida)],
      [h.comFoco ?? null, r.com_foco], [h.visitSegment ?? null, r.visit_segment],
      [String(h.tipo ?? ""), r.tipo], [String(h.atividade ?? ""), r.atividade],
      [h.observation ?? null, r.observation],
    ];
    const bad = pairs.find(([x, y]) => String(x) !== String(y));
    if (bad) check(`house ${h.id.slice(0, 8)}`, false, `got=${JSON.stringify(bad[1])} want=${JSON.stringify(bad[0])}`);
    else okN++;
  }
  check(`houses amostra (${okN}/${sample.length} íntegras)`, okN === sample.length);
}

// ---- 4. RG: matriz agente x ano --------------------------------------------------
{
  const matrix = {};
  for (const a of snap) for (const h of a.houses || []) {
    const y = String(h.data || "").slice(6, 10);
    const k = `${a.id.slice(0, 6)}/${y}`;
    matrix[k] = (matrix[k] || 0) + 1;
  }
  const db = new pg.Client({ connectionString: process.env.SUPABASE_DB_URL.replace(/sslmode=[^&]*/, "sslmode=no-verify") });
  await db.connect();
  const { rows } = await db.query(
    `SELECT left(a.firebase_uid,6) AS ag, extract(year FROM h.data_date)::int AS y, count(*) AS n
     FROM houses h JOIN agents a ON a.id = h.agent_id
     WHERE h.deleted_at IS NULL GROUP BY 1,2`
  );
  await db.end();
  let bad = 0;
  for (const r of rows) {
    const want = matrix[`${r.ag}/${r.y}`] || 0;
    if (Number(r.n) !== want) { bad++; console.log(`  RG diverge: ${r.ag}/${r.y} db=${r.n} fs=${want}`); }
  }
  const dbKeys = new Set(rows.map((r) => `${r.ag}/${r.y}`));
  for (const k of Object.keys(matrix)) if (!dbKeys.has(k) && matrix[k] > 0) { bad++; console.log(`  RG ausente no db: ${k} fs=${matrix[k]}`); }
  check("RG agente×ano idêntico", bad === 0);
}

// ---- 5. RLS: anon negado ----------------------------------------------------------
{
  const { data, error } = await anon.from("houses").select("id", { count: "exact", head: false }).limit(1);
  check("anon não lê houses", (data || []).length === 0, error ? `err=${error.code}` : "0 rows");
  const { error: insErr } = await anon.from("houses").insert({ agent_id: "00000000-0000-0000-0000-000000000000", natural_key: "x", data_text: "01-01-2026" });
  check("anon não insere houses", !!insErr, insErr?.code || insErr?.message);
}

// ---- 6. RLS + guards como usuário (via SQL: role authenticated + JWT simulado) -----
{
  const db = new pg.Client({ connectionString: process.env.SUPABASE_DB_URL.replace(/sslmode=[^&]*/, "sslmode=no-verify") });
  await db.connect();
  const { rows: profs } = await db.query(`SELECT id, email, agent_name FROM profiles WHERE email='chavesagrotec@gmail.com'`);
  const me = profs[0].id;
  const { rows: other } = await db.query(`SELECT id FROM profiles WHERE email='guigomelo9@gmail.com'`);
  const otherId = other[0].id;
  // Claims em nível de SESSÃO (set_config local morre no fim da transação implícita!)
  await db.query(`SELECT set_config('request.jwt.claim.sub', $1, false)`, [me]);
  await db.query(`SELECT set_config('request.jwt.claim.email', 'chavesagrotec@gmail.com', false)`);
  await db.query(`SELECT set_config('request.jwt.claims', $1, false)`, [
    JSON.stringify({ sub: me, email: "chavesagrotec@gmail.com", role: "authenticated" }),
  ]);
  const asUser = async (q, params = []) => {
    await db.query("SET ROLE authenticated");
    try { return await db.query(q, params); }
    finally { await db.query("RESET ROLE"); }
  };
  const housesTotal = (await asUser(`SELECT count(*)::int AS n FROM houses`)).rows[0].n;
  check(`autenticado lê RG unificado (${srcHouses})`, housesTotal === srcHouses, `n=${housesTotal}`);
  const profN = (await asUser(`SELECT count(*)::int AS n FROM profiles`)).rows[0].n;
  check("autenticado vê só próprio profile", profN === 1, `n=${profN}`);
  try {
    await asUser(`INSERT INTO houses (agent_id, natural_key, data_text) VALUES ($1,'parity-probe','01-01-2026')`, [otherId]);
    check("insert em agente alheio bloqueado", false);
  } catch (e) { check("insert em agente alheio bloqueado", /policy|permission/i.test(e.message), e.code); }
  await db.query("SET ROLE postgres").catch(() => {});
  const probe = await db.query(`SELECT id FROM houses WHERE natural_key='parity-probe'`);
  check("probe não persistiu", probe.rows.length === 0);
  try {
    await asUser(`UPDATE profiles SET role='ADMIN' WHERE id=$1`, [me]);
    const { rows: chk } = await db.query(`SELECT role FROM profiles WHERE id=$1`, [me]);
    check("self-escalation bloqueada", chk[0].role !== "ADMIN", `role=${chk[0].role}`);
  } catch (e) { check("self-escalation bloqueada", /role\/is_authorized|policy|permission/i.test(e.message), e.code); }
  // transfers: ciclo de vida real como origem (insert PENDING -> mutação negada -> cancel -> delete)
  try {
    await asUser(`INSERT INTO day_transfers (id, from_agent_id, from_name, to_key, to_name, from_date_text, status, house_count)
                  VALUES ('parity-x',$1,'VINÍCIUS CHAVES','GUILHERME MELLO','GUILHERME MELLO','01-01-2026','PENDING',3)`, [me]);
    check("origem cria PENDING p/ outro agente", true);
  } catch (e) { check("origem cria PENDING p/ outro agente", false, e.message); }
  try {
    await asUser(`UPDATE day_transfers SET house_count=99 WHERE id='parity-x'`);
    check("origem não muta oferta (só cancela)", false);
  } catch (e) { check("origem não muta oferta (só cancela)", /origem só pode cancelar/.test(e.message), e.code); }
  try {
    await asUser(`UPDATE day_transfers SET status='CANCELLED' WHERE id='parity-x'`);
    check("origem cancela própria oferta", true);
  } catch (e) { check("origem cancela própria oferta", false, e.message); }
  try {
    await asUser(`INSERT INTO day_transfers (id, from_agent_id, to_key, from_date_text, status) VALUES ('parity-y',$1,'VINÍCIUS CHAVES','01-01-2026','PENDING')`, [me]);
    check("transfer p/ si mesmo bloqueada", false);
    await db.query(`DELETE FROM day_transfers WHERE id='parity-y'`);
  } catch (e) { check("transfer p/ si mesmo bloqueada", /próprio/.test(e.message), e.code); }
  await asUser(`DELETE FROM day_transfers WHERE id='parity-x'`);
  const { rows: gone } = await db.query(`SELECT count(*)::int AS n FROM day_transfers WHERE id LIKE 'parity-%'`);
  check("scratch de transfers limpo", gone[0].n === 0);
  // DECLINED imutável: trigger dispara p/ qualquer ator (sem JWT = papel postgres sem bypass)
  try {
    await db.query(`UPDATE day_transfers SET status='ACCEPTED' WHERE id NOT LIKE 'parity-%' AND status='DECLINED'`);
    check("transfer DECLINED imutável", false);
  } catch (e) { check("transfer DECLINED imutável", /só ofertas PENDING/.test(e.message), e.code); }
  await db.end();
}

console.log(failures === 0 ? "\nPARIDADE VERDE" : `\nPARIDADE VERMELHA — ${failures} falhas`);
process.exit(failures === 0 ? 0 : 1);
