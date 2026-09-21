import { useState } from "react";
import { useStaffGate } from "../../hooks/useAuth";
import { useAdminUsers } from "../../hooks/useAdminData";

export function UsersPanel() {
  const { isAdmin } = useStaffGate();
  // Reaproveita o listener já ativo no Admin (zero leitura extra, tempo real).
  const { users, loading } = useAdminUsers();
  const [search, setSearch] = useState("");

  if (!isAdmin) return null;

  if (loading) {
    return <p>Carregando...</p>;
  }

  const filteredUsers = users.filter(
    (u) =>
      (u.displayName || "").toLowerCase().includes(search.toLowerCase()) ||
      (u.email || "").toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div>
      <h3>Usuários {users.length}</h3>
      <p className="text-sm text-muted">Buscar: <input
        type="text"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="form-control w-100 rounded-md px-3 py-2 text-sm"
        placeholder="Buscar usuário..."
      /></p>
      {filteredUsers.length === 0 && <p className="text-muted small">Nenhum usuário encontrado.</p>}

      <table className="user-table">
        <thead>
          <tr>
            <th>Nome</th>
            <th>E-mail</th>
            <th>Função</th>
            <th>Status</th>
            <th style={{ width: 180 }}>Ações</th>
          </tr>
        </thead>
        <tbody>
          {filteredUsers.map((user) => {
            const role = user.role || "AGENT";
            const isAuth = user.isAuthorized ? "Ativo" : "Não autorizado";
            const roleClass =
              role === "ADMIN"
                ? "source-badge summary"
                : role === "SUPERVISOR"
                ? "source-badge raw"
                : "source-badge";

            return (
              <tr key={user.id}>
                <td>{user.displayName || "Sem nome"}</td>
                <td>{user.email || "—"}</td>
                <td>
                  <span className={`badge ${roleClass}`}>{role}</span>
                </td>
                <td>
                  <span className={isAuth === "Ativo" ? "badge-success" : "badge-danger"}>
                    {isAuth}
                  </span>
                </td>
                <td>
                  <div className="flex gap-2">
                    {user.role !== "ADMIN" && (
                      <button className="btn btn-sm btn-outline">Gerenciar</button>
                    )}
                    <button className="btn btn-sm btn-danger">Remover</button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}