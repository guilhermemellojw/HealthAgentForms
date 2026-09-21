import { useEffect, useId, useMemo } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { must, supabase, toAccessRequest, toAgentDoc, toUserDoc } from "../lib/supabase";
import type { AgentDoc, UserDoc } from "../lib/types";
import type { AccessRequest, UnifiedProfile } from "../lib/adminTypes";

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

/** Realtime (postgres_changes) -> invalida a query; substitui onSnapshot. */
function useRealtimeInvalidate(table: string, queryKey: string[], filter?: string) {
  const qc = useQueryClient();
  // Nome único por instância: o mesmo hook pode montar 2× (ex. useAgentNames
  // no dashboard + dentro de useUnifiedProfiles); canal duplicado com .on()
  // após subscribe() lança erro no realtime-js.
  const inst = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  const key = queryKey.join("|");
  useEffect(() => {
    const ch = supabase
      .channel(`rt-${queryKey[0]}-${inst}`)
      .on(
        "postgres_changes",
        { event: "*", schema: "public", table, ...(filter ? { filter } : {}) },
        () => qc.invalidateQueries({ queryKey }),
      )
      .subscribe();
    return () => {
      void supabase.removeChannel(ch);
    };
  }, [table, key]);
}

export function useAdminUsers() {
  useRealtimeInvalidate("profiles", ["admin-users"]);
  const q = useQuery({
    queryKey: ["admin-users"],
    queryFn: async () => {
      const res = await supabase.from("profiles").select("*").order("display_name");
      return must(res).map((r) => ({ ...toUserDoc(r), id: r.id }));
    },
  });
  return { users: (q.data ?? []) as (UserDoc & { id: string })[], loading: q.isLoading };
}

export function useAdminAgents() {
  useRealtimeInvalidate("agents", ["admin-agents"]);
  const q = useQuery({
    queryKey: ["admin-agents"],
    queryFn: async () => {
      const res = await supabase.from("agents").select("*").order("agent_name");
      return must(res).map((r) => toAgentDoc(r));
    },
  });
  return { agents: (q.data ?? []) as (AgentDoc & { id: string })[], loading: q.isLoading };
}

function upperSorted(list: unknown): string[] {
  if (!Array.isArray(list)) return [];
  return list.map((n) => String(n).trim().toUpperCase()).filter(Boolean).sort();
}

export function useAgentNames() {
  useRealtimeInvalidate("metadata", ["meta-agent-info"], "key=eq.agent_info");
  const q = useQuery({
    queryKey: ["meta-agent-info"],
    queryFn: async () => {
      const res = await supabase.from("metadata").select("value").eq("key", "agent_info").maybeSingle();
      if (res.error) throw new Error(res.error.message);
      return upperSorted((res.data?.value as { names?: unknown })?.names);
    },
  });
  return { names: q.data ?? [], loading: q.isLoading };
}

export function useBairros() {
  useRealtimeInvalidate("metadata", ["meta-locations"], "key=eq.locations");
  const q = useQuery({
    queryKey: ["meta-locations"],
    queryFn: async () => {
      const res = await supabase.from("metadata").select("value").eq("key", "locations").maybeSingle();
      if (res.error) throw new Error(res.error.message);
      return upperSorted((res.data?.value as { bairros?: unknown })?.bairros);
    },
  });
  return { bairros: q.data ?? [] };
}

export function useAccessRequests() {
  useRealtimeInvalidate("access_requests", ["access-requests"]);
  const q = useQuery({
    queryKey: ["access-requests"],
    queryFn: async () => {
      const res = await supabase.from("access_requests").select("*").eq("status", "PENDING");
      return must(res).map(toAccessRequest);
    },
  });
  return { requests: (q.data ?? []) as AccessRequest[] };
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
      const displayName = u.displayName || u.agentName || agent?.agentName || u.email || "Sem nome";
      result.push({
        uid,
        email: u.email || email,
        displayName,
        agentName: u.agentName || agent?.agentName || null,
        role: (u.role as UnifiedProfile["role"]) || "AGENT",
        isAuthorized: !!u.isAuthorized,
        isPreRegistered: !!isPre,
        photoUrl: agent?.photoUrl || null,
        lastSyncTime: agent?.lastSyncTime ?? null,
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
        lastSyncTime: ag.lastSyncTime ?? null,
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
  const { data, error } = await supabase.from("metadata").select("value").eq("key", "settings").maybeSingle();
  if (error) throw new Error(error.message);
  return (data?.value as Record<string, unknown>) ?? {};
}
