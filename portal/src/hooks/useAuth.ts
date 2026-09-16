import { useEffect, useState } from "react";
import { onAuthStateChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { doc, onSnapshot } from "firebase/firestore";
import { auth, db, BOOTSTRAP_ADMINS, googleProvider } from "../lib/firebase";
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
    const unsub = onAuthStateChanged(auth, (u) => {
      setUser(u);
      setAuthReady(true);
    });
    return unsub;
  }, []);

  useEffect(() => {
    if (!user) {
      setUserDoc(null);
      return;
    }
    const ref = doc(db, "users", user.uid);
    const unsub = onSnapshot(
      ref,
      (snap) => {
        setUserDoc(snap.exists() ? ({ uid: user.uid, ...snap.data() } as UserDoc) : null);
      },
      (err) => {
        console.error("useStaffGate onSnapshot error:", err);
        setUserDoc(null);
      },
    );
    return unsub;
  }, [user]);

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

export function loginWithGoogle(): Promise<User> {
  return signInWithPopup(auth, googleProvider).then((r) => r.user);
}

export function logout(): Promise<void> {
  return signOut(auth);
}