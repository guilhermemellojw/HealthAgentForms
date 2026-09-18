import { useEffect, useState } from "react";
import { must, supabase } from "../../lib/supabase";

// Replicates Kotlin StringExtensions.normalize(): trim, "/"->"-", "."->"-", collapse spaces/dashes, UPPERCASE
function normalizeKey(value: unknown): string {
  return String(value ?? "")
    .trim()
    .replace(/\//g, "-")
    .replace(/\./g, "-")
    .replace(/\s+/g, " ")
    .replace(/-+/g, "-")
    .toUpperCase();
}

// Replicates House.generateNaturalKey() (House.kt:65): uuid if present, else
// AGENTUID_AGENTNAME_DATE_ADDRSIGNATURE_VISITSEGMENT uppercased.
function houseDocId(h: Record<string, unknown>, uid: string, agentNameUpper: string): string {
  const uuid = typeof h.uuid === "string" ? h.uuid.trim() : "";
  if (uuid) return uuid;
  const addr = (h.address ?? {}) as Record<string, unknown>;
  const sequence = Number(addr.sequence ?? 0) || 0;
  const complement = Number(addr.complement ?? 0) || 0;
  const addressSignature = [
    normalizeKey(addr.blockNumber),
    normalizeKey(addr.blockSequence),
    normalizeKey(addr.streetName),
    normalizeKey(addr.number),
    sequence,
    complement,
    normalizeKey(addr.bairro),
  ].join("_");
  const dataDash = String(h.data ?? "").trim().replace(/\//g, "-");
  const visitSegment = Number(h.visitSegment ?? 0) || 0;
  return [uid, normalizeKey(h.agentName || agentNameUpper), dataDash, addressSignature, visitSegment].join("_").toUpperCase();
}

interface TimelineItem {
  id: string;
  storagePath: string;
  timestamp: number;
  houseCount?: number;
  activityCount?: number;
}

const str = (v: unknown): string | null => {
  if (v === null || v === undefined) return null;
  const s = String(v);
  return s === "" ? null : s;
};
const num = (v: unknown, dflt: number | null = null): number | null => {
  if (v === null || v === undefined || v === "") return dflt;
  const n = Number(v);
  return Number.isFinite(n) ? n : dflt;
};

/** Backup do app (formato domain, address aninhado) -> linha houses. */
function backupHouseToRow(h: Record<string, unknown>, agentId: string, agentNameUpper: string) {
  const addr = (h.address ?? {}) as Record<string, unknown>;
  const pick = (...vals: unknown[]): string | null => {
    for (const v of vals) {
      const s = str(v);
      if (s !== null) return s;
    }
    return null;
  };
  return {
    agent_id: agentId,
    natural_key: houseDocId(h, agentId, agentNameUpper),
    data_text: String(h.data ?? "").trim().replace(/\//g, "-"),
    street_name: pick(h.streetName, addr.streetName),
    number: pick(h.number, addr.number),
    block_number: pick(h.blockNumber, addr.blockNumber),
    block_sequence: pick(h.blockSequence, addr.blockSequence),
    sequence: num(h.sequence ?? addr.sequence),
    complement: num(h.complement ?? addr.complement),
    visit_segment: num(h.visitSegment),
    list_order: num(h.listOrder),
    situation: pick(h.situation),
    property_type: pick(h.propertyType),
    com_foco: typeof h.comFoco === "boolean" ? h.comFoco : null,
    a1: num(h.a1, 0) ?? 0, a2: num(h.a2, 0) ?? 0, b: num(h.b, 0) ?? 0, c: num(h.c, 0) ?? 0,
    d1: num(h.d1, 0) ?? 0, d2: num(h.d2, 0) ?? 0, e: num(h.e, 0) ?? 0,
    eliminados: num(h.eliminados, 0) ?? 0, larvicida: num(h.larvicida, 0) ?? 0,
    latitude: num(h.latitude), longitude: num(h.longitude),
    observation: pick(h.observation),
    municipio: pick(h.municipio), bairro: pick(h.bairro, addr.bairro),
    categoria: pick(h.categoria), zona: pick(h.zona),
    tipo: h.tipo == null ? null : String(h.tipo),
    atividade: h.atividade == null ? null : String(h.atividade),
    ciclo: pick(h.ciclo),
    agent_name: agentNameUpper,
    agent_uid: agentId,
    client_uuid: str(h.uuid),
    edited_by_admin: true,
  };
}

export function AdminTimeline({ uid, agentName, onClose }: { uid: string; agentName: string; onClose: () => void }) {
  const [items, setItems] = useState<TimelineItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [restoring, setRestoring] = useState<string | null>(null);
  const [cleaning, setCleaning] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await supabase.from("backups").select("*").eq("agent_id", uid).order("ts", { ascending: false });
      const rows = must(res);
      setItems(
        rows.map((r) => ({
          id: String(r.ts),
          storagePath: r.storage_path as string,
          timestamp: Number(r.ts) || 0,
          houseCount: r.house_count != null ? Number(r.house_count) : undefined,
          activityCount: r.activity_count != null ? Number(r.activity_count) : undefined,
        })),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      console.error("AdminTimeline load error:", e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [uid]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const handleRestore = async (item: TimelineItem) => {
    if (!confirm(`Restaurar backup ${new Date(item.timestamp).toLocaleString("pt-BR")}?\nIsso substituirá os dados atuais da nuvem. O agente receberá reset.`)) return;
    setRestoring(item.id);
    try {
      const rel = item.storagePath.replace(/^backups\//, "");
      const { data: blob, error: dlError } = await supabase.storage.from("backups").download(rel);
      if (dlError || !blob) throw new Error(dlError?.message || "Falha ao baixar backup");
      const json = await blob.text();
      // Encrypted backups (EncryptedFile AES256 from app) are binary — detect before parsing
      if (!json.trimStart().startsWith("{")) {
        throw new Error("Backup criptografado pelo app (AES) não pode ser restaurado no portal. Use o app para restaurar este backup.");
      }
      let data: { houses?: unknown[]; dayActivities?: unknown[] };
      try {
        data = JSON.parse(json);
      } catch {
        throw new Error("Backup corrompido ou formato inválido");
      }
      const houses = (data.houses as Array<Record<string, unknown>>) || [];
      const activities = (data.dayActivities as Array<Record<string, unknown>>) || [];
      const agentNameUpper = agentName.toUpperCase();
      // For safety, do not wipe before success parse
      for (const table of ["houses", "day_activities", "monthly_summaries"] as const) {
        const del = await supabase.from(table).delete().eq("agent_id", uid);
        if (del.error) throw new Error(del.error.message);
      }
      // Write houses using Android-compatible natural keys (uuid or generated)
      const DATE_RE = /^[0-9]{2}-[0-9]{2}-[0-9]{4}$/;
      const rows = houses.map((h) => backupHouseToRow(h, uid, agentNameUpper));
      const bad = rows.filter((r) => !DATE_RE.test(r.data_text)).length;
      const good = rows.filter((r) => DATE_RE.test(r.data_text));
      for (let i = 0; i < good.length; i += 500) {
        const ins = await supabase.from("houses").insert(good.slice(i, i + 500));
        if (ins.error) throw new Error(ins.error.message);
      }
      // Activities use date as id (matches Android dateKey)
      const actRows = activities.map((a, idx) => ({
        agent_id: uid,
        date_text: String(a.date ?? "").trim().replace(/\//g, "-") || `act_${Date.now()}_${idx}`,
        status: str(a.status),
        is_closed: typeof a.isClosed === "boolean" ? a.isClosed : null,
        is_manual_unlock: typeof a.isManualUnlock === "boolean" ? a.isManualUnlock : null,
        agent_name: agentNameUpper,
        agent_uid: uid,
        edited_by_admin: true,
      }));
      for (let i = 0; i < actRows.length; i += 500) {
        const ins = await supabase.from("day_activities").insert(actRows.slice(i, i + 500));
        if (ins.error) throw new Error(ins.error.message);
      }
      // mark requireDataReset for app to pull
      await supabase.from("profiles").update({ require_data_reset: true }).eq("id", uid);
      showToast(`Restaurado: ${good.length} imóveis, ${actRows.length} dias${bad ? ` (${bad} ignorados por data inválida)` : ""}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    } finally {
      setRestoring(null);
    }
  };

  const handleSurgical = async () => {
    if (!confirm("Limpeza cirúrgica: removerá registros vazios (sem rua/número/quarteirão) que não estão em dias fechados. Continuar?")) return;
    setCleaning(true);
    try {
      const [hRes, aRes] = await Promise.all([
        supabase.from("houses").select("natural_key,street_name,number,block_number,data_text").eq("agent_id", uid).is("deleted_at", null),
        supabase.from("day_activities").select("date_text,is_closed,is_manual_unlock").eq("agent_id", uid).is("deleted_at", null),
      ]);
      const houses = must(hRes);
      const acts = must(aRes);
      const closedDates = new Set(
        acts
          .filter((a) => a.is_closed && !a.is_manual_unlock)
          .map((a) => (a.date_text || "").replace(/\//g, "-"))
          .filter(Boolean),
      );
      const broken = houses.filter((h) => {
        const isEmpty = !h.street_name?.trim() && !h.number?.trim() && !h.block_number?.trim();
        if (!isEmpty) return false;
        const dateDash = (h.data_text || "").replace(/\//g, "-");
        return !closedDates.has(dateDash);
      });
      if (broken.length === 0) {
        showToast("Nenhum registro quebrado encontrado");
        return;
      }
      for (let i = 0; i < broken.length; i += 200) {
        const keys = broken.slice(i, i + 200).map((h) => h.natural_key);
        const del = await supabase.from("houses").delete().eq("agent_id", uid).in("natural_key", keys);
        if (del.error) throw new Error(del.error.message);
      }
      showToast(`Limpeza: ${broken.length} removidos`);
      await load();
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e));
    } finally {
      setCleaning(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose} style={{ zIndex: 1100 }}>
      <div className="modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 640, width: "95%" }}>
        <div className="modal-header">
          <div>
            <h3>Timeline — {agentName}</h3>
            <p className="muted small">{uid} • {items.length} backups</p>
          </div>
          <button className="btn btn-ghost" onClick={onClose}>✕</button>
        </div>
        <div style={{ padding: "0.75rem 1.5rem", display: "flex", gap: 8 }}>
          <button className="btn btn-sm btn-outline" onClick={load} disabled={loading}>🔄 Recarregar</button>
          <button className="btn btn-sm btn-outline" onClick={handleSurgical} disabled={cleaning || loading}>
            {cleaning ? "Limpando…" : "🧹 Limpeza cirúrgica"}
          </button>
        </div>
        <div className="day-list" style={{ maxHeight: "60vh", padding: "0 1.5rem 1rem" }}>
          {loading && <p className="muted">Carregando…</p>}
          {error && <p className="error-text">{error}</p>}
          {!loading && !error && items.length === 0 && (
            <div style={{ textAlign: "center", padding: "2rem 0" }}>
              <div style={{ fontSize: "2rem" }}>🕘</div>
              <p className="muted">Nenhum backup encontrado.</p>
              <p className="muted small">Backups são criados automaticamente pelo app (TimelineBackupWorker).</p>
            </div>
          )}
          {items.map((it) => (
            <div key={it.id} className="house-row" style={{ flexDirection: "row", alignItems: "center" }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600, display: "flex", alignItems: "center", gap: 8 }}>
                  <span>☁️</span> {it.timestamp ? new Date(it.timestamp).toLocaleString("pt-BR") : it.id}
                </div>
                <div className="muted small">
                  {it.houseCount !== undefined ? `${it.houseCount} imóveis` : ""} {it.activityCount !== undefined ? `• ${it.activityCount} dias` : ""} • {it.storagePath}
                </div>
              </div>
              <button className="btn btn-sm btn-primary" onClick={() => handleRestore(it)} disabled={!!restoring}>
                {restoring === it.id ? "Restaurando…" : "Restaurar"}
              </button>
            </div>
          ))}
        </div>
        {toast && <div className="toast success" style={{ margin: "0 1.5rem 1rem" }}>{toast}</div>}
      </div>
    </div>
  );
}
