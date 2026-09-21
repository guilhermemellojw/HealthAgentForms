import { useMemo, useState } from "react";
import { useAgents, useAgentStatsByPeriod, useRgCoverage } from "../hooks/usePortalData";
import { useBairros } from "../hooks/useAdminData";
import { MONTHS, SITUATION_LABELS, WEEKDAYS } from "../lib/constants";
import { filterByPeriod, getWeeksForMonth, normalizeBairro } from "../lib/period";
import type { HouseStats } from "../lib/types";

const ZERO: HouseStats = {
  worked: 0, vacant: 0, closed: 0, abandoned: 0, refused: 0,
  focuses: 0, treated: 0, visits: 0, res: 0, com: 0, tb: 0, pe: 0, out: 0, activeDays: 0,
};

function sumStats(list: HouseStats[]): HouseStats {
  const out = { ...ZERO };
  for (const s of list) for (const k of Object.keys(out) as (keyof HouseStats)[]) out[k] += s[k];
  return out;
}

export default function SummaryPage() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [week, setWeek] = useState(-1);
  const [bairro, setBairro] = useState("");
  const [weekday, setWeekday] = useState(-1);
  // Cobertura de bairros só é buscada se o usuário tocar no filtro (evita um
  // collectionGroup sobre todas as casas do ano a cada abertura do Resumo).
  const [bairroArmed, setBairroArmed] = useState(false);

  const { data: agents, isLoading: loadingAgents } = useAgents();
  const { bairros: masterBairros } = useBairros();
  const { statsByAgent, isLoading: loadingStats } = useAgentStatsByPeriod(agents, year, month, week, bairro, weekday);
  const rgCoverage = useRgCoverage(year, bairroArmed);

  // Bairros realmente trabalhados no período (lidos das casas, como na aba RG),
  // unidos à lista mestra para não perder bairros cadastrados sem produção ainda.
  const bairroOptions = useMemo(() => {
    const set = new Set<string>();
    for (const h of filterByPeriod(rgCoverage.data ?? [], "data", year, month, week)) {
      const b = normalizeBairro(h.bairro);
      if (b) set.add(b);
    }
    for (const b of masterBairros) {
      const n = normalizeBairro(b);
      if (n) set.add(n);
    }
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [rgCoverage.data, masterBairros, year, month, week]);

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
        <select
          className="select"
          value={weekday}
          onChange={(e) => setWeekday(Number(e.target.value))}
          title="Filtrar por dia da semana"
        >
          <option value={-1}>Todos os dias</option>
          {WEEKDAYS.map((d, i) => (
            <option key={d} value={i}>{d}</option>
          ))}
        </select>
        <select
          className="select"
          value={bairro}
          onChange={(e) => setBairro(e.target.value)}
          onFocus={() => setBairroArmed(true)}
          onClick={() => setBairroArmed(true)}
          title="Filtrar por bairro"
          disabled={rgCoverage.isLoading && bairroOptions.length === 0}
        >
          <option value="">{rgCoverage.isLoading && bairroOptions.length === 0 ? "Carregando bairros…" : "Todos os bairros"}</option>
          {bairro && !bairroOptions.includes(bairro) && (
            <option value={bairro}>{bairro}</option>
          )}
          {bairroOptions.map((b) => (
            <option key={b} value={b}>{b}</option>
          ))}
        </select>
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
                <th className="agent-col">Agente</th>
                <th className="num" title="Visitas">VIS</th>
                <th className="num" title="Abertos">ABE</th>
                <th className="num" title="Fechados">FEC</th>
                <th className="num" title="Recusados">REC</th>
                <th className="num" title="Abandonados">ABA</th>
                <th className="num" title="Vazios">VAZ</th>
                <th className="num" title="Tratados">TRA</th>
                <th className="num" title="Focos">FOC</th>
                <th className="num" title="Dias de atividade">Dias</th>
                <th className="num" title="Residências">RES</th>
                <th className="num" title="Comércios">COM</th>
                <th className="num" title="Terrenos baldios">TB</th>
                <th className="num" title="Outros">OUT</th>
                <th className="num" title="Pontos estratégicos">PE</th>
              </tr>
            </thead>
            <tbody>
              {activeAgents.map((agent) => {
                const st = statsByAgent.get(agent.id)!;
                return (
                  <tr key={agent.id}>
                    <td className="agent-col">
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
                    <td className="num">{st.stats.activeDays}</td>
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
          Colunas: VIS = Visitas · ABE = Abertos · FEC = Fechados · REC = Recusados · ABA = Abandonados · VAZ = Vazios · TRA = Tratados · FOC = Focos
        </p>
        <p className="muted small">
          Situações: {Object.entries(SITUATION_LABELS).filter(([k]) => ["F", "REC", "A", "V"].includes(k)).map(([k, v]) => `${k} = ${v}`).join(" · ")}
        </p>
      </div>
    </div>
  );
}