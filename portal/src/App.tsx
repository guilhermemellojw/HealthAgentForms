import React, { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useStaffGate, loginWithGoogle, logout } from "./hooks/useAuth";
import SummaryPage from "./pages/SummaryPage";
import DocumentsPage from "./pages/DocumentsPage";
import { UsersPanel } from "./components/Admin/UsersPanel";
import { AgentsPanel } from "./components/Admin/AgentsPanel";

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { hasError: boolean; error: Error | null }> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false, error: null };
  }
  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error };
  }
  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("Portal ErrorBoundary:", error, info);
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="login-screen">
          <div className="login-card">
            <h1>Erro inesperado</h1>
            <p className="login-desc">{this.state.error?.message ?? "Tente recarregar a página."}</p>
            <button className="btn btn-primary btn-block" onClick={() => window.location.reload()}>
              Recarregar
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

type Tab = "resumo" | "documentos" | "admin";

function AppShell() {
  const gate = useStaffGate();
  const [tab, setTab] = useState<Tab>("resumo");
  const [mobileOpen, setMobileOpen] = useState(false);

  if (gate.status === "loading") {
    return <div className="loading-screen">Carregando…</div>;
  }
  if (gate.status === "signed-out") return <LoginScreen />;
  if (gate.status === "denied") return <DeniedScreen email={gate.user?.email} />;

  return (
    <div className="app">
      <header className="navbar">
        <div className="navbar-brand">
          <span className="navbar-logo">🦟</span>
          <span className="navbar-title">Eu ACE · Portal</span>
        </div>
        <button className="menu-btn" aria-label="Menu" aria-expanded={mobileOpen} onClick={() => setMobileOpen((o) => !o)}>
          ☰
        </button>
        <nav className={`nav-links ${mobileOpen ? "active" : ""}`} role="tablist">
          <button
            role="tab"
            aria-selected={tab === "resumo"}
            className={`nav-link ${tab === "resumo" ? "active" : ""}`}
            onClick={() => {
              setTab("resumo");
              setMobileOpen(false);
            }}
          >
            Resumo
          </button>
          <button
            role="tab"
            aria-selected={tab === "documentos"}
            className={`nav-link ${tab === "documentos" ? "active" : ""}`}
            onClick={() => {
              setTab("documentos");
              setMobileOpen(false);
            }}
          >
            Documentos
          </button>
          {gate.isAdmin && (
            <button
              role="tab"
              aria-selected={tab === "admin"}
              className={`nav-link ${tab === "admin" ? "active" : ""}`}
              onClick={() => {
                setTab("admin");
                setMobileOpen(false);
              }}
            >
              Admin
            </button>
          )}
        </nav>
        <div className="navbar-user">
          <span className="navbar-email">{gate.user?.email}</span>
          <button
            className="btn btn-outline"
            onClick={() => logout()}
          >
            Sair
          </button>
        </div>
      </header>
      <main className="main-content">
        {gate.isAdmin && tab === "admin" && (
          <div className="grid grid-2">
            <UsersPanel />
            <AgentsPanel />
          </div>
        )}
        {tab === "resumo" && <SummaryPage />}
        {tab === "documentos" && <DocumentsPage />}
      </main>
    </div>
  );
}

function LoginScreen() {
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="login-screen">
      <div className="login-card">
        <div className="login-logo">🦟</div>
        <h1>Eu ACE</h1>
        <p className="login-sub">Portal de Gestão</p>
        <p className="login-desc">
          Acesso restrito à equipe de supervisão e administração.
        </p>
        <button
          className="btn btn-primary btn-block"
          onClick={() => {
            setError(null);
            loginWithGoogle().catch((e) => setError(e.message));
          }}
        >
          Entrar com Google
        </button>
        {error && <p className="error-text">{error}</p>}
      </div>
    </div>
  );
}

function DeniedScreen({ email }: { email?: string | null }) {
  return (
    <div className="login-screen">
      <div className="login-card">
        <h1>Acceso negado</h1>
        <p className="login-desc">
          Sua conta ({email}) não está autorizada a acessar o portal.
          Contate o administrador.
        </p>
        <button className="btn btn-outline btn-block" onClick={() => logout()}>
          Sair
        </button>
      </div>
    </div>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary>
        <AppShell />
      </ErrorBoundary>
    </QueryClientProvider>
  );
}