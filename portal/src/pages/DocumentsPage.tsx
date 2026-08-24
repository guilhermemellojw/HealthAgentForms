import { useEffect, useMemo, useState } from "react";
import { useAgents, useAgentSnapshot } from "../hooks/usePortalData";
import { MONTHS } from "../lib/constants";
import { getWeeksForMonth } from "../lib/period";
import { downloadBoletim } from "../lib/pdf/boletim";
import { downloadSemanal } from "../lib/pdf/semanal";
import { downloadRg } from "../lib/pdf/rg";
import { downloadWeeklyBatch } from "../lib/pdf/weeklyBatch";
import type { HouseDoc } from "../lib/types";

const fmt = (d: Date) => `${String(d.getDate()).padStart(2, "0")}-${String(d.getMonth() + 1).padStart(2, "0")}-${d.getFullYear()}`;

function blockKey(h: HouseDoc): string {
  const n = (h.blockNumber || "").trim();
  const s = (h.blockSequence || "").trim();
  return s === "" ? n : `${n}/${s}`;
}

export default function DocumentsPage() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [weekIndex, setWeekIndex] = useState(0);
  const [agentId, setAgentId] = useState("");
  const [bairro, setBairro] = useState("");
  const [block, setBlock] = useState("");
  const [dailyDate, setDailyDate] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: agents } = useAgents();
  const snapshot = useAgentSnapshot(agentId);

  const weeks = getWeeksForMonth(year, month);
  const week = weeks[weekIndex] ?? null;

  const weekDates = useMemo(
    () => {
      if (!week) return [];
      const dates: string[] = [];
      const d = new Date(week.start);
      for (let i = 0; i < 7; i++) {
        dates.push(fmt(d));
        d.setDate(d.getDate() + 1);
      }
      return dates;
    },
    [week],
  );

  const weekHouses = useMemo(
    () => {
      const all = snapshot.data?.houses ?? [];
      const valid = all.filter((h) => weekDates.includes((h.data || "").trim()));
      return valid.sort((a, b) => (a.listOrder ?? 0) - (b.listOrder ?? 0));
    },
    [snapshot.data, weekDates],
  );

  const weekActivities = useMemo(() => {
    const map: Record<string, string> = {};
    for (const a of snapshot.data?.activities ?? []) {
      const d = (a.date || "").trim();
      if (weekDates.includes(d) && a.status) map[d] = a.status;
    }
    return map;
  }, [snapshot.data, weekDates]);

  const weeklyData = useMemo(() => {
    const map: Record<string, HouseDoc[]> = {};
    for (const h of weekHouses) {
      const d = (h.data || "").trim();
      if (!map[d]) map[d] = [];
      map[d].push(h);
    }
    return map;
  }, [weekHouses]);

  const bairros = useMemo(
    () =>
      [...new Set(weekHouses.map((h) => (h.bairro || "").trim().toUpperCase()).filter((b) => b !== ""))].sort(),
    [weekHouses],
  );

  const blocks = useMemo(() => {
    const out = new Map<string, string>();
    for (const h of weekHouses) {
      if ((h.bairro || "").trim().toUpperCase() !== bairro) continue;
      const bNum = (h.blockNumber || "").trim();
      const bSeq = (h.blockSequence || "").trim();
      const display = bSeq === "" ? bNum : `${bNum}/${bSeq}`;
      out.set(blockKey(h), display);
    }
    return [...out.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [weekHouses, bairro]);

  const rgHouses = useMemo(() => {
    if (!bairro || !block) return [];
    const target = blockKey({ blockNumber: block.split("/")[0], blockSequence: block.split("/")[1] ?? "" } as HouseDoc);
    return weekHouses.filter((h) => (h.bairro || "").trim().toUpperCase() === bairro && blockKey(h) === target);
  }, [weekHouses, bairro, block]);

  const agentName = agents?.find((a) => a.id === agentId)?.agentName || "";
  const activeAgent = agents?.find((a) => a.id === agentId);

  const dailyDates = Object.keys(weeklyData).sort();
  const dailyDatesKey = dailyDates.join("|");
  const weekDatesKey = weekDates.join("|");

  useEffect(() => {
    setDailyDate((prev) => (dailyDates.includes(prev) ? prev : dailyDates[0] || ""));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dailyDatesKey]);

  useEffect(() => {
    setBairro("");
    setBlock("");
  }, [agentId, weekDatesKey]);

  const withBusy = async (key: string, fn: () => Promise<void>) => {
    setBusy(key);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const canSemanal = weekHouses.length > 0 || Object.keys(weekActivities).length > 0;

  return (
    <div className="docs-page">
      <div className="period-bar">
        <select className="select" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
          <option value="">Selecione um agente…</option>
          {agents?.map((a) => (
            <option key={a.id} value={a.id}>{a.agentName || a.email}</option>
          ))}
        </select>
        <select className="select" value={year} onChange={(e) => setYear(Number(e.target.value))}>
          {Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - 2 + i).map((y) => (
            <option key={y} value={y}>{y}</option>
          ))}
        </select>
        <select className="select" value={month} onChange={(e) => setMonth(Number(e.target.value))}>
          {MONTHS.slice(1).map((m, i) => (
            <option key={m} value={i}>{m}</option>
          ))}
        </select>
        {weeks.length > 0 && (
          <select className="select" value={weekIndex} onChange={(e) => setWeekIndex(Number(e.target.value))}>
            {weeks.map((w, i) => (
              <option key={i} value={i}>{w.label}</option>
            ))}
          </select>
        )}
      </div>

      {!agentId && <p className="muted">Selecione um agente e o período para gerar os documentos.</p>}

      {agentId && (
        <div className="docs-grid">
          <section className="card docs-card">
            <h3>Boletim Diário</h3>
            <p className="muted small">Registro diário do serviço antivetorial (frente + verso).</p>
            <select
              className="select"
              value={dailyDate}
              onChange={(e) => setDailyDate(e.target.value)}
            >
              {dailyDates.map((d) => (
                <option key={d} value={d}>{d.replace(/-/g, "/")}</option>
              ))}
            </select>
            <button
              className="btn btn-primary btn-block"
              disabled={!dailyDate || busy !== null}
              onClick={() =>
                withBusy("diario", () => downloadBoletim(weeklyData[dailyDate] || [], dailyDate, agentName))
              }
            >
              {busy === "diario" ? "Gerando…" : "Baixar Boletim"}
            </button>
          </section>

          <section className="card docs-card">
            <h3>Resumo Semanal</h3>
            <p className="muted small">Resumo semanal dos agentes (1 página, paisagem).</p>
            <button
              className="btn btn-primary btn-block"
              disabled={!canSemanal || busy !== null}
              onClick={() =>
                withBusy("semanal", () => downloadSemanal(weekDates, weekHouses, weekActivities, agentName))
              }
            >
              {busy === "semanal" ? "Gerando…" : "Baixar Resumo Semanal"}
            </button>
          </section>

          <section className="card docs-card">
            <h3>Produção da Semana</h3>
            <p className="muted small">Frentes + versos de todos os dias da semana + resumo semanal.</p>
            <button
              className="btn btn-primary btn-block"
              disabled={!canSemanal || busy !== null}
              onClick={() =>
                withBusy("producao", () => downloadWeeklyBatch(weeklyData, agentName, weekActivities, weekDates))
              }
            >
              {busy === "producao" ? "Gerando…" : "Baixar Produção da Semana"}
            </button>
          </section>

          <section className="card docs-card">
            <h3>RG do Quarteirão</h3>
            <p className="muted small">Roteiro de campo do quarteirão (bairro + quarteirão).</p>
            <select className="select" value={bairro} onChange={(e) => { setBairro(e.target.value); setBlock(""); }}>
              <option value="">Selecione o bairro…</option>
              {bairros.map((b) => (
                <option key={b} value={b}>{b}</option>
              ))}
            </select>
            <select className="select" value={block} onChange={(e) => setBlock(e.target.value)} disabled={!bairro}>
              <option value="">Selecione o quarteirão…</option>
              {blocks.map(([k]) => (
                <option key={k} value={k}>{k}</option>
              ))}
            </select>
            <button
              className="btn btn-primary btn-block"
              disabled={!bairro || !block || rgHouses.length === 0 || busy !== null}
              onClick={() =>
                withBusy("rg", () =>
downloadRg(
                  rgHouses,
                  bairro,
                  block,
                  "Bom Jardim",
                ),
                )
              }
            >
              {busy === "rg" ? "Gerando…" : "Baixar RG"}
            </button>
          </section>
        </div>
      )}

      {error && <p className="error-text">{error}</p>}
      {agentId && <p className="muted small">Agente: {activeAgent?.agentName || agentName}</p>}
    </div>
  );
}