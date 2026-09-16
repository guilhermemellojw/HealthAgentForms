#!/usr/bin/env node
/**
 * Audit: web stats (parity with website/main.js logic) vs monthly_summaries (Android).
 *
 * Usage:
 *   node scripts/audit_web_stats.js [snapshot.json]
 *
 * Ports the exact aggregation logic from website/main.js (SITUATION, PROPERTY_TYPE,
 * dedup key, isTreated, filterByPeriod) and compares against the per-agent
 * monthly_summaries written by the Android app (SyncPushHandler).
 */
const fs = require("fs");
const path = require("path");

const SNAP = process.argv[2] || path.join(__dirname, "firestore_export", "snapshot.json");
const snapshot = JSON.parse(fs.readFileSync(SNAP, "utf8"));

// ── Mirrors website/main.js ─────────────────────────────────────────────────
const SITUATION = {
  NONE: "NONE", EMPTY: "EMPTY",
  CLOSED: "CLOSED", VACANT: "VACANT", REFUSED: "REFUSED", ABANDONED: "ABANDONED",
  L_F: "F", L_V: "V", L_REC: "REC", L_A: "A", L_NONE: "—",
};
const PROPERTY_TYPE = { RES: "R", COM: "C", TB: "TB", PE: "PE", OUT: "O", EMPTY: "EMPTY" };
const WORKED_EXCLUDED = [
  SITUATION.VACANT, SITUATION.CLOSED, SITUATION.REFUSED, SITUATION.ABANDONED,
  SITUATION.L_V, SITUATION.L_F, SITUATION.L_REC, SITUATION.L_A,
];

function normalizeName(text) {
  if (text === null || text === undefined) return "";
  return text.toString().normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .trim()
    .replace(/\//g, "-")
    .replace(/\./g, "-")
    .replace(/\s+/g, " ")
    .replace(/-+/g, "-")
    .toUpperCase();
}

function isTreated(house) {
  return (house.a1 || 0) + (house.a2 || 0) + (house.b || 0) + (house.c || 0) +
    (house.d1 || 0) + (house.d2 || 0) + (house.e || 0) + (house.eliminados || 0) > 0 ||
    (house.larvicida || 0) > 0 || house.comFoco;
}

function parseDate(dateStr) {
  const parts = dateStr.split("-");
  if (parts.length !== 3) return new Date(0);
  return new Date(parts[2], parts[1] - 1, parts[0], 12, 0, 0);
}

function filterByPeriod(items, dateField, year, month, weekIndex) {
  const today = new Date();
  today.setHours(23, 59, 59, 999);
  const currentItems = items.filter((item) => {
    const dateStr = (item[dateField] || "").replace(/\//g, "-");
    return parseDate(dateStr) <= today;
  });
  if (month === -1) {
    return currentItems.filter((item) => ((item[dateField] || "").replace(/\//g, "-")).endsWith(`-${year}`));
  }
  const monthStr = String(month + 1).padStart(2, "0");
  return currentItems.filter((item) =>
    ((item[dateField] || "").replace(/\//g, "-")).endsWith(`-${monthStr}-${year}`)
  );
}

function dedupHouses(houses) {
  const groups = {};
  let duplicates = 0;
  for (const h of houses) {
    const date = normalizeName(h.data || h.date);
    const street = normalizeName(h.streetName);
    const num = normalizeName(h.number);
    const bNum = normalizeName(h.blockNumber);
    const bSeq = normalizeName(h.blockSequence || "0");
    const seq = h.sequence || 0;
    const comp = h.complement || 0;
    const seg = h.visitSegment || 0;
    const key = `DEDUP|${date}|${bNum}|${bSeq}|${street}|${num}|${seq}|${comp}|${seg}`;
    const ts = (h.lastUpdated && h.lastUpdated.seconds) || h.lastUpdated || h.lastSyncTime || 0;
    if (!groups[key]) {
      groups[key] = { ...h, ts };
    } else {
      duplicates++;
      if (ts > groups[key].ts) groups[key] = { ...h, ts };
    }
  }
  return { valid: Object.values(groups), duplicates };
}

function computeWebStats(houses, activities, year, month) {
  const filteredHouses = filterByPeriod(houses, "data", year, month, -1);
  const filteredActivities = filterByPeriod(activities, "date", year, month, -1);
  const countSituation = (s) => filteredHouses.filter((h) => h.situation === s).length;
  return {
    worked: filteredHouses.filter((h) => !WORKED_EXCLUDED.includes(h.situation)).length,
    vacant: countSituation(SITUATION.VACANT) + countSituation(SITUATION.L_V),
    closed: countSituation(SITUATION.CLOSED) + countSituation(SITUATION.L_F),
    abandoned: countSituation(SITUATION.ABANDONED) + countSituation(SITUATION.L_A),
    refused: countSituation(SITUATION.REFUSED) + countSituation(SITUATION.L_REC),
    focuses: filteredHouses.filter((h) => h.comFoco).length,
    treated: filteredHouses.filter((h) => isTreated(h)).length,
    visits: filteredHouses.length,
    res: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.RES || h.propertyType === PROPERTY_TYPE.EMPTY).length,
    com: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.COM).length,
    tb: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.TB).length,
    pe: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.PE).length,
    out: filteredHouses.filter((h) => h.propertyType === PROPERTY_TYPE.OUT).length,
    activeDays: filteredActivities.length,
  };
}

// ── Audit ───────────────────────────────────────────────────────────────────
const today = new Date();
const YEAR = today.getFullYear();
let totalChecks = 0, passed = 0;
const failures = [];

function check(label, webVal, androidVal, tolerance = 0) {
  totalChecks++;
  const ok = Math.abs(webVal - androidVal) <= tolerance;
  if (ok) passed++;
  else failures.push({ label, webVal, androidVal, diff: webVal - androidVal });
}

let anyAgent = false;
for (const agent of snapshot) {
  const housesRaw = agent.houses || [];
  const activities = agent.activities || [];
  const summaries = agent.summaries || [];
  if (housesRaw.length === 0 && summaries.length === 0) continue;
  anyAgent = true;

  const { valid: housesDedup, duplicates } = dedupHouses(housesRaw);
  console.log(`\n=== ${agent.agent.agentName || agent.agent.email || agent.id} (${agent.id.slice(0, 8)}...) ===`);
  console.log(`  houses raw=${housesRaw.length} dedup=${housesDedup.length} (duplicatas em memória: ${duplicates})`);

  const monthsWithSummary = summaries.map((s) => s.monthYear);
  const monthsInData = [...new Set(housesDedup.map((h) => (h.data || "").replace(/\//g, "-").slice(3)))];

  for (const monthYear of [...new Set([...monthsWithSummary, ...monthsInData])].sort()) {
    const parts = monthYear.split("-");
    const month = parseInt(parts[0], 10) - 1;
    const year = parseInt(parts[1], 10);
    if (isNaN(month) || isNaN(year)) continue;

    const webRaw = computeWebStats(housesRaw, activities, year, month);
    const web = computeWebStats(housesDedup, activities, year, month);
    const sum = summaries.find((s) => s.monthYear === monthYear);

    if (!sum) {
      console.log(`  [${monthYear}] sem monthly_summary (Android não computou) — web: visits=${web.visits} worked=${web.worked}`);
      continue;
    }

    const android = {
      totalHouses: sum.totalHouses || 0,
      treatedCount: sum.treatedCount || 0,
      focusCount: sum.focusCount || 0,
      daysWorked: sum.daysWorked || 0,
      situationCounts: sum.situationCounts || {},
      propertyTypeCounts: sum.propertyTypeCounts || {},
    };
    const sumSituations = (codes) => codes.reduce((acc, c) => acc + (android.situationCounts[c] || 0), 0);
    const sumTypes = (codes) => codes.reduce((acc, c) => acc + (android.propertyTypeCounts[c] || 0), 0);

    console.log(`\n  [${monthYear}]`);
    console.log(`    totalHouses     web(dedup)=${web.visits} web(raw)=${webRaw.visits} android=${android.totalHouses}`);
    console.log(`    treated         web=${web.treated} android=${android.treatedCount}`);
    console.log(`    focuses         web=${web.focuses} android=${android.focusCount}`);
    console.log(`    activeDays      web=${web.activeDays} android=${android.daysWorked}`);
    console.log(`    situacao fechados  web=${web.closed} android=${sumSituations(["F", "CLOSED"])}`);
    console.log(`    situacao vazios    web=${web.vacant} android=${sumSituations(["V", "VACANT"])}`);
    console.log(`    situacao recusados web=${web.refused} android=${sumSituations(["REC", "REFUSED"])}`);
    console.log(`    situacao aband.    web=${web.abandoned} android=${sumSituations(["A", "ABANDONED"])}`);
    console.log(`    situacao aberto    web=${web.worked} android=${sumSituations(["NONE", "EMPTY"])}`);
    console.log(`    tipo R(res)        web=${web.res} android=${sumTypes(["R"])}`);
    console.log(`    tipo C(com)        web=${web.com} android=${sumTypes(["C"])}`);
    console.log(`    tipo TB            web=${web.tb} android=${sumTypes(["TB"])}`);
    console.log(`    tipo O(out)        web=${web.out} android=${sumTypes(["O"])}`);
    console.log(`    tipo PE            web=${web.pe} android=${sumTypes(["PE"])}`);

    // Checks (dedup vs android)
    check(`${agent.agent.agentName}|${monthYear}|visits`, web.visits, android.totalHouses);
    check(`${agent.agent.agentName}|${monthYear}|treated`, web.treated, android.treatedCount);
    check(`${agent.agent.agentName}|${monthYear}|focuses`, web.focuses, android.focusCount);
    check(`${agent.agent.agentName}|${monthYear}|activeDays`, web.activeDays, android.daysWorked);
    check(`${agent.agent.agentName}|${monthYear}|closed`, web.closed, sumSituations(["F", "CLOSED"]));
    check(`${agent.agent.agentName}|${monthYear}|vacant`, web.vacant, sumSituations(["V", "VACANT"]));
    check(`${agent.agent.agentName}|${monthYear}|refused`, web.refused, sumSituations(["REC", "REFUSED"]));
    check(`${agent.agent.agentName}|${monthYear}|abandoned`, web.abandoned, sumSituations(["A", "ABANDONED"]));
    check(`${agent.agent.agentName}|${monthYear}|res`, web.res, sumTypes(["R"]));
    check(`${agent.agent.agentName}|${monthYear}|com`, web.com, sumTypes(["C"]));
    check(`${agent.agent.agentName}|${monthYear}|tb`, web.tb, sumTypes(["TB"]));
    check(`${agent.agent.agentName}|${monthYear}|out`, web.out, sumTypes(["O"]));
    check(`${agent.agent.agentName}|${monthYear}|pe`, web.pe, sumTypes(["PE"]));
  }

  // Year-level: web(Ano Todo) vs soma dos summaries do ano
  const webYear = computeWebStats(housesDedup, activities, YEAR, -1);
  const sumsYear = summaries.filter((s) => s.monthYear.endsWith(`-${YEAR}`));
  if (sumsYear.length > 0) {
    const androidYear = {
      total: sumsYear.reduce((a, s) => a + (s.totalHouses || 0), 0),
      treated: sumsYear.reduce((a, s) => a + (s.treatedCount || 0), 0),
      focus: sumsYear.reduce((a, s) => a + (s.focusCount || 0), 0),
      days: sumsYear.reduce((a, s) => a + (s.daysWorked || 0), 0),
    };
    console.log(`\n  [ANO ${YEAR}] (${sumsYear.length} meses com summary)`);
    console.log(`    totalHouses web=${webYear.visits} android=${androidYear.total}`);
    console.log(`    treated     web=${webYear.treated} android=${androidYear.treated}`);
    console.log(`    focuses     web=${webYear.focuses} android=${androidYear.focus}`);
    console.log(`    activeDays  web=${webYear.activeDays} android=${androidYear.days}`);
    check(`${agent.agent.agentName}|${YEAR}|visits`, webYear.visits, androidYear.total);
    check(`${agent.agent.agentName}|${YEAR}|treated`, webYear.treated, androidYear.treated);
    check(`${agent.agent.agentName}|${YEAR}|focuses`, webYear.focuses, androidYear.focus);
    check(`${agent.agent.agentName}|${YEAR}|activeDays`, webYear.activeDays, androidYear.days);
  }
}

console.log("\n\n════════════════════════════════════════════");
console.log(`RESULTADO: ${passed}/${totalChecks} checks passaram`);
if (failures.length === 0) {
  console.log("✅ PARIDADE TOTAL (100%) entre web e monthly_summaries (Android)");
} else {
  console.log(`❌ ${failures.length} divergências:`);
  for (const f of failures.slice(0, 30)) {
    console.log(`   - ${f.label}: web=${f.webVal} android=${f.androidVal} (dif=${f.diff})`);
  }
  if (failures.length > 30) console.log(`   ... e mais ${failures.length - 30}`);
}
if (!anyAgent) console.log("Nenhum agente com dados encontrado.");