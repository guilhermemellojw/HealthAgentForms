import { useEffect, useState } from "react";
import { collection, doc, getDocs, writeBatch } from "firebase/firestore";
import { getDownloadURL, getMetadata, listAll, ref } from "firebase/storage";
import { db, storage } from "../../lib/firebase";

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
      const listRef = ref(storage, `backups/${uid}`);
      const list = await listAll(listRef);
      const mapped: TimelineItem[] = [];
      for (const item of list.items) {
        try {
          const meta = await getMetadata(item);
          const ts = meta.timeCreated ? new Date(meta.timeCreated).getTime() : (meta.customMetadata?.timestamp ? Number(meta.customMetadata.timestamp) : 0);
          mapped.push({
            id: item.name,
            storagePath: item.fullPath,
            timestamp: ts || 0,
            houseCount: meta.customMetadata?.houseCount ? Number(meta.customMetadata.houseCount) : undefined,
            activityCount: meta.customMetadata?.activityCount ? Number(meta.customMetadata.activityCount) : undefined,
          });
        } catch {
          mapped.push({ id: item.name, storagePath: item.fullPath, timestamp: 0 });
        }
      }
      // also try prefixes if any
      for (const prefix of list.prefixes) {
        const sub = await listAll(prefix);
        for (const item of sub.items) {
          try {
            const meta = await getMetadata(item);
            const ts = meta.timeCreated ? new Date(meta.timeCreated).getTime() : 0;
            mapped.push({ id: `${prefix.name}/${item.name}`, storagePath: item.fullPath, timestamp: ts });
          } catch {
            mapped.push({ id: item.name, storagePath: item.fullPath, timestamp: 0 });
          }
        }
      }
      mapped.sort((a, b) => b.timestamp - a.timestamp);
      setItems(mapped);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
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
      let data: { houses?: unknown[]; dayActivities?: unknown[]; housesCount?: number };
      try {
        data = JSON.parse(json);
      } catch {
        throw new Error("Backup corrompido ou formato inválido");
      }
      const houses = (data.houses as unknown[]) || [];
      const activities = (data.dayActivities as unknown[]) || [];
      // push to Firestore: clear existing and write new (chunk 400)
      // For safety, do not wipe before success parse
      const houseIds = houses.length;
      const actIds = activities.length;
      // use writeBatch to set houses
      // For simplicity, delete existing houses/activities first (like isFullWipe)
      const existingHouses = await getDocs(collection(db, "agents", uid, "houses"));
      const existingActs = await getDocs(collection(db, "agents", uid, "day_activities"));
      // delete existing in batches
      const allExisting = [...existingHouses.docs, ...existingActs.docs];
      for (let i = 0; i < allExisting.length; i += 400) {
        const chunk = allExisting.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((d) => batch.delete(d.ref));
        await batch.commit();
      }
      // write new
      const houseDocs = houses as Array<Record<string, unknown> & { id?: string }>;
      for (let i = 0; i < houseDocs.length; i += 400) {
        const chunk = houseDocs.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((h, idx) => {
          const id = (h.id as string) || `restored_${Date.now()}_${i + idx}`;
          batch.set(doc(db, "agents", uid, "houses", id), { ...h, agentUid: uid, agentName: agentName.toUpperCase() });
        });
        await batch.commit();
      }
      const actDocs = activities as Array<Record<string, unknown> & { id?: string; date?: string }>;
      for (let i = 0; i < actDocs.length; i += 400) {
        const chunk = actDocs.slice(i, i + 400);
        const batch = writeBatch(db);
        chunk.forEach((a, idx) => {
          const id = (a.id as string) || (a.date as string) || `act_${Date.now()}_${i + idx}`;
          batch.set(doc(db, "agents", uid, "day_activities", id), { ...a, agentUid: uid, agentName: agentName.toUpperCase() });
        });
        await batch.commit();
      }
      // mark requireDataReset for app to pull
      try {
        const { setDoc } = await import("firebase/firestore");
        await setDoc(doc(db, "users", uid), { requireDataReset: true }, { merge: true });
      } catch {
        // ignore
      }
      showToast(`Restaurado: ${houseIds} imóveis, ${actIds} dias`);
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
