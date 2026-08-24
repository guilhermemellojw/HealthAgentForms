import { initializeApp } from "firebase/app";
import { getAuth, GoogleAuthProvider } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY ?? "AIzaSyDNhuYlD1YKOWFCLFLbQR7aypQCTFY64HA",
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN ?? "healthagentforms.firebaseapp.com",
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID ?? "healthagentforms",
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET ?? "healthagentforms.firebasestorage.app",
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID ?? "558585725380",
  appId: import.meta.env.VITE_FIREBASE_APP_ID ?? "1:558585725380:web:78546123bcdef",
};

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
export const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

export const BOOTSTRAP_ADMINS: string[] =
  (import.meta.env.VITE_BOOTSTRAP_ADMINS as string | undefined)
    ?.split(",")
    .map((s: string) => s.trim())
    .filter(Boolean) ?? ["gmellobkp@gmail.com"];