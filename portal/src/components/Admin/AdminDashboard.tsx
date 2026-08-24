import { useEffect, useMemo, useState } from "react";
import { arrayRemove, arrayUnion, deleteDoc, doc, setDoc, updateDoc, writeBatch } from "firebase/firestore";
import { db } from "../../lib/firebase";
import { MONTHS } from "../../lib/constants";
import { fetchSystemSettings, useAccessRequests, useAgentNames, useBairros, useUnifiedProfiles } from "../../hooks/useAdminData";
import { useAgents, useAgentStatsByPeriod } from "../../hooks/usePortalData";
import type { UnifiedProfile } from "../../lib/adminTypes";

export function AdminDashboard() {
  const [tab, setTab] = useState<"gestao" | "config">("gestao");
  const [search, setSearch] = useState("");
  const [year, setYear] = useState(new Date().getFullYear());
  const [month, setMonth] = useState(new Date().getMonth());
  const [week] = useState(-1);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [newMasterName, setNewMasterName] = useState("");
  const [newBairro, setNewBairro] = useState("");
  const [newActivity, setNewActivity] = useState("");
  const [settings, setSettings] = useState<Record<string, unknown>>({});
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<"AGENT" | "SUPERVISOR" | "ADMIN">("AGENT");
  const [inviteAgentName, setInviteAgentName] = useState("");
  const [inviteAuthorized, setInviteAuthorized] = useState(true);

  const { profiles, loading } = useUnifiedProfiles(search);
  const { names: masterNames } = useAgentNames();
  const { requests } = useAccessRequests();
  const { bairros } = useBairros();
  const { data: agents } = useAgents();
  const { statsByAgent } = useAgentStatsByPeriod(agents, year, month, week);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  const handleAuthorize = async (p: UnifiedProfile, value: boolean) => {
    if (!p.uid) return showToast("Perfil sem UID não pode ser autorizado");
    try {
      await updateDoc(doc(db, "users", p.uid), { isAuthorized: value });
      showToast(value ? "Autorizado" : "Autorização removida");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRoleChange = async (p: UnifiedProfile, role: string) => {
    if (!p.uid) return showToast("Sem UID");
    try {
      const batch = writeBatch(db);
      batch.update(doc(db, "users", p.uid), { role });
      const adminRef = doc(db, "admins", p.uid);
      const supRef = doc(db, "supervisors", p.uid);
      if (role === "ADMIN") {
        batch.set(adminRef, { email: p.email.toLowerCase() });
        batch.delete(supRef);
      } else if (role === "SUPERVISOR") {
        batch.set(supRef, { email: p.email.toLowerCase() });
        batch.delete(adminRef);
      } else {
        batch.delete(adminRef);
        batch.delete(supRef);
      }
      await batch.commit();
      showToast(`Função alterada para ${role}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleVincular = async (p: UnifiedProfile, name: string) => {
    if (!p.uid) return showToast("Sem UID");
    const upper = name.trim().toUpperCase();
    if (!upper) return;
    try {
      await updateDoc(doc(db, "users", p.uid), { agentName: upper });
      // also ensure agent doc exists/update
      const agentId = p.agentId || p.uid;
      await setDoc(doc(db, "agents", agentId), { agentName: upper, email: p.email.toLowerCase() }, { merge: true });
      await updateDoc(doc(db, "metadata", "agent_info"), { names: arrayUnion(upper) });
      showToast(`Vinculado a ${upper}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleClearVinculo = async (p: UnifiedProfile) => {
    if (!p.uid) return;
    try {
      await updateDoc(doc(db, "users", p.uid), { agentName: null });
      showToast("Vínculo removido");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleDelete = async (p: UnifiedProfile, deleteCloud: boolean) => {
    if (!p.uid && !p.agentId) {
      // only master name
      if (p.agentName) {
        try {
          await updateDoc(doc(db, "metadata", "agent_info"), { names: arrayRemove(p.agentName) });
          showToast("Nome removido da lista mestra");
        } catch (e) {
          showToast(e instanceof Error ? e.message : String(e));
        }
      }
      return;
    }
    const uid = p.uid || p.agentId!;
    if (!confirm(`Excluir ${p.displayName}? ${deleteCloud ? "Dados da nuvem também serão apagados (irreversível)." : ""}`)) return;
    try {
      await deleteDoc(doc(db, "users", uid));
      await deleteDoc(doc(db, "admins", uid));
      await deleteDoc(doc(db, "supervisors", uid));
      if (deleteCloud && p.agentId) {
        // purge agent subcollections count limited to client; do simple doc delete, subcollections remain but marked
        await deleteDoc(doc(db, "agents", p.agentId));
      }
      showToast("Excluído");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleAddMaster = async () => {
    const upper = newMasterName.trim().toUpperCase();
    if (!upper) return;
    try {
      await updateDoc(doc(db, "metadata", "agent_info"), { names: arrayUnion(upper) });
      setNewMasterName("");
      showToast("Nome adicionado");
    } catch (e) {
      try {
        await setDoc(doc(db, "metadata", "agent_info"), { names: arrayUnion(upper) }, { merge: true });
        setNewMasterName("");
        showToast("Nome adicionado");
      } catch {
        showToast(e instanceof Error ? e.message : String(e));
      }
    }
  };

  const handleInvite = async () => {
    const email = inviteEmail.trim().toLowerCase();
    if (!email || !email.includes("@")) return showToast("Email inválido");
    const preId = `pre_${email.replace(/\./g, "_").replace(/@/g, "_")}`;
    const data: Record<string, unknown> = {
      email,
      role: inviteRole,
      isAuthorized: inviteAuthorized,
      isPreRegistered: true,
      displayName: inviteAgentName.trim().toUpperCase() || email,
      agentName: inviteAgentName.trim().toUpperCase() || null,
      createdAt: Date.now(),
    };
    try {
      await setDoc(doc(db, "users", preId), data, { merge: true });
      if (inviteAgentName.trim()) {
        await setDoc(doc(db, "metadata", "agent_info"), { names: arrayUnion(inviteAgentName.trim().toUpperCase()) }, { merge: true });
      }
      setInviteEmail("");
      setInviteAgentName("");
      showToast(`Convite criado para ${email}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRemoveMaster = async (name: string) => {
    try {
      await updateDoc(doc(db, "metadata", "agent_info"), { names: arrayRemove(name) });
      showToast("Removido");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleApproveRequest = async (reqId: string, _email: string, agentName: string) => {
    try {
      const batch = writeBatch(db);
      batch.update(doc(db, "access_requests", reqId), { status: "APPROVED" });
      const uid = reqId;
      batch.update(doc(db, "users", uid), { isAuthorized: true, agentName: agentName.toUpperCase(), role: "AGENT" });
      batch.set(doc(db, "metadata", "agent_info"), { names: arrayUnion(agentName.toUpperCase()) }, { merge: true });
      await batch.commit();
      showToast("Aprovado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRejectRequest = async (reqId: string) => {
    try {
      await updateDoc(doc(db, "access_requests", reqId), { status: "REJECTED" });
      showToast("Rejeitado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleAddBairro = async () => {
    const upper = newBairro.trim().toUpperCase();
    if (!upper) return;
    try {
      await setDoc(doc(db, "metadata", "locations"), { bairros: arrayUnion(upper) }, { merge: true });
      setNewBairro("");
      showToast("Bairro adicionado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleRemoveBairro = async (b: string) => {
    try {
      await updateDoc(doc(db, "metadata", "locations"), { bairros: arrayRemove(b) });
      showToast("Bairro removido");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleAddActivity = async () => {
    const val = newActivity.trim();
    if (!val) return;
    try {
      const cur = (settings.custom_activities as string[] | string | undefined);
      const list = Array.isArray(cur) ? cur : typeof cur === "string" && cur ? cur.split(",").map((s) => s.trim()).filter(Boolean) : [];
      const next = [...list, val];
      await setDoc(doc(db, "metadata", "settings"), { custom_activities: next }, { merge: true });
      setSettings((s) => ({ ...s, custom_activities: next }));
      setNewActivity("");
      showToast("Atividade adicionada");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleRemoveActivity = async (act: string) => {
    try {
      const cur = (settings.custom_activities as string[] | string | undefined);
      const list = Array.isArray(cur) ? cur : typeof cur === "string" && cur ? cur.split(",").map((s) => s.trim()).filter(Boolean) : [];
      const next = list.filter((a) => a !== act);
      await setDoc(doc(db, "metadata", "settings"), { custom_activities: next }, { merge: true });
      setSettings((s) => ({ ...s, custom_activities: next }));
      showToast("Atividade removida");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleSettingChange = async (key: string, value: unknown) => {
    try {
      await setDoc(doc(db, "metadata", "settings"), { [key]: value }, { merge: true });
      setSettings((s) => ({ ...s, [key]: value }));
      showToast("Configuração salva");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    if (tab === "config") {
      setSettingsLoading(true);
      fetchSystemSettings()
        .then(setSettings)
        .finally(() => setSettingsLoading(false));
    }
  }, [tab]);

  const years = useMemo(() => Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - 2 + i), []);

  if (loading) return <p className="muted">Carregando administração…</p>;

  return (
    <div className="admin-stack">
      {toast && <div className="toast success" style={{ position: "fixed", top: 12, right: 12, zIndex: 9999 }}>{toast}</div>}

      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        <button className={`btn ${tab === "gestao" ? "btn-primary" : "btn-outline"}`} onClick={() => setTab("gestao")}>Gestão</button>
        <button className={`btn ${tab === "config" ? "btn-primary" : "btn-outline"}`} onClick={() => setTab("config")}>Configurações</button>
      </div>

      {tab === "gestao" ? (
        <>
          {/* Period filter */}
      <div className="period-bar">
        <select className="select" value={year} onChange={(e) => setYear(Number(e.target.value))} style={{ maxWidth: 110 }}>
          {years.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <select className="select" value={month} onChange={(e) => setMonth(Number(e.target.value))} style={{ maxWidth: 140 }}>
          {MONTHS.map((m, i) => <option key={m} value={i - 1}>{m}</option>)}
        </select>
        <input className="input" placeholder="Buscar nome ou email..." value={search} onChange={(e) => setSearch(e.target.value)} style={{ maxWidth: 260 }} />
        <span className="muted small">
          {profiles.length} perfis • {masterNames.length} na lista mestra • {bairros.length} bairros
        </span>
      </div>

      {/* Access requests */}
      {requests.length > 0 && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h3>Solicitações pendentes ({requests.length})</h3>
          <div style={{ display: "flex", gap: 12, overflowX: "auto", paddingTop: 8 }}>
            {requests.map((r) => (
              <div key={r.id} className="panel-card" style={{ minWidth: 260, padding: 12 }}>
                <div style={{ fontWeight: 600 }}>{r.email}</div>
                <div className="muted small">{r.requestedName || r.displayName || "—"}</div>
                <div className="flex gap-2" style={{ marginTop: 8 }}>
                  <select className="select" defaultValue="" onChange={(e) => { if(e.target.value) handleApproveRequest(r.id, r.email, e.target.value); }}>
                    <option value="">Aprovar como…</option>
                    {masterNames.slice(0, 8).map((n) => <option key={n} value={n}>{n}</option>)}
                  </select>
                  <button className="btn btn-sm btn-outline" onClick={() => handleRejectRequest(r.id)}>Rejeitar</button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Invite */}
      <div className="card" style={{ marginBottom: 16 }}>
        <h3>Convidar usuário (pré-cadastro)</h3>
        <p className="muted small">Cria um perfil `pre_` que será vinculado automaticamente quando o usuário fizer login.</p>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))", gap: 8, marginTop: 8 }}>
          <input className="input" placeholder="Email" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} />
          <select className="select" value={inviteRole} onChange={(e) => setInviteRole(e.target.value as never)}>
            <option value="AGENT">AGENT</option>
            <option value="SUPERVISOR">SUPERVISOR</option>
            <option value="ADMIN">ADMIN</option>
          </select>
          <select className="select" value={inviteAgentName} onChange={(e) => setInviteAgentName(e.target.value)}>
            <option value="">Agente (opcional)</option>
            {masterNames.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
          <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 13 }}>
            <input type="checkbox" checked={inviteAuthorized} onChange={(e) => setInviteAuthorized(e.target.checked)} /> Já autorizado
          </label>
        </div>
        <button className="btn btn-primary btn-sm" style={{ marginTop: 8 }} onClick={handleInvite}>Criar convite</button>
      </div>

      {/* Master list */}
      <div className="card" style={{ marginBottom: 16 }}>
        <h3>Lista Mestra de Nomes</h3>
        <p className="muted small">Nomes que aparecem nos dropdowns de vinculação e convite. Clique em × para remover.</p>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
          {masterNames.map((n) => (
            <span key={n} className="badge badge-primary" style={{ gap: 6 }}>
              {n}
              <button onClick={() => handleRemoveMaster(n)} style={{ background: "none", border: "none", cursor: "pointer", fontWeight: 700 }} aria-label={`Remover ${n}`}>×</button>
            </span>
          ))}
          {masterNames.length === 0 && <span className="muted small">Nenhum nome cadastrado.</span>}
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 12, maxWidth: 360 }}>
          <input className="input" placeholder="Novo nome (ex: JOÃO SILVA)" value={newMasterName} onChange={(e) => setNewMasterName(e.target.value)} />
          <button className="btn btn-primary btn-sm" onClick={handleAddMaster}>Adicionar</button>
        </div>
      </div>

      {/* Unified list */}
      <div className="card">
        <h3>Perfis Unificados</h3>
        <p className="muted small">Clique no cartão para expandir ações e estatísticas ({month === -1 ? `Ano ${year}` : `${MONTHS[month + 1]} ${year}`}).</p>
        <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 12 }}>
          {profiles.map((p) => {
            const isExpanded = expanded === (p.uid || p.email);
            const agentStats = p.agentId ? statsByAgent.get(p.agentId) : undefined;
            const stats = agentStats?.stats;
            return (
              <div key={p.uid || p.email} className="panel-card" style={{ padding: 14, borderLeft: p.isPreRegistered ? "3px solid var(--warning)" : p.role === "ADMIN" ? "3px solid var(--primary)" : "3px solid var(--border)" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 12, cursor: "pointer" }} onClick={() => setExpanded(isExpanded ? null : (p.uid || p.email))}>
                  <div style={{ width: 36, height: 36, borderRadius: 999, background: "var(--bg-subtle)", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700, fontSize: 14 }}>
                    {(p.displayName[0] || "?").toUpperCase()}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                      {p.displayName} {p.isPreRegistered && <span className="badge badge-warning" style={{ marginLeft: 6 }}>PRÉ-REGISTRO</span>}
                    </div>
                    <div className="muted small" style={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{p.email} • {p.agentName || "sem agente vinculado"}</div>
                  </div>
                  <span className={`badge ${p.role === "ADMIN" ? "badge-primary" : p.role === "SUPERVISOR" ? "badge-warning" : "badge-success"}`}>{p.role}</span>
                  <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12 }} onClick={(e) => e.stopPropagation()}>
                    <input type="checkbox" checked={p.isAuthorized} onChange={(e) => handleAuthorize(p, e.target.checked)} disabled={!p.uid} /> Autorizado
                  </label>
                  <span style={{ transform: isExpanded ? "rotate(180deg)" : "none", transition: "0.15s" }}>▾</span>
                </div>

                {isExpanded && (
                  <div style={{ marginTop: 12, borderTop: "1px solid var(--border)", paddingTop: 12, display: "flex", flexDirection: "column", gap: 10 }}>
                    {/* Stats */}
                    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(90px,1fr))", gap: 8 }}>
                      <div className="badge badge-primary" style={{ justifyContent: "center", padding: "6px 8px" }}>Visitas: {stats?.visits ?? "—"}</div>
                      <div className="badge badge-success" style={{ justifyContent: "center", padding: "6px 8px" }}>Abertos: {stats?.worked ?? "—"}</div>
                      <div className="badge badge-success" style={{ justifyContent: "center", padding: "6px 8px", background: "#fecaca", color: "#7f1d1d" }}>Focos: {stats?.focuses ?? "—"}</div>
                      <div className="badge badge-warning" style={{ justifyContent: "center", padding: "6px 8px" }}>Dias: {stats?.activeDays ?? "—"}</div>
                    </div>
                    <div className="muted small">
                      Situação: Abertos {stats?.worked ?? 0} • Fechados {stats?.closed ?? 0} • Recusados {stats?.refused ?? 0} • Abandonados {stats?.abandoned ?? 0} • Vazios {stats?.vacant ?? 0} | Imóveis: R {stats?.res ?? 0} C {stats?.com ?? 0} TB {stats?.tb ?? 0} PE {stats?.pe ?? 0} O {stats?.out ?? 0}
                    </div>

                    {/* Actions row 1 */}
                    <div className="flex gap-2" style={{ flexWrap: "wrap" }}>
                      <select className="select" value={p.role} onChange={(e) => handleRoleChange(p, e.target.value)} style={{ maxWidth: 140 }} disabled={!p.uid}>
                        <option value="AGENT">AGENT</option>
                        <option value="SUPERVISOR">SUPERVISOR</option>
                        <option value="ADMIN">ADMIN</option>
                      </select>
                      <select className="select" defaultValue="" onChange={(e) => { if(e.target.value) handleVincular(p, e.target.value); e.target.value=""; }} style={{ maxWidth: 200 }} disabled={!p.uid}>
                        <option value="">Vincular agente…</option>
                        {masterNames.map((n) => <option key={n} value={n}>{n}</option>)}
                      </select>
                      {p.agentName && <button className="btn btn-sm btn-outline" onClick={() => handleClearVinculo(p)}>Desvincular</button>}
                    </div>

                    {/* Actions row 2 */}
                    <div className="flex gap-2" style={{ flexWrap: "wrap" }}>
                      <button className="btn btn-sm btn-outline" onClick={() => showToast("Transferir: selecione destino em Config avançada")}>Transferir dados</button>
                      <button className="btn btn-sm btn-outline" onClick={() => showToast("Restaurar: use arquivo JSON em Config avançada")}>Restaurar backup</button>
                      <button className="btn btn-sm btn-danger" onClick={() => handleDelete(p, false)}>Excluir perfil</button>
                      {p.agentId && <button className="btn btn-sm btn-danger" onClick={() => handleDelete(p, true)}>Excluir + nuvem</button>}
                    </div>

                    <div className="muted small">
                      UID: {p.uid || "—"} • AgentID: {p.agentId || "—"} • Último sinc: {p.lastSyncTime ? (typeof p.lastSyncTime === "number" ? new Date(p.lastSyncTime).toLocaleString("pt-BR") : p.lastSyncTime.seconds ? new Date(p.lastSyncTime.seconds*1000).toLocaleString("pt-BR") : "—") : "Nunca"} • Fonte: {agentStats?.source === "summary" ? "sumarizado" : agentStats?.source === "raw" ? "leitura direta" : "—"}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
          {profiles.length === 0 && <p className="muted small">Nenhum perfil encontrado.</p>}
        </div>
      </div>
        </>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {settingsLoading ? (
            <p className="muted">Carregando configurações…</p>
          ) : (
            <>
              <div className="card">
                <h3>Jornada e Metas</h3>
                <div style={{ marginTop: 12 }}>
                  <label className="form-label">Meta diária (máx. imóveis abertos): {(settings.max_open_houses as number) || 30}</label>
                  <input
                    type="range"
                    min={25}
                    max={35}
                    value={(settings.max_open_houses as number) || 30}
                    onChange={(e) => handleSettingChange("max_open_houses", Number(e.target.value))}
                    style={{ width: "100%" }}
                  />
                  <div className="muted small">Define quantos imóveis podem ficar em aberto por dia (padrão 30).</div>
                </div>
              </div>

              <div className="card">
                <h3>Bairros Atendidos</h3>
                <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
                  {bairros.map((b) => (
                    <span key={b} className="badge badge-primary" style={{ gap: 6 }}>
                      {b}
                      <button onClick={() => handleRemoveBairro(b)} style={{ background: "none", border: "none", cursor: "pointer", fontWeight: 700 }}>×</button>
                    </span>
                  ))}
                  {bairros.length === 0 && <span className="muted small">Nenhum bairro cadastrado.</span>}
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 12, maxWidth: 360 }}>
                  <input className="input" placeholder="Novo bairro (ex: CENTRO)" value={newBairro} onChange={(e) => setNewBairro(e.target.value)} />
                  <button className="btn btn-primary btn-sm" onClick={handleAddBairro}>Adicionar</button>
                </div>
              </div>

              <div className="card">
                <h3>Atividades Padrão</h3>
                {(() => {
                  const cur = settings.custom_activities as string[] | string | undefined;
                  const list: string[] = Array.isArray(cur) ? cur : typeof cur === "string" && cur ? cur.split(",").map((s) => s.trim()).filter(Boolean) : [];
                  return (
                    <>
                      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginTop: 8 }}>
                        {list.map((a) => (
                          <span key={a} className="badge badge-warning" style={{ gap: 6 }}>
                            {a}
                            <button onClick={() => handleRemoveActivity(a)} style={{ background: "none", border: "none", cursor: "pointer", fontWeight: 700 }}>×</button>
                          </span>
                        ))}
                        {list.length === 0 && <span className="muted small">Nenhuma atividade cadastrada.</span>}
                      </div>
                      <div style={{ display: "flex", gap: 8, marginTop: 12, maxWidth: 360 }}>
                        <input className="input" placeholder="Nova atividade" value={newActivity} onChange={(e) => setNewActivity(e.target.value)} />
                        <button className="btn btn-primary btn-sm" onClick={handleAddActivity}>Adicionar</button>
                      </div>
                    </>
                  );
                })()}
              </div>

              <div className="card">
                <h3>Preferências de Sistema</h3>
                <label style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 12, cursor: "pointer" }}>
                  <input
                    type="checkbox"
                    checked={!!settings.default_easy_mode}
                    onChange={(e) => handleSettingChange("default_easy_mode", e.target.checked)}
                  />
                  Modo simplificado por padrão
                </label>
                <div style={{ marginTop: 12 }}>
                  <div className="form-label">Cor temática recomendada</div>
                  <div style={{ display: "flex", gap: 8, marginTop: 6 }}>
                    {[
                      ["#10b981", "Esmeralda"],
                      ["#0ea5e9", "Oceano"],
                      ["#8b5cf6", "Violeta"],
                      ["#f59e0b", "Âmbar"],
                      ["#f43f5e", "Rosa"],
                    ].map(([color, label]) => (
                      <button
                        key={color}
                        onClick={() => handleSettingChange("recommended_theme_color", color)}
                        title={label}
                        style={{
                          width: 32,
                          height: 32,
                          borderRadius: 999,
                          background: color,
                          border: settings.recommended_theme_color === color ? "3px solid var(--text-primary)" : "2px solid var(--border)",
                          cursor: "pointer",
                        }}
                      />
                    ))}
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
