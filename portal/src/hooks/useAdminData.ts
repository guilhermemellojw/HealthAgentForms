import { useEffect, useMemo, useState } from "react";
import { collection, doc, getDoc, onSnapshot, orderBy, query, where } from "firebase/firestore";
import { db } from "../lib/firebase";
import type { AgentDoc, UserDoc } from "../lib/types";
import type { AccessRequest, UnifiedProfile } from "../lib/adminTypes";

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

export function useAdminUsers() {
  const [users, setUsers] = useState<(UserDoc & { id: string })[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const q = query(collection(db, "users"), orderBy("displayName"));
    const unsub = onSnapshot(
      q,
      (snap) => {
        setUsers(snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<UserDoc, "uid">) } as UserDoc & { id: string })));
        setLoading(false);
      },
      () => setLoading(false),
    );
    return unsub;
  }, []);
  return { users, loading };
}

export function useAdminAgents() {
  const [agents, setAgents] = useState<(AgentDoc & { id: string })[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const q = query(collection(db, "agents"), orderBy("agentName"));
    const unsub = onSnapshot(
      q,
      (snap) => {
        setAgents(snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<AgentDoc, "id">) } as AgentDoc & { id: string })));
        setLoading(false);
      },
      () => setLoading(false),
    );
    return unsub;
  }, []);
  return { agents, loading };
}

export function useAgentNames() {
  const [names, setNames] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const ref = doc(db, "metadata", "agent_info");
    const unsub = onSnapshot(
      ref,
      (snap) => {
        if (snap.exists()) {
          const data = snap.data() as { names?: string[] };
          const list = (data.names || []).map((n) => n.trim().toUpperCase()).filter(Boolean).sort();
          setNames(list.length ? list : []);
        } else {
          setNames([]);
        }
        setLoading(false);
      },
      () => setLoading(false),
    );
    return unsub;
  }, []);
  // fallback fetch once if empty
  useEffect(() => {
    if (names.length === 0) {
      getDoc(doc(db, "metadata", "agent_info")).then((snap) => {
        if (snap.exists()) {
          const data = snap.data() as { names?: string[] };
          const list = (data.names || []).map((n) => n.trim().toUpperCase()).filter(Boolean).sort();
          if (list.length) setNames(list);
        }
      });
    }
  }, [names.length]);
  return { names, loading };
}

export function useBairros() {
  const [bairros, setBairros] = useState<string[]>([]);
  useEffect(() => {
    const ref = doc(db, "metadata", "locations");
    const unsub = onSnapshot(ref, (snap) => {
      if (snap.exists()) {
        const data = snap.data() as { bairros?: string[] };
        const list = (data.bairros || []).map((b) => b.trim().toUpperCase()).filter(Boolean).sort();
        setBairros(list);
      }
    });
    return unsub;
  }, []);
  return { bairros };
}

export function useAccessRequests() {
  const [requests, setRequests] = useState<AccessRequest[]>([]);
  useEffect(() => {
    const q = query(collection(db, "access_requests"), where("status", "==", "PENDING"));
    const unsub = onSnapshot(
      q,
      (snap) => setRequests(snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<AccessRequest, "id">) }))),
      () => setRequests([]),
    );
    return unsub;
  }, []);
  return { requests };
}

export function useUnifiedProfiles(search: string): { profiles: UnifiedProfile[]; loading: boolean } {
  const { users, loading: loadingUsers } = useAdminUsers();
  const { agents, loading: loadingAgents } = useAdminAgents();
  const { names, loading: loadingNames } = useAgentNames();

  const profiles = useMemo(() => {
    const byUid = new Map<string, AgentDoc & { id: string }>();
    const byEmail = new Map<string, AgentDoc & { id: string }>();
    for (const ag of agents) {
      byUid.set(ag.id, ag);
      if (ag.email) byEmail.set(normalizeEmail(ag.email), ag);
    }

    const result: UnifiedProfile[] = [];
    const seenEmails = new Set<string>();
    const seenUids = new Set<string>();

    for (const u of users) {
      const email = normalizeEmail(u.email || u.uid || "");
      if (!email) continue;
      const isPre = u.uid?.startsWith("pre_") || u.isPreRegistered || u.id.startsWith("pre_");
      let agent: (AgentDoc & { id: string }) | undefined;
      if (u.uid && byUid.has(u.uid)) agent = byUid.get(u.uid);
      else if (email && byEmail.has(email)) agent = byEmail.get(email);
      const uid = u.uid || u.id;
      const displayName = u.displayName || (u as unknown as { agentName?: string }).agentName || agent?.agentName || u.email || "Sem nome";
      result.push({
        uid,
        email: u.email || email,
        displayName,
        agentName: (u as unknown as { agentName?: string }).agentName || agent?.agentName || null,
        role: (u.role as UnifiedProfile["role"]) || "AGENT",
        isAuthorized: !!u.isAuthorized,
        isPreRegistered: !!isPre,
        photoUrl: agent?.photoUrl || null,
        lastSyncTime: agent?.lastSyncTime || null,
        agentId: agent?.id || null,
      });
      seenEmails.add(email);
      if (uid) seenUids.add(uid);
      if (agent?.id) seenUids.add(agent.id);
    }

    for (const ag of agents) {
      const email = ag.email ? normalizeEmail(ag.email) : "";
      if ((email && seenEmails.has(email)) || seenUids.has(ag.id)) continue;
      result.push({
        uid: null,
        email: ag.email || ag.id,
        displayName: ag.agentName || ag.email || ag.id,
        agentName: ag.agentName || null,
        role: "AGENT",
        isAuthorized: false,
        isPreRegistered: !!ag.isPreRegistered,
        photoUrl: ag.photoUrl,
        lastSyncTime: ag.lastSyncTime,
        agentId: ag.id,
      });
      if (email) seenEmails.add(email);
    }

    for (const name of names) {
      const upper = name.trim().toUpperCase();
      if (result.some((p) => (p.agentName || "").toUpperCase() === upper)) continue;
      result.push({
        uid: null,
        email: upper,
        displayName: upper,
        agentName: upper,
        role: "AGENT",
        isAuthorized: false,
        isPreRegistered: true,
        photoUrl: null,
        lastSyncTime: null,
        agentId: null,
      });
    }

    const term = search.trim().toLowerCase();
    if (!term) return result.sort((a, b) => a.displayName.localeCompare(b.displayName));
    return result
      .filter((p) => p.displayName.toLowerCase().includes(term) || p.email.toLowerCase().includes(term) || (p.agentName || "").toLowerCase().includes(term))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }, [users, agents, names, search]);

  const loading = loadingUsers || loadingAgents || loadingNames;
  return { profiles, loading };
}

export async function fetchSystemSettings(): Promise<Record<string, unknown>> {
  const snap = await getDoc(doc(db, "metadata", "settings"));
  return snap.exists() ? (snap.data() as Record<string, unknown>) : {};
}
