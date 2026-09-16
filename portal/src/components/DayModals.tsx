import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { collection, getDocs, query, where } from "firebase/firestore";
import { db } from "../lib/firebase";
import { downloadBoletim } from "../lib/pdf/boletim";
import type { DayActivityDoc, HouseDoc } from "../lib/types";

function tsOf(v: number | { seconds?: number } | undefined): number {
  if (typeof v === "number") return v;
  return v?.seconds ?? 0;
}

function useDayHouses(agentId: string | null, date: string | null) {
  return useQuery({
    queryKey: ["day-houses", agentId, date],
    enabled: !!agentId && !!date,
    queryFn: async () => {
      const ref = collection(db, "agents", agentId as string, "houses");
      const snap = await getDocs(query(ref, where("data", "==", date as string)));
      const docs = snap.docs.map((d) => ({ id: d.id, ...d.data() })) as HouseDoc[];
      docs.sort(
        (a, b) =>
          (a.listOrder ?? 0) - (b.listOrder ?? 0) || tsOf(a.createdAt) - tsOf(b.createdAt)
      );
      return docs;
    },
    staleTime: 30_000,
  });
}

export function DayDetailModal({ agent, date, onClose }: { agent: { id: string; agentName?: string; email?: string }; date: string; onClose: () => void }) {
  const { data: houses, isLoading } = useDayHouses(agent.id, date);
  const [downloading, setDownloading] = useState(false);
  const [pdfError, setPdfError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>Produção: {date}</h3>
            <p className="muted small">{agent.agentName || agent.email}</p>
          </div>
          <button className="btn btn-ghost" onClick={onClose}>✕</button>
        </div>
        <div className="modal-actions">
          <button
            className="btn btn-primary"
            disabled={!houses?.length || downloading}
            onClick={async () => {
              if (!houses?.length) return;
              setDownloading(true);
              setPdfError(null);
              try {
                await new Promise((r) => setTimeout(r, 50));
                await downloadBoletim(houses, date, agent.agentName || "");
              } catch (e) {
                console.error("Falha ao gerar boletim:", e);
                setPdfError(e instanceof Error ? e.message : String(e));
              } finally {
                setDownloading(false);
              }
            }}
          >
            {downloading ? "Gerando…" : "Baixar Boletim (PDF)"}
          </button>
          <span className="muted small">{houses?.length ?? 0} imóveis</span>
          {pdfError && <span className="error-text">{pdfError}</span>}
        </div>
        <div className="day-list">
          {isLoading && <p className="muted">Carregando…</p>}
          {!isLoading && houses?.length === 0 && <p className="muted">Nenhuma visita encontrada para este dia.</p>}
          {houses?.map((h) => (
            <div key={h.id} className="house-row">
              <div className="house-main">
                <span className="strong">{h.streetName || "Sem Rua"}, {h.number || "SN"}</span>
                <span className="house-meta">
                  Seq: {h.sequence} · Q: {h.blockNumber}{h.blockSequence ? `/${h.blockSequence}` : ""} · {h.visitSegment ? `Seg: ${h.visitSegment}` : ""}
                </span>
              </div>
              <div className="house-tags">
                <span className={`tag sit-${(h.situation || "NONE").toLowerCase()}`}>{h.situation || "Aberto"}</span>
                <span className="tag">{h.propertyType || "—"}</span>
                {h.comFoco && <span className="tag tag-foco">Com Foco</span>}
                {isTreated(h) && <span className="tag tag-trat">Tratado</span>}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function isTreated(h: HouseDoc): boolean {
  return (
    (h.a1 || 0) + (h.a2 || 0) + (h.b || 0) + (h.c || 0) +
      (h.d1 || 0) + (h.d2 || 0) + (h.e || 0) + (h.eliminados || 0) >
      0 ||
    (h.larvicida || 0) > 0 ||
    !!h.comFoco
  );
}

export function DaysModal({
  agent,
  days,
  onSelectDay,
  onClose,
}: {
  agent: { id: string; agentName?: string; email?: string };
  days: DayActivityDoc[];
  onSelectDay: (date: string) => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const sorted = [...days].sort((a, b) => (b.date || "").localeCompare(a.date || ""));

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>Dias de atividade</h3>
            <p className="muted small">{agent.agentName || agent.email}</p>
          </div>
          <button className="btn btn-ghost" onClick={onClose}>✕</button>
        </div>
        <div className="day-list">
          {sorted.length === 0 && <p className="muted">Nenhum dia registrado no período.</p>}
          {sorted.map((d) => (
            <button key={d.id} className="day-row" onClick={() => onSelectDay(d.date || d.id)}>
              <span className="strong">{(d.date || d.id).split("-").reverse().join("/")}</span>
              <span className={`tag ${d.isClosed ? "tag-closed" : "tag-open"}`}>{d.isClosed ? "Fechado" : "Aberto"}</span>
              <span className="muted small">Ver produção →</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}