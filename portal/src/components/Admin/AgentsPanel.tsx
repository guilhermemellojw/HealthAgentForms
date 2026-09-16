import { useEffect, useState } from "react";
import { collection, getDocs, orderBy, query } from "firebase/firestore";
import { db } from "../../lib/firebase";
import { useStaffGate } from "../../hooks/useAuth";
import type { AgentDoc } from "../../lib/types";

export function AgentsPanel() {
  const { isAdmin } = useStaffGate();
  const [agents, setAgents] = useState<(AgentDoc & { id: string })[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");

  useEffect(() => {
    if (!isAdmin) {
      setLoading(false);
      return;
    }

    const loadAgents = async () => {
      setLoading(true);
      try {
        const agentsRef = collection(db, "agents");
        const snap = await getDocs(query(agentsRef, orderBy("agentName")));
        const agentList = snap.docs.map((doc) => ({
          id: doc.id,
          ...(doc.data() as Omit<AgentDoc, "id">),
        })) as (AgentDoc & { id: string })[];
        setAgents(agentList);
      } catch (err) {
        console.error("Error loading agents:", err);
      } finally {
        setLoading(false);
      }
    };

    loadAgents();
  }, [isAdmin]);

  if (!isAdmin) return null;

  if (loading) {
    return <p>Carregando...</p>;
  }

  // Filtrar agentes pelo termo de busca
  const filteredAgents = agents.filter(
    (a) =>
      (a.agentName || "").toLowerCase().includes(search.toLowerCase()) ||
      (a.email || "").toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="panel-card">
      <h3>Agentes {agents.length}</h3>

      {/* Barra de busca */}
      <div className="mb-3">
        <input
          type="text"
          placeholder="Buscar agente..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="form-control w-100 rounded-md px-3 py-2 text-sm"
        />
      </div>

      {filteredAgents.length === 0 && (
        <p className="text-muted small">Nenhum agente encontrado.</p>
      )}

      <div className="table-section">
        <table className="user-table">
          <thead>
            <tr>
              <th>Nome</th>
              <th>E-mail</th>
              <th>Último sinc.</th>
              <th>Produção</th>
              <th style={{ width: 140 }}>Ações</th>
            </tr>
          </thead>
          <tbody>
            {filteredAgents.map((agent) => {
              let lastSync = "Nunca";
              if (agent.lastSyncTime) {
                if (typeof agent.lastSyncTime === "number") {
                  lastSync = new Date(agent.lastSyncTime).toLocaleDateString("pt-BR");
                } else {
                  const ts = agent.lastSyncTime as unknown as { seconds?: number };
                  if (ts.seconds) lastSync = new Date(ts.seconds * 1000).toLocaleDateString("pt-BR");
                }
              }

              // Produção resumo - stats não está no doc agents, manter 0 até integração futura
              const stats = (agent as unknown as { stats?: { worked: number; treated: number; focuses: number; closed: number; vacant: number } }).stats;
              const worked = stats?.worked || 0;
              const treated = stats?.treated || 0;
              const focuses = stats?.focuses || 0;
              const closed = stats?.closed || 0;
              const vacant = stats?.vacant || 0;

              return (
                <tr key={agent.id}>
                  <td>
                    <strong>{agent.agentName || "Sem nome"}</strong>
                    <br />
                    <span className="text-sm text-muted">
                      {agent.email || "—"}
                    </span>
                  </td>
                  <td>{agent.email || "—"}</td>
                  <td>
                    <span className="text-xs text-muted">{lastSync}</span>
                  </td>
                  <td className="text-center">
                    <span className="font-medium">{worked}</span>
                    <br />
                    <span className="text-xs text-success">Tratados: {treated}</span>
                    <br />
                    <span className="text-xs text-warning">Focos: {focuses}</span>
                    <br />
                    <span className="text-xs text-primary">Fechados: {closed}</span>
                    <br />
                    <span className="text-xs text-muted">Vazios: {vacant}</span>
                  </td>
                  <td>
                    <div className="flex gap-2">
                      <button
                        className="btn btn-sm btn-outline"
                        title="Ver detalhes"
                      >
                        👁️
                      </button>
                      <button
                        className="btn btn-sm btn-primary"
                        title="Sincronizar"
                      >
                        ⏳
                      </button>
                      <button
                        className="btn btn-sm btn-danger"
                        title="Remover"
                      >
                        ✕
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}