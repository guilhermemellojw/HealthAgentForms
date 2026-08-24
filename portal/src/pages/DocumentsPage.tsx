import { useEffect, useMemo, useState } from "react";
import { useAgents, useAgentSnapshot, useRgHouses } from "../hooks/usePortalData";
import { useBairros } from "../hooks/useAdminData";
import { MONTHS } from "../lib/constants";
import { getWeeksForMonth, sortRgHouses } from "../lib/period";
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
  const [docTab, setDocTab] = useState<"producao" | "rg">("producao");

  // ---- Produção (agente + semana) ----
  const [agentId, setAgentId] = useState("");
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [weekIndex, setWeekIndex] = useState(0);
  const [dailyDate, setDailyDate] = useState("");
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

  const { data: agents } = useAgents();
  const agentName = agents?.find((a) => a.id === agentId)?.agentName || "";
  const activeAgent = agents?.find((a) => a.id === agentId);

  const dailyDates = Object.keys(weeklyData).sort();
  const dailyDatesKey = dailyDates.join("|");

  useEffect(() => {
    setDailyDate((prev) => (dailyDates.includes(prev) ? prev : dailyDates[0] || ""));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dailyDatesKey]);

  const canSemanal = weekHouses.length > 0 || Object.keys(weekActivities).length > 0;

  // ---- RG (ano + bairro, multi-agente — paridade Android) ----
  const [rgYear, setRgYear] = useState(now.getFullYear());
  const [rgBairro, setRgBairro] = useState("");
  const [rgBlock, setRgBlock] = useState("");
  const { bairros: masterBairros } = useBairros();
  const rgQuery = useRgHouses(rgBairro);

  const rgYearHouses = useMemo(
    () => (rgQuery.data ?? []).filter((h) => (h.data || "").trim().endsWith(`-${rgYear}`)),
    [rgQuery.data, rgYear],
  );

  const rgBlocks = useMemo(() => {
    const out = new Map<string, string>();
    for (const h of rgYearHouses) {
      const bNum = (h.blockNumber || "").trim();
      const bSeq = (h.blockSequence || "").trim();
      const display = bSeq === "" ? bNum : `${bNum}/${bSeq}`;
      out.set(blockKey(h), display);
    }
    return [...out.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [rgYearHouses]);

  const rgHouses = useMemo(() => {
    if (!rgBlock) return [];
    const target = blockKey({ blockNumber: rgBlock.split("/")[0], blockSequence: rgBlock.split("/")[1] ?? "" } as HouseDoc);
    return sortRgHouses(rgYearHouses.filter((h) => blockKey(h) === target));
  }, [rgYearHouses, rgBlock]);

  const rgAgents = useMemo(
    () => [...new Set(rgHouses.map((h) => (h.agentName || "").trim()).filter(Boolean))].sort(),
    [rgHouses],
  );

  useEffect(() => {
    setRgBlock("");
  }, [rgBairro, rgYear]);

  // ---- Compartilhado ----
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

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

  const years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - 2 + i);

  return (
    <div className="docs-page">
      <div style={{ display: "flex", gap: 8, marginBottom: 14 }}>
        <button className={`btn ${docTab === "producao" ? "btn-primary" : "btn-outline"}`} onClick={() => setDocTab("producao")}>
          Produção
        </button>
        <button className={`btn ${docTab === "rg" ? "btn-primary" : "btn-outline"}`} onClick={() => setDocTab("rg")}>
          RG
        </button>
      </div>

      {error && <p className="error-text">{error}</p>}

      {docTab === "producao" ? (
        <>
          <div className="period-bar">
            <select className="select" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">Selecione um agente…</option>
              {agents?.map((a) => (
                <option key={a.id} value={a.id}>{a.agentName || a.email}</option>
              ))}
            </select>
            <select className="select" value={year} onChange={(e) => setYear(Number(e.target.value))}>
              {years.map((y) => (
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

          {!agentId && <p className="muted">Selecione um agente e o período para gerar os documentos de produção.</p>}

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
            </div>
          )}

          {agentId && <p className="muted small">Agente: {activeAgent?.agentName || agentName}</p>}
        </>
      ) : (
        <>
          <div className="period-bar">
            <select className="select" value={rgYear} onChange={(e) => setRgYear(Number(e.target.value))}>
              {years.map((y) => (
                <option key={y} value={y}>{y}</option>
              ))}
            </select>
            <select
              className="select"
              value={rgBairro}
              onChange={(e) => {
                setRgBairro(e.target.value.toUpperCase());
                setRgBlock("");
              }}
            >
              <option value="">Selecione o bairro…</option>
              {masterBairros.map((b) => (
                <option key={b} value={b}>{b}</option>
              ))}
            </select>
            <select
              className="select"
              value={rgBlock}
              onChange={(e) => setRgBlock(e.target.value)}
              disabled={!rgBairro}
            >
              <option value="">Selecione o quarteirão…</option>
              {rgBlocks.map(([k, display]) => (
                <option key={k} value={k}>{display}</option>
              ))}
            </select>
          </div>

          {!rgBairro && <p className="muted">Selecione o ano e o bairro para montar o RG do quarteirão (inclui todos os agentes).</p>}
          {rgBairro && rgQuery.isLoading && <p className="muted">Carregando imóveis do bairro…</p>}
          {rgBairro && !rgQuery.isLoading && rgBlocks.length === 0 && (
            <p className="muted">Nenhum imóvel encontrado em {rgBairro} no ano {rgYear}.</p>
          )}
          {rgBlock && rgHouses.length > 0 && (
            <p className="muted small">
              Quarteirão {rgBlock} • {rgHouses.length} imóveis • Agentes: {rgAgents.join(" / ") || "—"}
            </p>
          )}

          {rgBlock && (
            <div className="docs-grid">
              <section className="card docs-card">
                <h3>RG do Quarteirão</h3>
                <p className="muted small">
                  Roteiro de campo do quarteirão {rgBairro} • Q{rgBlock} ({rgYear}) — todas as equipes.
                </p>
                <button
                  className="btn btn-primary btn-block"
                  disabled={rgHouses.length === 0 || busy !== null}
                  onClick={() =>
                    withBusy("rg", () => downloadRg(rgHouses, rgBairro, rgBlock, "Bom Jardim", rgAgents))
                  }
                >
                  {busy === "rg" ? "Gerando…" : "Baixar RG"}
                </button>
              </section>
            </div>
          )}
        </>
      )}
    </div>
  );
}
