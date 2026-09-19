import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { must, supabase, toActivityDoc, toHouseDoc, toSnakePayload } from "../../lib/supabase";
import { useBairros } from "../../hooks/useAdminData";
import {
  buildAuditEntry, buildUpdatePayload, computeDayTotals,
  findDuplicateIds, houseToForm, lastUpdatedMs, monthYearOf,
  PROPERTY_OPTIONS, refreshMonthSummary, SITUATION_OPTIONS,
  todayDashSP, treatmentSummary, validateRowLabels,
  type VisitForm,
} from "../../lib/adminProduction";
import type { HouseDoc } from "../../lib/types";

interface Props {
  agentId: string;
  agentName: string;
  lastSyncTime: number | null;
}

function toDateInput(ddmmyyyy: string): string {
  const m = /^(\d{2})-(\d{2})-(\d{4})$/.exec(ddmmyyyy.trim());
  return m ? `${m[3]}-${m[2]}-${m[1]}` : "";
}

function fromDateInput(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? `${m[3]}-${m[2]}-${m[1]}` : "";
}

function fmtSync(ts: number | null): string {
  if (!ts) return "nunca";
  return new Date(ts).toLocaleString("pt-BR");
}

const NUM_KEYS = ["a1", "a2", "b", "c", "d1", "d2", "e", "eliminados"] as const;

function Stepper({ label, value, onChange, step = 1 }: {
  label: string; value: number; onChange: (v: number) => void; step?: number;
}) {
  const decimals = step < 1 ? 1 : 0;
  const fmt = (v: number) => (decimals ? v.toFixed(decimals).replace(".", ",") : String(v));
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
      <span className="muted small" style={{ minWidth: 86 }}>{label}</span>
      <button type="button" className="btn btn-sm btn-outline" onClick={() => onChange(Math.max(0, Math.round((value - step) * 10) / 10))}>−</button>
      <strong style={{ minWidth: 44, textAlign: "center" }}>{fmt(value)}</strong>
      <button type="button" className="btn btn-sm btn-outline" onClick={() => onChange(Math.round((value + step) * 10) / 10)}>+</button>
    </div>
  );
}

export default function AgentProductionEditor({ agentId, agentName, lastSyncTime }: Props) {
  const qc = useQueryClient();
  const { bairros } = useBairros();
  const [day, setDay] = useState(todayDashSP());
  const [forms, setForms] = useState<Record<string, VisitForm>>({});
  const [openedMs, setOpenedMs] = useState<Record<string, number | null>>({});
  const [loadedDay, setLoadedDay] = useState("");
  const [expandedTreat, setExpandedTreat] = useState<Record<string, boolean>>({});
  const [expandedNotes, setExpandedNotes] = useState<Record<string, boolean>>({});
  const [deleteMarked, setDeleteMarked] = useState<string[]>([]);
  const [forceIds, setForceIds] = useState<string[]>([]);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const containerRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [retryMonth, setRetryMonth] = useState<string | null>(null);
  const [lastEditAt, setLastEditAt] = useState<number | null>(null);

  const dayQuery = useQuery({
    queryKey: ["admin-day", agentId, day],
    enabled: !!agentId && /^\d{2}-\d{2}-\d{4}$/.test(day),
    queryFn: async () => {
      const hRes = await supabase.from("houses").select("*").eq("agent_id", agentId)
        .eq("data_text", day).is("deleted_at", null).order("list_order");
      const houses = must(hRes).map(toHouseDoc);
      const aRes = await supabase.from("day_activities").select("*").eq("agent_id", agentId)
        .eq("date_text", day).is("deleted_at", null).maybeSingle();
      if (aRes.error) throw new Error(aRes.error.message);
      const activity = aRes.data ? toActivityDoc(aRes.data) : null;
      return { houses, activity };
    },
    staleTime: 0,
  });

  // Carrega os formulários quando o dia chega (reseta edições ao trocar de dia).
  useEffect(() => {
    if (!dayQuery.data || loadedDay === day) return;
    const f: Record<string, VisitForm> = {};
    const o: Record<string, number | null> = {};
    for (const h of dayQuery.data.houses) {
      f[h.id] = houseToForm(h);
      o[h.id] = lastUpdatedMs(h.lastUpdated);
    }
    setForms(f);
    setOpenedMs(o);
    setLoadedDay(day);
    setDeleteMarked([]);
    setForceIds([]);
    setRowErrors({});
    setExpandedTreat({});
    setExpandedNotes({});
    // Entrada colapsada; auto-expande só linhas com pendência.
    const c: Record<string, boolean> = {};
    const dups = findDuplicateIds(dayQuery.data.houses.map((h) => ({ id: h.id, form: f[h.id] })));
    for (const h of dayQuery.data.houses) {
      c[h.id] = validateRowLabels(f[h.id], dups.has(h.id)).length === 0;
    }
    setCollapsed(c);
  }, [dayQuery.data, loadedDay, day]);

  const houses = useMemo(() => {
    const list = [...(dayQuery.data?.houses ?? [])];
    list.sort((a, b) => (a.listOrder ?? 0) - (b.listOrder ?? 0));
    return list;
  }, [dayQuery.data]);

  const activity = dayQuery.data?.activity ?? null;

  const rows = useMemo(
    () => houses.filter((h) => forms[h.id]).map((h) => ({ id: h.id, house: h, form: forms[h.id] })),
    [houses, forms],
  );

  const duplicateIds = useMemo(
    () => findDuplicateIds(rows.filter((r) => !deleteMarked.includes(r.id)).map((r) => ({ id: r.id, form: r.form }))),
    [rows, deleteMarked],
  );

  const labelsById = useMemo(() => {
    const out: Record<string, string[]> = {};
    for (const r of rows) {
      if (deleteMarked.includes(r.id)) continue;
      out[r.id] = validateRowLabels(r.form, duplicateIds.has(r.id));
    }
    return out;
  }, [rows, deleteMarked, duplicateIds]);

  const visibleForms = useMemo(
    () => rows.filter((r) => !deleteMarked.includes(r.id)).map((r) => r.form),
    [rows, deleteMarked],
  );
  const totals = useMemo(() => computeDayTotals(visibleForms), [visibleForms]);

  const dirtyIds = useMemo(
    () =>
      rows
        .filter((r) => !deleteMarked.includes(r.id))
        .filter((r) => {
          const orig = houseToForm(r.house);
          return JSON.stringify({ ...r.form }) !== JSON.stringify(orig);
        })
        .map((r) => r.id),
    [rows, deleteMarked],
  );

  const blockedIds = useMemo(
    () => Object.entries(labelsById).filter(([, l]) => l.length > 0).map(([id]) => id),
    [labelsById],
  );

  const converged = lastEditAt === null || (lastSyncTime !== null && lastSyncTime >= lastEditAt);

  function setField(id: string, patch: Partial<VisitForm>) {
    setForms((prev) => (prev[id] ? { ...prev, [id]: { ...prev[id], ...patch } } : prev));
  }

  function scrollToFirstError() {
    const id = blockedIds[0];
    if (!id) return;
    setCollapsed((p) => ({ ...p, [id]: false }));
    requestAnimationFrame(() => {
      const box = containerRef.current;
      const el = rowRefs.current[id];
      if (box && el) box.scrollTop = el.offsetTop - box.offsetTop - 70;
    });
  }

  async function handleSaveDay() {
    const { data: { user: actor } } = await supabase.auth.getUser();
    if (!actor?.id) {
      setError("Sessão expirada. Entre novamente.");
      return;
    }
    if (blockedIds.length > 0) {
      setError("Há linhas com pendências (etiquetas vermelhas). Corrija antes de salvar.");
      return;
    }
    if (dirtyIds.length === 0 && deleteMarked.length === 0) {
      setNotice("Nenhuma alteração a salvar.");
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    const perRowError: Record<string, string> = {};
    try {
      // 1) Re-lê as alteradas (concorrência otimista por linha; apagadas somem do mapa).
      const fresh = new Map<string, HouseDoc>();
      if (dirtyIds.length > 0) {
        const reRes = await supabase.from("houses").select("*").eq("agent_id", agentId).is("deleted_at", null).in("natural_key", dirtyIds);
        for (const r of must(reRes)) fresh.set(r.natural_key, toHouseDoc(r));
      }
      const missing = dirtyIds.filter((id) => !fresh.has(id));
      if (missing.length > 0) {
        for (const id of missing) perRowError[id] = "Não existe mais na nuvem.";
      }
      const changedElsewhere = dirtyIds.filter((id) => {
        const f = fresh.get(id);
        if (!f) return false;
        const cur = lastUpdatedMs((f as unknown as Record<string, unknown>).lastUpdated);
        return openedMs[id] !== null && openedMs[id] !== undefined && cur !== null && cur !== openedMs[id] && !forceIds.includes(id);
      });
      if (changedElsewhere.length > 0) {
        for (const id of changedElsewhere) {
          perRowError[id] = "Mudou desde a abertura (alguém editou). Marque forçar para sobrescrever.";
        }
        setRowErrors(perRowError);
        setError("Algumas linhas mudaram desde a abertura. Revise e use Forçar onde for o caso.");
        return;
      }

      // 2) Updates + soft-deletes SEQUENCIAIS (sem batch atômico no REST):
      // cada linha sucede/falha isolada; resumo e auditoria refletem o real.
      // Exclusão é soft (deleted_at) para convergir no pull delta do app;
      // bulk (wipe/restore/purge) continua hard + require_data_reset.
      const audits: Record<string, unknown>[] = [];
      for (const id of dirtyIds) {
        const f = fresh.get(id);
        if (!f) continue;
        const payload = buildUpdatePayload(f, rows.find((r) => r.id === id)!.form);
        if (Object.keys(payload).length > 2) {
          try {
            const upd = await supabase.from("houses").update(toSnakePayload(payload)).eq("agent_id", agentId).eq("natural_key", id).is("deleted_at", null).select("natural_key");
            if (upd.error) throw new Error(upd.error.message);
            if (!upd.data?.length) throw new Error("linha alterada/apagada por outro — recarregue");
            audits.push(buildAuditEntry({
              actorUid: actor.id, actorEmail: actor.email || "",
              action: "update", agentUid: agentId, agentName,
              houseId: id, date: day, monthYear: monthYearOf(day) || undefined,
              before: f, after: payload,
            }));
          } catch (e) {
            perRowError[id] = e instanceof Error ? e.message : String(e);
          }
        }
      }
      for (const id of deleteMarked) {
        const h = houses.find((x) => x.id === id);
        try {
          const del = await supabase.from("houses").update({ deleted_at: new Date().toISOString(), edited_by_admin: true }).eq("agent_id", agentId).eq("natural_key", id).is("deleted_at", null).select("natural_key");
          if (del.error) throw new Error(del.error.message);
          if (!del.data?.length) throw new Error("linha já alterada/apagada — recarregue");
          audits.push(buildAuditEntry({
            actorUid: actor.id, actorEmail: actor.email || "",
            action: "delete", agentUid: agentId, agentName,
            houseId: id, date: day, monthYear: monthYearOf(day) || undefined,
            before: h ? { ...h } : undefined,
          }));
        } catch (e) {
          perRowError[id] = e instanceof Error ? e.message : String(e);
        }
      }

      // 3) Resumo do mês (formato exato do app; nunca apaga sem regravar).
      const my = monthYearOf(day);
      if (my) {
        try {
          await refreshMonthSummary(agentId, my);
          setRetryMonth(null);
        } catch (e) {
          setRetryMonth(my);
          throw e;
        }
      }

      // 4) Audit (best-effort explícito: não desfaz o dado).
      try {
        const rows = audits.map((a) => {
          const r = a as Record<string, unknown>;
          return {
            actor_uid: r.actorUid as string,
            actor_email: r.actorEmail as string,
            action: r.action as string,
            payload: {
              agent_uid: r.agentUid, agent_name: r.agentName, house_id: r.houseId,
              date: r.date, month_year: r.monthYear, before: r.before, after: r.after,
            },
          };
        });
        if (rows.length) {
          const ins = await supabase.from("admin_audit").insert(rows);
          if (ins.error) throw new Error(ins.error.message);
        }
      } catch (e) {
        setError(`Dia salvo, mas auditoria falhou: ${e instanceof Error ? e.message : String(e)}. Avise o suporte.`);
      }

      setLastEditAt(Date.now());
      setForceIds([]);
      setDeleteMarked([]);
      const nUpd = audits.filter((a) => (a as { action: string }).action === "update").length;
      const nDel = audits.filter((a) => (a as { action: string }).action === "delete").length;
      if (Object.keys(perRowError).length > 0) {
        setRowErrors(perRowError);
        const nFail = Object.keys(perRowError).length;
        setError(`Dia parcialmente salvo (${nUpd + nDel} linha(s) ok, ${nFail} com falha). Revise as linhas marcadas.`);
        setNotice(null);
      } else if (!error) {
        setNotice(`Dia salvo: ${nUpd} editada(s), ${nDel} excluída(s). Resumo do mês recalculado.`);
      }
      await qc.invalidateQueries({ queryKey: ["admin-day", agentId] });
      setLoadedDay("");
    } catch (e) {
      if (!retryMonth) setError(e instanceof Error ? e.message : String(e));
      else setError(`Dia salvo, mas resumo de ${retryMonth} pode estar desatualizado. Use Recalcular.`);
      setRowErrors(perRowError);
    } finally {
      setBusy(false);
    }
  }

  const hasChanges = dirtyIds.length > 0 || deleteMarked.length > 0;
  const saveState = !hasChanges ? "idle" : blockedIds.length > 0 ? "errors" : "ready";

  return (
    <div style={{ borderTop: "1px dashed var(--border)", paddingTop: 10 }}>
      <h4 style={{ margin: "0 0 8px" }}>Produção do dia — edição</h4>

      <div className="admin-day-scroll" ref={containerRef}>
        <div className="admin-day-head">
          <div className="flex gap-2" style={{ alignItems: "center", flexWrap: "wrap" }}>
            <input
              type="date"
              className="input"
              style={{ maxWidth: 150 }}
              value={toDateInput(day)}
              max={toDateInput(todayDashSP())}
              onChange={(e) => setDay(fromDateInput(e.target.value) || day)}
            />
            <button type="button" className="btn-act btn-act-icon" onClick={() => { setLoadedDay(""); dayQuery.refetch(); }} disabled={dayQuery.isFetching} title="Recarregar">
              ⟳
            </button>
            {activity && (
              <span className={`tag ${activity.isClosed ? "tag-closed" : "tag-open"}`}>
                {activity.isClosed ? "🔒" : "🔓"}
              </span>
            )}
            <span className="muted small">{agentName}</span>
            <span style={{ flex: 1 }} />
            <button
              className={`btn-save-day ${saveState === "ready" ? "ready" : saveState === "errors" ? "errors" : ""}`}
              disabled={busy || saveState === "idle"}
              onClick={() => (saveState === "errors" ? scrollToFirstError() : handleSaveDay())}
              title={saveState === "errors" ? "Ir para a primeira pendência" : "Salvar alterações do dia"}
            >
              {busy ? "Salvando…" : saveState === "errors" ? `⚠ Corrigir ${blockedIds.length}` : saveState === "ready" ? `✓ Salvar (${dirtyIds.length + deleteMarked.length})` : "Salvar o dia"}
            </button>
          </div>

          <div className="admin-day-totals" style={{ marginTop: 8 }}>
            {[
              ["VISITAS", totals.visits], ["ABERTOS", totals.worked], ["FOCOS", totals.focuses],
              ["A1", totals.a1], ["A2", totals.a2], ["B", totals.b], ["C", totals.c],
              ["D1", totals.d1], ["D2", totals.d2], ["E", totals.e],
              ["ELIM.", totals.eliminados], ["LARV.", `${totals.larvicida}g`],
            ].map(([label, v]) => (
              <span key={label as string} className="tag" style={{ fontVariantNumeric: "tabular-nums" }}>
                {label}: <strong>{v}</strong>
              </span>
            ))}
          </div>

          <div className="flex gap-2" style={{ alignItems: "center", marginTop: 6, flexWrap: "wrap" }}>
            <span className="muted small">
              {rows.length} imóveis • {dirtyIds.length} editados • {blockedIds.length} pendências • sync {fmtSync(lastSyncTime)}
              {lastEditAt !== null && (converged ? " • no aparelho." : " • aguardando aparelho…")}
            </span>
            <span style={{ flex: 1 }} />
            {rows.length > 0 && (
              <>
                <button type="button" className="btn-act btn-act-icon" style={{ minHeight: 32 }} onClick={() => setCollapsed(Object.fromEntries(rows.map((r) => [r.id, false])))}>Expandir</button>
                <button type="button" className="btn-act btn-act-icon" style={{ minHeight: 32 }} onClick={() => setCollapsed(Object.fromEntries(rows.map((r) => [r.id, true])))}>Recolher</button>
              </>
            )}
          </div>

          {error && <p className="error-text" style={{ margin: "6px 0 0" }}>{error}</p>}
          {notice && <p className="muted small" style={{ margin: "6px 0 0", color: "var(--secondary)" }}>{notice}</p>}
        </div>

        <div className="admin-day-rows">
          {retryMonth && (
            <p className="error-text">
              Resumo de {retryMonth} pode estar desatualizado.{" "}
              <button
                className="btn btn-sm btn-outline"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    await refreshMonthSummary(agentId, retryMonth);
                    setRetryMonth(null);
                    setNotice("Resumo recalculado.");
                  } catch (e) {
                    setError(e instanceof Error ? e.message : String(e));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Recalcular agora
              </button>
            </p>
          )}

          {dayQuery.isLoading && <p className="muted">Carregando visitas…</p>}
          {!dayQuery.isLoading && rows.length === 0 && (
            <p className="muted small">Nenhuma visita neste dia.</p>
          )}

          {rows.map((r) => {
            const f = r.form;
            const labels = labelsById[r.id] ?? [];
            const marked = deleteMarked.includes(r.id);
            const dirty = dirtyIds.includes(r.id);
            const treat = treatmentSummary(f);
            const isTreated = treat !== "";
            const isCollapsed = collapsed[r.id] !== false;
            const sitLabel = SITUATION_OPTIONS.find((o) => o.value === f.situation)?.label.split(" ")[0] ?? f.situation;
            return (
              <div
                key={r.id}
                ref={(el) => { rowRefs.current[r.id] = el; }}
                className={`house-card${labels.length > 0 ? " invalid" : ""}${marked ? " todelete" : ""}`}
              >
                <div className="house-card-head" onClick={() => setCollapsed((p) => ({ ...p, [r.id]: !p[r.id] }))} role="button" tabIndex={0}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setCollapsed((p) => ({ ...p, [r.id]: !p[r.id] })); } }}>
                  <span className="seq-badge">{f.sequence || "•"}</span>
                  <span className="house-card-title">
                    <span className="name">{f.streetName.trim() || "Sem rua"}, {f.number.trim() || "SN"}</span>
                    <span className="meta">
                      Q {f.blockNumber.trim() || "—"}{f.blockSequence.trim() ? `/${f.blockSequence.trim()}` : ""} • {f.bairro.trim() || "sem bairro"}
                      {treat ? ` • ${treat}` : ""}
                    </span>
                  </span>
                  <span className="house-card-side">
                    {dirty && !marked && <span className="dot dot-dirty" title="Editado" />}
                    {labels.length > 0 && <span className="dot dot-error" title={`${labels.length} pendência(s)`} />}
                    {f.comFoco && <span className="sit sit-FOC">FOCO</span>}
                    <span className={`sit sit-${f.situation}`}>{sitLabel}</span>
                    {marked && <span className="tag tag-closed">excluir</span>}
                    <span className={`chev${isCollapsed ? "" : " open"}`}>▾</span>
                  </span>
                </div>

                {!isCollapsed && (
                  <div className="house-card-body">
                    <div style={{ display: "flex", gap: 4, flexWrap: "wrap", alignItems: "center" }}>
                      {r.house.editedByAdmin && <span className="tag tag-trat">Homologado</span>}
                      {labels.map((l) => <span key={l} className="tag tag-foco">{l}</span>)}
                      {rowErrors[r.id] && <span className="error-text">{rowErrors[r.id]}</span>}
                    </div>

                    {!marked && (
                      <>
                        <div className="grid" style={{ gridTemplateColumns: "1fr 1fr", gap: 8, marginTop: 8 }}>
                          <label className="form-label">Rua
                            <input className="input" value={f.streetName} onChange={(e) => setField(r.id, { streetName: e.target.value })} />
                          </label>
                          <label className="form-label">Número
                            <input className="input" value={f.number} onChange={(e) => setField(r.id, { number: e.target.value })} />
                          </label>
                          <label className="form-label">Bairro
                            <input className="input" list={`bairros-${agentId}`} value={f.bairro} onChange={(e) => setField(r.id, { bairro: e.target.value.toUpperCase() })} />
                          </label>
                          <label className="form-label">Quarteirão
                            <input className="input" value={f.blockNumber} onChange={(e) => setField(r.id, { blockNumber: e.target.value })} />
                          </label>
                          <label className="form-label">Seq. quarteirão
                            <input className="input" value={f.blockSequence} onChange={(e) => setField(r.id, { blockSequence: e.target.value })} />
                          </label>
                          <label className="form-label">Sequência
                            <input type="number" min={0} className="input" value={f.sequence} onChange={(e) => setField(r.id, { sequence: Number(e.target.value) })} />
                          </label>
                          <label className="form-label">Compl.
                            <input type="number" min={0} className="input" value={f.complement} onChange={(e) => setField(r.id, { complement: Number(e.target.value) })} />
                          </label>
                          <label className="form-label">Tipo
                            <select className="select" value={f.propertyType} onChange={(e) => setField(r.id, { propertyType: e.target.value })}>
                              {PROPERTY_OPTIONS.filter((o) => o.value !== "EMPTY").map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                            </select>
                          </label>
                          <label className="form-label">Situação
                            <select className="select" value={f.situation} onChange={(e) => setField(r.id, { situation: e.target.value })}>
                              {SITUATION_OPTIONS.filter((o) => o.value !== "EMPTY").map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                            </select>
                          </label>
                        </div>

                        <div className="btn-act-row r1">
                          <button type="button" className={`btn-act btn-act-primary${isTreated ? " active" : ""}`} onClick={() => setExpandedTreat((p) => ({ ...p, [r.id]: !p[r.id] }))}>
                            <span>{isTreated ? "◉" : "◎"}</span> {isTreated ? "Tratado" : "Tratamento"}
                          </button>
                          <button type="button" className="btn-act btn-act-secondary" onClick={() => setExpandedNotes((p) => ({ ...p, [r.id]: !p[r.id] }))}>
                            <span>✎</span> Notas
                          </button>
                        </div>

                        {expandedTreat[r.id] && (
                          <div style={{ display: "flex", flexDirection: "column", gap: 6, marginTop: 8 }}>
                            {(NUM_KEYS).map((k) => (
                              <Stepper key={k} label={k.toUpperCase()} value={f[k]} onChange={(v) => setField(r.id, { [k]: v } as Partial<VisitForm>)} />
                            ))}
                            <Stepper label="LARVICIDA (g)" value={f.larvicida} step={0.5} onChange={(v) => setField(r.id, { larvicida: v })} />
                            <label className="form-label" style={{ display: "flex", alignItems: "center", gap: 8 }}>
                              <input type="checkbox" checked={f.comFoco} onChange={(e) => setField(r.id, { comFoco: e.target.checked })} /> Com foco
                            </label>
                            {f.comFoco && <span className="muted small">Sem captura de GPS na web — o foco entra sem coordenadas.</span>}
                          </div>
                        )}
                        {expandedNotes[r.id] && (
                          <label className="form-label" style={{ marginTop: 8 }}>Observação
                            <input className="input" value={f.observation} onChange={(e) => setField(r.id, { observation: e.target.value })} />
                          </label>
                        )}

                        <div className="btn-act-row r2">
                          <button
                            type="button" className="btn-act btn-act-icon"
                            onClick={() => setForceIds((p) => (p.includes(r.id) ? p.filter((x) => x !== r.id) : [...p, r.id]))}
                            title="Sobrescreve mesmo se mudou desde a abertura"
                          >
                            {forceIds.includes(r.id) ? "✓ Forçar" : "Forçar"}
                          </button>
                          <button type="button" className="btn-act btn-act-destructive" disabled={busy} onClick={() => setDeleteMarked((p) => [...p, r.id])}>
                            <span>🗑</span><span>Excluir</span>
                          </button>
                        </div>
                      </>
                    )}

                    {marked && (
                      <div className="flex gap-2" style={{ marginTop: 8 }}>
                        <button type="button" className="btn btn-sm btn-ghost" disabled={busy} onClick={() => setDeleteMarked((p) => p.filter((x) => x !== r.id))}>
                          Desfazer exclusão
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {activity?.isClosed && (
        <p className="muted small">Dia fechado: as edições serão homologadas e o aparelho trava até desbloquear.</p>
      )}
      <datalist id={`bairros-${agentId}`}>{bairros.map((b) => <option key={b} value={b} />)}</datalist>
    </div>
  );
}
