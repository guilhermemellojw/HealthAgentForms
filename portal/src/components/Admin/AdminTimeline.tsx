import { useEffect, useState } from "react";
import { collection, deleteField, doc, getDocs, query, orderBy, updateDoc, writeBatch } from "firebase/firestore";
import { getDownloadURL, ref } from "firebase/storage";
import { db, storage } from "../../lib/firebase";

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
      console.log("AdminTimeline load:", { uid });
      // Android parity: query Firestore only (never list Storage directly)
      const backupsRef = collection(db, "agents", uid, "backups");
      const q = query(backupsRef, orderBy("timestamp", "desc"));
      const snap = await getDocs(q);
      console.log("Firestore backup count:", snap.docs.length);

      let mapped: TimelineItem[] = [];
      snap.docs.forEach((d) => {
        const data = d.data();
        const storagePath = data.storagePath as string;
        const ts = data.timestamp ? Number(data.timestamp) : 0;
        mapped.push({
          id: d.id,
          storagePath,
          timestamp: ts,
          houseCount: data.houseCount != null ? Number(data.houseCount) : undefined,
          activityCount: data.activityCount != null ? Number(data.activityCount) : undefined,
        });
      });
      mapped.sort((a, b) => b.timestamp - a.timestamp);
      setItems(mapped);
      console.log("Using Firestore backups:", mapped.length);
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
      const url = await getDownloadURL(ref(storage, item.storagePath));
      const res = await fetch(url);
      const json = await res.text();
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
      const existingHouses = await getDocs(collection(db, "agents", uid, "houses"));
      const existingActs = await getDocs(collection(db, "agents", uid, "day_activities"));
      const existingSummaries = await getDocs(collection(db, "agents", uid, "monthly_summaries"));
      const allExisting = [...existingHouses.docs, ...existingActs.docs, ...existingSummaries.docs];
      for (let i = 0; i < allExisting.length; i += 400) {
        const chunk = allExisting.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((d) => batch.delete(d.ref));
        await batch.commit();
      }
      // Clear sync tombstones so restored data is not re-deleted by the app on next pull,
      // and refresh lastSyncTime (parity with Android wipeMetadata)
      try {
        await updateDoc(doc(db, "agents", uid), {
          deleted_house_ids: deleteField(),
          deleted_activity_dates: deleteField(),
          lastSyncError: deleteField(),
          lastSyncTime: Date.now(),
        });
      } catch {
        // agents doc may not exist yet
      }
      // Write houses using Android-compatible doc IDs (uuid or natural key)
      for (let i = 0; i < houses.length; i += 400) {
        const chunk = houses.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((h) => {
          const id = houseDocId(h, uid, agentNameUpper);
          batch.set(doc(db, "agents", uid, "houses", id), { ...h, id, agentUid: uid, agentName: agentNameUpper });
        });
        await batch.commit();
      }
      // Activities use date as doc ID (matches Android dateKey)
      for (let i = 0; i < activities.length; i += 400) {
        const chunk = activities.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((a, idx) => {
          const dateKey = String(a.date ?? "").trim().replace(/\//g, "-");
          const id = dateKey || `act_${Date.now()}_${i + idx}`;
          batch.set(doc(db, "agents", uid, "day_activities", id), { ...a, agentUid: uid, agentName: agentNameUpper });
        });
        await batch.commit();
      }
      // mark requireDataReset for app to pull
      try {
        await updateDoc(doc(db, "users", uid), { requireDataReset: true });
      } catch {
        // ignore
      }
      showToast(`Restaurado: ${houses.length} imóveis, ${activities.length} dias`);
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
      const housesSnap = await getDocs(collection(db, "agents", uid, "houses"));
      const actsSnap = await getDocs(collection(db, "agents", uid, "day_activities"));
      const closedDates = new Set(
        actsSnap.docs
          .map((d) => d.data() as { date?: string; isClosed?: boolean; isManualUnlock?: boolean })
          .filter((a) => a.isClosed && !a.isManualUnlock)
          .map((a) => (a.date || "").replace(/\//g, "-"))
          .filter(Boolean),
      );
      const broken = housesSnap.docs.filter((d) => {
        const h = d.data() as { streetName?: string; number?: string; blockNumber?: string; data?: string };
        const isEmpty = !h.streetName?.trim() && !h.number?.trim() && !h.blockNumber?.trim();
        if (!isEmpty) return false;
        const dateDash = (h.data || "").replace(/\//g, "-");
        return !closedDates.has(dateDash);
      });
      if (broken.length === 0) {
        showToast("Nenhum registro quebrado encontrado");
        return;
      }
      for (let i = 0; i < broken.length; i += 400) {
        const chunk = broken.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((d) => batch.delete(d.ref));
        await batch.commit();
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
