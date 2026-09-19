import { useEffect, useMemo, useState } from "react";
import { AdminTimeline } from "./AdminTimeline";
import AgentProductionEditor from "./AgentProductionEditor";
import { getMetaValue, mutateMetaArray, setMetaValue, supabase } from "../../lib/supabase";
import { MONTHS } from "../../lib/constants";
import { fetchSystemSettings, useAccessRequests, useAgentNames, useBairros, useUnifiedProfiles } from "../../hooks/useAdminData";
import { useAgents, useAgentStatsByPeriod } from "../../hooks/usePortalData";
import type { UnifiedProfile } from "../../lib/adminTypes";

async function throwOn(res: { error: unknown }) {
  if (res.error) throw new Error((res.error as { message?: string }).message || "Erro Supabase");
}

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
  const [inviteAgentName, setInviteAgentName] = useState("");
  const [transferFrom, setTransferFrom] = useState<UnifiedProfile | null>(null);
  const [transferTo, setTransferTo] = useState<string>("");
  const [wiping, setWiping] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<{ uid: string; name: string } | null>(null);

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
      await throwOn(await supabase.from("profiles").update({ is_authorized: value }).eq("id", p.uid));
      showToast(value ? "Autorizado" : "Autorização removida");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRoleChange = async (p: UnifiedProfile, role: string) => {
    if (!p.uid) return showToast("Sem UID");
    try {
      // Papel vive só em profiles (sem coleções admins/supervisors no Supabase).
      await throwOn(await supabase.from("profiles").update({ role }).eq("id", p.uid));
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
      const agentId = p.agentId || p.uid!;
      const { data: ag } = await supabase.from("agents").select("agent_name").eq("id", agentId).maybeSingle();
      const existing = (ag?.agent_name as string | null)?.trim().toUpperCase() || null;
      const shouldRename = !existing || existing !== upper;
      await throwOn(await supabase.from("profiles").update({ agent_name: upper }).eq("id", p.uid!));
      await throwOn(
        await supabase.from("agents").upsert(
          { id: agentId, agent_name: upper, email: p.email.toLowerCase() },
          { onConflict: "id" },
        ),
      );
      await mutateMetaArray("agent_info", "names", upper);
      if (shouldRename && agentId) {
        await renameAgentField(agentId, "houses", upper);
        await renameAgentField(agentId, "day_activities", upper);
      }
      showToast(`Vinculado a ${upper}${shouldRename ? " (produção renomeada)" : ""}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  async function renameAgentField(agentId: string, table: "houses" | "day_activities", value: string) {
    // Um UPDATE relacional substitui a paginação 450/400 do Firestore.
    await throwOn(await supabase.from(table).update({ agent_name: value }).eq("agent_id", agentId));
  }

  const handleClearVinculo = async (p: UnifiedProfile) => {
    if (!p.uid) return;
    try {
      await throwOn(await supabase.from("profiles").update({ agent_name: null }).eq("id", p.uid));
      showToast("Vínculo removido");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleDelete = async (p: UnifiedProfile, deleteCloud: boolean) => {
    if (!p.uid && !p.agentId) {
      if (p.agentName) {
        try {
          await mutateMetaArray("agent_info", "names", undefined, p.agentName);
          showToast("Nome removido da lista mestra");
        } catch (e) {
          showToast(e instanceof Error ? e.message : String(e));
        }
      }
      return;
    }
    const uid = p.uid || p.agentId!;
    if (!confirm(`Excluir ${p.displayName}? Perfil e produção vinculada serão removidos (login futuro recria o perfil).${deleteCloud ? " Arquivos de backup também serão apagados (irreversível)." : ""}`)) return;
    try {
      if (deleteCloud && p.agentId) await purgeAgentCompletely(p.agentId);
      else {
        // Excluir perfil: produção vai junto (FK profiles->agents em cascata).
        await throwOn(await supabase.from("houses").delete().eq("agent_id", uid));
        await throwOn(await supabase.from("day_activities").delete().eq("agent_id", uid));
        await throwOn(await supabase.from("monthly_summaries").delete().eq("agent_id", uid));
        await throwOn(await supabase.from("backups").delete().eq("agent_id", uid));
        await throwOn(await supabase.from("agents").delete().eq("id", uid));
        await throwOn(await supabase.from("profiles").delete().eq("id", uid));
      }
      showToast("Excluído");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  async function purgeAgentCompletely(agentId: string) {
    for (const table of ["houses", "day_activities", "monthly_summaries", "backups"] as const) {
      await throwOn(await supabase.from(table).delete().eq("agent_id", agentId));
    }
    await throwOn(await supabase.from("agents").delete().eq("id", agentId));
    // Cleanup storage backups folder
    try {
      const { data } = await supabase.storage.from("backups").list(agentId, { limit: 1000 });
      const files = (data || []).filter((e) => e.id !== null).map((e) => `${agentId}/${e.name}`);
      for (let i = 0; i < files.length; i += 100) {
        await supabase.storage.from("backups").remove(files.slice(i, i + 100));
      }
    } catch {
      // ignore if bucket not configured or error
    }
  }

  const handleAddMaster = async () => {
    const upper = newMasterName.trim().toUpperCase();
    if (!upper) return;
    try {
      await mutateMetaArray("agent_info", "names", upper);
      setNewMasterName("");
      showToast("Nome adicionado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleInvite = async () => {
    const email = inviteEmail.trim().toLowerCase();
    if (!email || !email.includes("@")) return showToast("Email inválido");
    // Sem pré-cadastro em tabela: o perfil nasce no primeiro login (trigger) e o
    // claim por e-mail vincula ao nome mestre. Papel/autorização se definem aqui após o login.
    try {
      if (inviteAgentName.trim()) {
        await mutateMetaArray("agent_info", "names", inviteAgentName.trim().toUpperCase());
      }
      setInviteEmail("");
      setInviteAgentName("");
      showToast(`Anotado para ${email}. Peça o login com Google e autorize o perfil aqui.`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRemoveMaster = async (name: string) => {
    try {
      await mutateMetaArray("agent_info", "names", undefined, name);
      showToast("Removido");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleApproveRequest = async (reqId: string, _email: string, agentName: string) => {
    try {
      await throwOn(await supabase.from("access_requests").update({ status: "APPROVED" }).eq("id", reqId));
      const { data: req } = await supabase.from("access_requests").select("requester_id,email").eq("id", reqId).maybeSingle();
      const upper = agentName.toUpperCase();
      if (req?.requester_id) {
        await throwOn(
          await supabase.from("profiles").update({ is_authorized: true, agent_name: upper, role: "AGENT" }).eq("id", req.requester_id as string),
        );
      } else if (req?.email) {
        await throwOn(
          await supabase.from("profiles").update({ is_authorized: true, agent_name: upper, role: "AGENT" }).eq("email", (req.email as string).toLowerCase()),
        );
      }
      await mutateMetaArray("agent_info", "names", upper);
      showToast("Aprovado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRejectRequest = async (reqId: string) => {
    try {
      await throwOn(await supabase.from("access_requests").update({ status: "REJECTED" }).eq("id", reqId));
      showToast("Rejeitado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleTransferExecute = async () => {
    if (!transferFrom || !transferTo) return showToast("Selecione origem e destino");
    if (transferFrom.uid === transferTo) return showToast("Origem e destino iguais");
    const fromUid = transferFrom.uid || transferFrom.agentId!;
    const toUid = transferTo;
    const targetProfile = profiles.find((p) => p.uid === toUid || p.agentId === toUid);
    const targetName = targetProfile?.agentName?.toUpperCase() || "";
    if (!confirm(`Transferir TODOS os dados de ${transferFrom.displayName} para ${targetProfile?.displayName || toUid}? Irreversível.`)) return;
    try {
      showToast("Transferindo…");
      // Conta a origem antes (o RETURNING do UPDATE pode vir truncado em moves grandes).
      const { count: fromCount } = await supabase.from("houses").select("*", { count: "exact", head: true }).eq("agent_id", fromUid);
      // Move relacional: dois UPDATEs substituem cópia+delete em chunks de 100.
      const movedRes = await supabase.from("houses").update({ agent_id: toUid, agent_uid: toUid, agent_name: targetName || undefined }).eq("agent_id", fromUid).select("natural_key");
      if (movedRes.error) throw new Error(movedRes.error.message);
      await throwOn(await supabase.from("day_activities").update({ agent_id: toUid, agent_uid: toUid, agent_name: targetName || undefined }).eq("agent_id", fromUid));
      await throwOn(await supabase.from("profiles").update({ require_data_reset: true }).eq("id", fromUid));
      showToast(`Transferência concluída: ${fromCount ?? "?"} imóveis`);
      setTransferFrom(null);
      setTransferTo("");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };

  const handleWipeExecute = async (p: UnifiedProfile) => {
    const uid = p.uid || p.agentId!;
    if (!confirm(`Wipe remoto de ${p.displayName}? Nuvem será apagada e app fará reset no próximo acesso. Perfil mantido.`)) return;
    setWiping(uid);
    try {
      for (const table of ["houses", "day_activities", "monthly_summaries", "backups"] as const) {
        await throwOn(await supabase.from(table).delete().eq("agent_id", uid));
      }
      await throwOn(await supabase.from("profiles").update({ require_data_reset: true }).eq("id", uid));
      showToast("Wipe concluído — nuvem limpa e reset agendado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    } finally {
      setWiping(null);
    }
  };

  const handleAddBairro = async () => {
    const upper = newBairro.trim().toUpperCase();
    if (!upper) return;
    try {
      await mutateMetaArray("locations", "bairros", upper);
      setNewBairro("");
      showToast("Bairro adicionado");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleRemoveBairro = async (b: string) => {
    try {
      await mutateMetaArray("locations", "bairros", undefined, b);
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
      const merged = { ...(await getMetaValue("settings").catch(() => ({} as Record<string, unknown>))), custom_activities: next };
      await setMetaValue("settings", merged);
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
      const merged = { ...(await getMetaValue("settings").catch(() => ({} as Record<string, unknown>))), custom_activities: next };
      await setMetaValue("settings", merged);
      setSettings((s) => ({ ...s, custom_activities: next }));
      showToast("Atividade removida");
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    }
  };
  const handleSettingChange = async (key: string, value: unknown) => {
    try {
      const merged = { ...(await getMetaValue("settings").catch(() => ({} as Record<string, unknown>))), [key]: value };
      await setMetaValue("settings", merged);
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
        <p className="muted small">Anota o nome na lista mestra. O perfil nasce no primeiro login com Google e é vinculado por e-mail — depois autorize aqui.</p>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))", gap: 8, marginTop: 8 }}>
          <input className="input" placeholder="Email" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} />
          <select className="select" value={inviteAgentName} onChange={(e) => setInviteAgentName(e.target.value)}>
            <option value="">Agente (opcional)</option>
            {masterNames.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
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
                      <button className="btn btn-sm btn-outline" onClick={() => setTransferFrom(p)} disabled={!p.uid}>Transferir dados</button>
                      <button className="btn btn-sm btn-outline" onClick={() => handleWipeExecute(p)} disabled={!!wiping || !p.uid}>{wiping === (p.uid || p.agentId) ? "Limpando…" : "Wipe remoto"}</button>
                      <button className="btn btn-sm btn-outline" onClick={() => setTimeline({ uid: p.uid || p.agentId!, name: p.displayName })} disabled={!p.uid && !p.agentId}>Timeline</button>
                      <button className="btn btn-sm btn-danger" onClick={() => handleDelete(p, false)}>Excluir perfil</button>
                      {p.agentId && <button className="btn btn-sm btn-danger" onClick={() => handleDelete(p, true)}>Excluir + nuvem</button>}
                    </div>

                    <div className="muted small">
                      UID: {p.uid || "—"} • AgentID: {p.agentId || "—"} • Último sinc: {typeof p.lastSyncTime === "number" ? new Date(p.lastSyncTime).toLocaleString("pt-BR") : "Nunca"} • Fonte: {agentStats?.source === "summary" ? "sumarizado" : agentStats?.source === "raw" ? "leitura direta" : "—"}
                    </div>

                    {p.agentId && p.agentName && (
                      <AgentProductionEditor
                        agentId={p.agentId}
                        agentName={p.agentName}
                        lastSyncTime={typeof p.lastSyncTime === "number" ? p.lastSyncTime : null}
                      />
                    )}
                  </div>
                )}
              </div>
            );
          })}
          {profiles.length === 0 && <p className="muted small">Nenhum perfil encontrado.</p>}
        </div>
      </div>

      {transferFrom && (
        <div className="card" style={{ borderColor: "var(--warning)", background: "#fffbeb" }}>
          <h3>Transferir dados de {transferFrom.displayName}</h3>
          <p className="muted small">Selecione o destino. Todos os imóveis e atividades serão movidos. Origem receberá `requireDataReset` e fará wipe local no próximo acesso. Irreversível.</p>
          <select className="select" value={transferTo} onChange={(e) => setTransferTo(e.target.value)} style={{ marginTop: 8 }}>
            <option value="">Selecione destino…</option>
            {profiles.filter((p) => p.uid && p.uid !== transferFrom.uid && !p.isPreRegistered).map((p) => (
              <option key={p.uid!} value={p.uid!}>{p.displayName} — {p.email}</option>
            ))}
          </select>
          <div className="flex gap-2" style={{ marginTop: 8 }}>
            <button className="btn btn-primary btn-sm" onClick={handleTransferExecute} disabled={!transferTo}>Confirmar transferência</button>
            <button className="btn btn-outline btn-sm" onClick={() => { setTransferFrom(null); setTransferTo(""); }}>Cancelar</button>
          </div>
        </div>
      )}
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
      {timeline && <AdminTimeline uid={timeline.uid} agentName={timeline.name} onClose={() => setTimeline(null)} />}
    </div>
  );
}
