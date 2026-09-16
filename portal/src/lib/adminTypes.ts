export interface UnifiedProfile {
  uid: string | null;
  email: string;
  displayName: string;
  agentName: string | null;
  role: "ADMIN" | "SUPERVISOR" | "AGENT";
  isAuthorized: boolean;
  isPreRegistered: boolean;
  photoUrl?: string | null;
  lastSyncTime?: number | { seconds?: number } | null;
  agentId: string | null;
  bairros?: string[];
}

export interface AccessRequest {
  id: string;
  email: string;
  displayName?: string;
  requestedName?: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  createdAt?: number | { seconds?: number };
}

export interface BairroDoc {
  bairros: string[];
}

export interface AgentInfoDoc {
  names: string[];
}
