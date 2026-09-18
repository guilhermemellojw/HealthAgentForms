import { useEffect, useMemo, useState } from "react";
import { useAgents, useAgentSnapshot, useRgCoverage } from "../hooks/usePortalData";
import { useAgentNames, useBairros } from "../hooks/useAdminData";
import { MONTHS } from "../lib/constants";
import { getWeeksForMonth, houseYear } from "../lib/period";
import { getRGBlocks } from "../lib/rgBlocks";
import { downloadBoletim } from "../lib/pdf/boletim";
import { downloadSemanal } from "../lib/pdf/semanal";
import { downloadRg } from "../lib/pdf/rg";
import { downloadWeeklyBatch } from "../lib/pdf/weeklyBatch";
import type { HouseDoc } from "../lib/types";

const fmt = (d: Date) => `${String(d.getDate()).padStart(2, "0")}-${String(d.getMonth() + 1).padStart(2, "0")}-${d.getFullYear()}`;

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
  const rgCoverage = useRgCoverage(rgYear);
  const { bairros: masterBairros } = useBairros();
  const { names: masterAgentNames } = useAgentNames();

  // Ano validado no cliente (lido do data de cada casa) — o range do servidor
  // em DD-MM-YYYY vaza outros anos.
  const rgYearHouses = useMemo(
    () => (rgCoverage.data ?? []).filter((h) => houseYear(h) === rgYear),
    [rgCoverage.data, rgYear],
  );

  // Bairros realmente trabalhados no ano (com contagem de imóveis)
  const rgBairros = useMemo(() => {
    const counts = new Map<string, number>();
    for (const h of rgYearHouses) {
      const b = (h.bairro || "").trim().toUpperCase();
      if (!b) continue;
      counts.set(b, (counts.get(b) ?? 0) + 1);
    }
    return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [rgYearHouses]);

  // Segmentos de quarteirão: porte do GetRGBlocksUseCase (Android) — filtra por
  // bairro (heal) + ano, agrupa e particiona com conclusão implícita.
  const rgSegments = useMemo(
    () => getRGBlocks(rgCoverage.data ?? [], rgBairro, String(rgYear), masterBairros, masterAgentNames),
    [rgCoverage.data, rgBairro, rgYear, masterBairros, masterAgentNames],
  );

  // Cards de segmento (um por partição, como o app).
  const rgCards = useMemo(
    () =>
      rgSegments.map((s) => ({
        id: s.id,
        display: s.blockSequence === "" ? s.blockNumber : `${s.blockNumber} / ${s.blockSequence}`,
        count: s.houses.length,
        agents: s.participatingAgents,
        isConcluded: s.isConcluded,
        conclusionDate: s.conclusionDate,
        startDate: s.startDate,
        endDate: s.endDate,
      })),
    [rgSegments],
  );

  const rgSelectedCard = useMemo(() => rgCards.find((c) => c.id === rgBlock), [rgCards, rgBlock]);
  const rgHouses = useMemo(
    () => rgSegments.find((s) => s.id === rgBlock)?.houses ?? [],
    [rgSegments, rgBlock],
  );
  const rgAgents = useMemo(
    () => rgSegments.find((s) => s.id === rgBlock)?.participatingAgents ?? [],
    [rgSegments, rgBlock],
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
            <select className="select" value={rgYear} onChange={(e) => { setRgYear(Number(e.target.value)); setRgBairro(""); setRgBlock(""); }}>
              {years.map((y) => (
                <option key={y} value={y}>{y}</option>
              ))}
            </select>
            <select
              className="select"
              value={rgBairro}
              onChange={(e) => {
                setRgBairro(e.target.value);
                setRgBlock("");
              }}
              disabled={rgCoverage.isLoading}
            >
              <option value="">{rgCoverage.isLoading ? "Carregando bairros…" : "Selecione o bairro…"}</option>
              {rgBairros.map(([b]) => (
                <option key={b} value={b}>{b}</option>
              ))}
            </select>
          </div>

          {!rgCoverage.isLoading && rgBairros.length === 0 && (
            <p className="muted">Nenhum imóvel registrado no ano {rgYear}. Selecione outro ano.</p>
          )}
          {!rgBairro && !rgCoverage.isLoading && rgBairros.length > 0 && (
            <p className="muted">Selecione o bairro para montar o RG do quarteirão (inclui todos os agentes).</p>
          )}

          {rgBairro && !rgBlock && (
            <div className="docs-grid">
              {rgCards.length === 0 && (
                <p className="muted">Nenhum quarteirão encontrado em {rgBairro} em {rgYear}.</p>
              )}
              {rgCards.map((c) => (
                <section
                  key={c.id}
                  className="card docs-card rg-block-card"
                  role="button"
                  tabIndex={0}
                  onClick={() => setRgBlock(c.id)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      setRgBlock(c.id);
                    }
                  }}
                >
                  <h3>Quarteirão {c.display}</h3>
                  <p className={`rg-block-status ${c.isConcluded ? "rg-block-closed" : ""}`}>
                    {c.isConcluded ? `Concluído em ${c.conclusionDate}` : "Em Aberto"}
                  </p>
                  <p className="muted small">{c.count} imóveis</p>
                  <p className="muted small">Período: {c.startDate} a {c.endDate}</p>
                  {c.agents.length > 0 && (
                    <p className="muted small">Equipe: {c.agents.join(" / ")}</p>
                  )}
                </section>
              ))}
            </div>
          )}

          {rgBlock && rgHouses.length === 0 && (
            <p className="muted">Nenhum imóvel encontrado neste quarteirão.</p>
          )}

          {rgBlock && rgHouses.length > 0 && (
            <>
              <p className="muted small">
                Quarteirão {rgSelectedCard?.display} • {rgHouses.length} imóveis • Agentes: {rgAgents.join(" / ") || "—"}
              </p>
              <div className="docs-grid">
                <section className="card docs-card">
                  <h3>RG do Quarteirão</h3>
                  <p className="muted small">
                    Roteiro de campo do quarteirão {rgBairro} • Q{rgSelectedCard?.display} ({rgYear}) — todas as equipes.
                  </p>
                  <button
                    className="btn btn-primary btn-block"
                    disabled={busy !== null}
                    onClick={() =>
                      withBusy("rg", () => downloadRg(rgHouses, rgBairro, rgSelectedCard?.display ?? rgBlock, "Bom Jardim", rgAgents))
                    }
                  >
                    {busy === "rg" ? "Gerando…" : "Baixar RG"}
                  </button>
                  <button className="btn btn-outline btn-block" onClick={() => setRgBlock("")}>
                    Trocar quarteirão
                  </button>
                </section>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
