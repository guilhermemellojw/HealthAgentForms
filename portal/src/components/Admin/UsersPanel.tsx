import { useEffect, useState } from "react";
import { collection, getDocs, orderBy, query } from "firebase/firestore";
import { db } from "../../lib/firebase";
import { useStaffGate } from "../../hooks/useAuth";
import type { UserDoc } from "../../lib/types";

export function UsersPanel() {
  const { isAdmin } = useStaffGate();
  const [users, setUsers] = useState<(UserDoc & { id: string })[]>([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isAdmin) {
      setLoading(false);
      return;
    }

    const loadUsers = async () => {
      setLoading(true);
      try {
        const usersRef = collection(db, "users");
        const snap = await getDocs(query(usersRef, orderBy("displayName")));
        const userList = snap.docs.map((doc) => ({
          id: doc.id,
          ...(doc.data() as Omit<UserDoc, "uid">),
        })) as (UserDoc & { id: string })[];
        setUsers(userList);
      } catch (err) {
        console.error("Error loading users:", err);
      } finally {
        setLoading(false);
      }
    };

    loadUsers();
  }, [isAdmin]);

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