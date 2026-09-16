import { useMemo, useState } from "react";
import { useAgents, useAgentStatsByPeriod, useAgentActivitiesByPeriod } from "../hooks/usePortalData";
import { MONTHS, SITUATION_LABELS } from "../lib/constants";
import { getWeeksForMonth } from "../lib/period";
import { DayDetailModal, DaysModal } from "../components/DayModals";
import type { AgentDoc, HouseStats } from "../lib/types";

const ZERO: HouseStats = {
  worked: 0, vacant: 0, closed: 0, abandoned: 0, refused: 0,
  focuses: 0, treated: 0, visits: 0, res: 0, com: 0, tb: 0, pe: 0, out: 0, activeDays: 0,
};

function sumStats(list: HouseStats[]): HouseStats {
  const out = { ...ZERO };
  for (const s of list) for (const k of Object.keys(out) as (keyof HouseStats)[]) out[k] += s[k];
  return out;
}

function formatDateLabel(y: number, m: number, w: number): string {
  if (w >= 0) {
    const weeks = getWeeksForMonth(y, m);
    return weeks[w]?.label.split(" (")[0] ?? "";
  }
  return m === -1 ? `Ano ${y}` : `${MONTHS[m + 1]} ${y}`;
}

export default function SummaryPage() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [week, setWeek] = useState(-1);

  const { data: agents, isLoading: loadingAgents } = useAgents();
  const { statsByAgent, isLoading: loadingStats } = useAgentStatsByPeriod(agents, year, month, week);
  const activitiesByAgent = useAgentActivitiesByPeriod(agents, year, month, week);

  const [daysAgent, setDaysAgent] = useState<AgentDoc | null>(null);
  const [detailAgent, setDetailAgent] = useState<AgentDoc | null>(null);
  const [detailDay, setDetailDay] = useState<string | null>(null);

  const activeAgents = useMemo(
    () => (agents || []).filter((a) => statsByAgent.has(a.id)),
    [agents, statsByAgent],
  );
  const totals = useMemo(
    () => sumStats([...statsByAgent.values()].map((v) => v.stats)),
    [statsByAgent],
  );

  const weeks = month === -1 ? [] : getWeeksForMonth(year, month);

  const cards: { label: string; value: number; highlight?: boolean }[] = [
    { label: "Visitas", value: totals.visits, highlight: true },
    { label: "Abertos", value: totals.worked },
    { label: "Tratados", value: totals.treated, highlight: true },
    { label: "Focos", value: totals.focuses, highlight: true },
    { label: "Dias ativos", value: totals.activeDays },
    { label: "Fechados", value: totals.closed },
    { label: "Recusados", value: totals.refused },
    { label: "Abandonados", value: totals.abandoned },
    { label: "Vazios", value: totals.vacant },
  ];

  return (
    <div>
      <div className="period-bar">
        <select
          className="select"
          value={year}
          onChange={(e) => {
            setYear(Number(e.target.value));
            setWeek(-1);
          }}
        >
          {Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - 2 + i).map((y) => (
            <option key={y} value={y}>{y}</option>
          ))}
        </select>
        <select
          className="select"
          value={month}
          onChange={(e) => {
            setMonth(Number(e.target.value));
            setWeek(-1);
          }}
        >
          {MONTHS.map((m, i) => (
            <option key={m} value={i - 1}>{m}</option>
          ))}
        </select>
        {month !== -1 && (
          <select
            className="select"
            value={week}
            onChange={(e) => setWeek(Number(e.target.value))}
          >
            <option value={-1}>Todo o Mês</option>
            {weeks.map((w, i) => (
              <option key={i} value={i}>{w.label}</option>
            ))}
          </select>
        )}
        <span className="period-label">{formatDateLabel(year, month, week)}</span>
      </div>

      <div className="cards">
        {cards.map((c) => (
          <div key={c.label} className={`card ${c.highlight ? "card-highlight" : ""}`}>
            <div className="card-value">{c.value}</div>
            <div className="card-label">{c.label}</div>
          </div>
        ))}
      </div>

      <div className="table-card">
        <h2 className="table-title">Agentes</h2>
        {(loadingAgents || loadingStats) && <p className="muted">Carregando dados…</p>}
        {!loadingAgents && !loadingStats && activeAgents.length === 0 && (
          <p className="muted">Nenhum dado no período selecionado.</p>
        )}
        {activeAgents.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>Agente</th>
                <th className="num">Visitas</th>
                <th className="num">Abertos</th>
                <th className="num">Fechados</th>
                <th className="num">Recusados</th>
                <th className="num">Abandonados</th>
                <th className="num">Vazios</th>
                <th className="num">Tratados</th>
                <th className="num">Focos</th>
                <th className="num">Dias</th>
                <th className="num">RES</th>
                <th className="num">COM</th>
                <th className="num">TB</th>
                <th className="num">OUT</th>
                <th className="num">PE</th>
              </tr>
            </thead>
            <tbody>
              {activeAgents.map((agent) => {
                const st = statsByAgent.get(agent.id)!;
                return (
                  <tr key={agent.id}>
                    <td>
                      <span className="agent-name">{agent.agentName || agent.email}</span>
                      <span className={`source-badge ${st.source}`}>
                        {st.source === "summary" ? "sumarizado" : "leitura direta"}
                      </span>
                    </td>
                    <td className="num strong">{st.stats.visits}</td>
                    <td className="num">{st.stats.worked}</td>
                    <td className="num">{st.stats.closed}</td>
                    <td className="num">{st.stats.refused}</td>
                    <td className="num">{st.stats.abandoned}</td>
                    <td className="num">{st.stats.vacant}</td>
                    <td className="num strong">{st.stats.treated}</td>
                    <td className="num strong">{st.stats.focuses}</td>
                    <td className="num">
                  <button
                    className="link-btn"
                    onClick={() => setDaysAgent(agent)}
                    title="Ver dias de atividade"
                  >
                    {st.stats.activeDays}
                  </button>
                </td>
                    <td className="num">{st.stats.res}</td>
                    <td className="num">{st.stats.com}</td>
                    <td className="num">{st.stats.tb}</td>
                    <td className="num">{st.stats.out}</td>
                    <td className="num">{st.stats.pe}</td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr>
                <td>Total</td>
                <td className="num strong">{totals.visits}</td>
                <td className="num">{totals.worked}</td>
                <td className="num">{totals.closed}</td>
                <td className="num">{totals.refused}</td>
                <td className="num">{totals.abandoned}</td>
                <td className="num">{totals.vacant}</td>
                <td className="num strong">{totals.treated}</td>
                <td className="num strong">{totals.focuses}</td>
                <td className="num">{totals.activeDays}</td>
                <td className="num">{totals.res}</td>
                <td className="num">{totals.com}</td>
                <td className="num">{totals.tb}</td>
                <td className="num">{totals.out}</td>
                <td className="num">{totals.pe}</td>
              </tr>
            </tfoot>
          </table>
        )}
        <p className="muted small">
          Situações: {Object.entries(SITUATION_LABELS).filter(([k]) => ["F", "REC", "A", "V"].includes(k)).map(([k, v]) => `${k} = ${v}`).join(" · ")}
        </p>
      </div>

      {daysAgent && (
        <DaysModal
          agent={daysAgent}
          days={activitiesByAgent.data?.get(daysAgent.id) || []}
          onSelectDay={(date) => {
            setDetailAgent(daysAgent);
            setDetailDay(date);
            setDaysAgent(null);
          }}
          onClose={() => setDaysAgent(null)}
        />
      )}
      {detailAgent && detailDay && (
        <DayDetailModal
          agent={detailAgent}
          date={detailDay}
          onClose={() => {
            setDetailAgent(null);
            setDetailDay(null);
          }}
        />
      )}
    </div>
  );
}