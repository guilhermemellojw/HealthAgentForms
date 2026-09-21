import { useEffect, useState } from "react";
import { supabase, toUserDoc, BOOTSTRAP_ADMINS, type ProfileRow, type User } from "../lib/supabase";
import type { UserDoc } from "../lib/types";

export interface StaffInfo {
  user: User | null;
  userDoc: UserDoc | null;
  status: "loading" | "signed-out" | "denied" | "allowed";
  isAdmin: boolean;
}

export function useStaffGate(): StaffInfo {
  const [user, setUser] = useState<User | null>(null);
  const [userDoc, setUserDoc] = useState<UserDoc | null>(null);
  const [authReady, setAuthReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    supabase.auth.getSession().then(({ data }) => {
      if (cancelled) return;
      setUser(data.session?.user ?? null);
      setAuthReady(true);
    });
    const { data: sub } = supabase.auth.onAuthStateChange((_event, session) => {
      if (cancelled) return;
      setUser(session?.user ?? null);
      setAuthReady(true);
    });
    return () => {
      cancelled = true;
      sub.subscription.unsubscribe();
    };
  }, []);

  useEffect(() => {
    if (!user) {
      setUserDoc(null);
      return;
    }
    let cancelled = false;
    const load = async () => {
      // Primeiro login pós-migração: o auth.uid() novo assume a linha seed (match por e-mail).
      await supabase.rpc("claim_migrated_profile").then(
        () => undefined,
        () => undefined, // best-effort: perfil stub do trigger cobre o resto
      );
      if (cancelled) return;
      const { data } = await supabase.from("profiles").select("*").eq("id", user.id).maybeSingle();
      if (!cancelled) setUserDoc(data ? toUserDoc(data as ProfileRow) : null);
    };
    void load();
    // Perfil ao vivo (role/autorização alterados pelo admin refletem sem reload).
    const ch = supabase
      .channel(`profile-${user.id}`)
      .on(
        "postgres_changes",
        { event: "*", schema: "public", table: "profiles", filter: `id=eq.${user.id}` },
        (payload) => {
          const row = (payload.new ?? payload.old) as ProfileRow | Record<string, never>;
          if (row && "id" in row) setUserDoc(toUserDoc(row as ProfileRow));
        },
      )
      .subscribe();
    return () => {
      cancelled = true;
      void supabase.removeChannel(ch);
    };
  }, [user?.id]);

  if (!authReady) return { user: null, userDoc: null, status: "loading", isAdmin: false };
  if (!user) return { user: null, userDoc: null, status: "signed-out", isAdmin: false };

  const emailLower = user.email?.toLowerCase() ?? "";
  const bootstrapLower = BOOTSTRAP_ADMINS.map((e) => e.toLowerCase());
  const isAdmin =
    (emailLower && bootstrapLower.includes(emailLower)) || userDoc?.role === "ADMIN";
  const isStaff =
    isAdmin || (userDoc?.isAuthorized === true && userDoc?.role === "SUPERVISOR");

  return {
    user,
    userDoc,
    status: isStaff ? "allowed" : "denied",
    isAdmin,
  };
}

export async function loginWithGoogle(): Promise<void> {
  // Retorna para o path atual (não só origin): no Pages o app vive em subpath
  // (/HealthAgentForms) e voltar para a raiz cairia em 404 sem trocar o code.
  const returnTo = window.location.origin + window.location.pathname;
  const { error } = await supabase.auth.signInWithOAuth({
    provider: "google",
    options: { redirectTo: returnTo, queryParams: { prompt: "select_account" } },
  });
  if (error) throw error;
}

export function logout(): Promise<{ error: unknown }> {
  return supabase.auth.signOut() as Promise<{ error: unknown }>;
}
