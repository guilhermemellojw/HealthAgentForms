// Firebase Configuration
const firebaseConfig = {
    apiKey: "AIzaSyDNhuYlD1YKOWFCLFLbQR7aypQCTFY64HA",
    authDomain: "healthagentforms.firebaseapp.com",
    projectId: "healthagentforms",
    storageBucket: "healthagentforms.firebasestorage.app",
    messagingSenderId: "558585725380",
    appId: "1:558585725380:web:78546123bcdef"
};

// Initialize Firebase
firebase.initializeApp(firebaseConfig);
const auth = firebase.auth();
const db = firebase.firestore();

// Constants
const BOOTSTRAP_ADMINS = ["guigomelo9@gmail.com"];
const KML_URL = "https://earth.google.com/earth/d/1gHbsFGvKEBwI9N0ZAUAgiFpIjpdwFRWQ";
const CORS_PROXY = "https://corsproxy.io/?";

const MONTHS = ["Ano Todo", "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez"];
// Source of truth matching Android Enums.kt (Enum Names are stored in Firestore)
const SITUATION = {
    NONE: 'NONE', EMPTY: 'EMPTY',
    CLOSED: 'CLOSED', VACANT: 'VACANT', REFUSED: 'REFUSED', ABANDONED: 'ABANDONED',
    // Legacy support for codes
    L_F: 'F', L_V: 'V', L_REC: 'REC', L_A: 'A', L_NONE: '—'
};

const PROPERTY_TYPE = {
    RES: 'RES', COM: 'COM', TB: 'TB', PE: 'PE', OUT: 'OUT', EMPTY: 'EMPTY'
};

// UI Elements
const loginModal = document.getElementById("login-modal");
const loginBtn = document.getElementById("login-nav-btn");
const heroLoginBtn = document.getElementById("hero-login-btn");
const googleLoginBtn = document.getElementById("google-login-btn");
const closeBtn = document.querySelector(".close");
const authError = document.getElementById("auth-error");

// Navigation elements
const hero = document.querySelector('.hero');
const features = document.getElementById('features');
const dashboardView = document.getElementById('dashboard-view');
const dashboardContent = document.getElementById('dashboard-content');

// Global State
let leafletMap = null;
let currentKmlLayer = null;
let userProfile = null;

let selectedYear = new Date().getFullYear();
let selectedMonth = new Date().getMonth(); // 0-based
let selectedWeekIndex = -1; // -1 for "Mês Todo"
let agentsData = [];
let aggregateSummary = null;
let isLoadingData = false;

// Modal Controls
const openLogin = () => loginModal.style.display = "block";
if (loginBtn) loginBtn.onclick = openLogin;
if (heroLoginBtn) heroLoginBtn.onclick = openLogin;
if (closeBtn) closeBtn.onclick = () => loginModal.style.display = "none";
window.onclick = (event) => {
    if (event.target == loginModal) loginModal.style.display = "none";
}

// Google Auth Logic
googleLoginBtn.onclick = async () => {
    const provider = new firebase.auth.GoogleAuthProvider();
    authError.style.color = "var(--text-secondary)";
    authError.textContent = "Autenticando...";
    
    try {
        const result = await auth.signInWithPopup(provider);
        const user = result.user;
        
        authError.textContent = "Verificando permissões...";
        userProfile = await fetchUserProfile(user);
        
        if (userProfile.isAuthorized || userProfile.role === 'ADMIN') {
            showDashboard(user);
        } else {
            authError.style.color = "#ff5252";
            authError.textContent = "Acesso Negado: Usuário não autorizado por um administrador.";
            auth.signOut();
        }
    } catch (error) {
        console.error("Firebase Auth Error:", error.code, error.message);
        authError.style.color = "#ff5252";
        authError.textContent = translateError(error.code);
    }
};

async function fetchUserProfile(user) {
    const email = user.email.toLowerCase();
    if (BOOTSTRAP_ADMINS.includes(email)) {
        return { uid: user.uid, email: email, role: "ADMIN", isAuthorized: true, displayName: user.displayName };
    }
    try {
        const doc = await db.collection('users').doc(user.uid).get();
        if (doc.exists) return { uid: user.uid, ...doc.data() };
        return { uid: user.uid, email: email, role: "AGENT", isAuthorized: false, displayName: user.displayName };
    } catch (err) {
        return { role: "AGENT", isAuthorized: false };
    }
}

function translateError(code) {
    switch (code) {
        case 'auth/operation-not-supported-in-this-environment': return 'O Google Login não funciona em arquivos locais (file://).';
        case 'auth/popup-closed-by-user': return 'A janela de login foi fechada.';
        default: return 'Falha na autenticação. Verifique as configurações do Firebase.';
    }
}

function showDashboard(user) {
    loginModal.style.display = "none";
    hero.classList.add('hidden');
    features.classList.add('hidden');
    dashboardView.classList.remove('hidden');
    
    loginBtn.textContent = "Sair";
    loginBtn.onclick = () => location.reload();
    
    refreshDashboardData();
    renderView('summary');
    
    document.querySelectorAll('.menu-btn').forEach(btn => {
        btn.onclick = () => {
            document.querySelectorAll('.menu-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            renderView(btn.dataset.view);
        }
    });
}

// --- Data Quality & Cleanup Tools (Global Scope) ---

/**
 * Normalizes a string by removing accents/diacritics. 
 */
function normalizeName(text) {
    if (text === null || text === undefined) return "";
    return text.toString().normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .trim()
        .replace(/\//g, "-")
        .replace(/\./g, "-")
        .replace(/\s+/g, " ")
        .replace(/-+/g, "-")
        .toUpperCase();
}

/**
 * Identifies and removes duplicate records for an agent.
 * Safety: Added 'sequence' and 'blockSequence' to the key to prevent 
 * collisions on properties without house numbers (S/N).
 */
let isCleaning = false;
async function cleanupAgentDuplicates(agentUid) {
    if (isCleaning) return;
    
    const confirmMsg = "🛡️ AVISO DE SEGURANÇA:\n\n" +
        "Esta ferramenta removerá apenas cópias IDÊNTICAS de visitas (mesma data, quarteirão e sequência).\n" +
        "Se houver perda de dados imprevista, use a 'Restauração Completa' no App Android do agente para recuperar.\n\n" +
        "Deseja prosseguir com a limpeza segura?";
        
    if (!confirm(confirmMsg)) return;
    
    console.log(`%c[SAFE-CLEANUP] Starting for UID: ${agentUid}`, "background: #4caf50; color: white; padding: 2px 5px; font-weight: bold;");
    
    const btn = document.querySelector(`.agent-card[data-uid="${agentUid}"] .heal-btn`);
    if (btn) {
        btn.disabled = true;
        btn.textContent = "⌛ Analisando...";
        btn.style.background = "#ff9800";
    }
    
    isCleaning = true;
    
    try {
        const agentRef = db.collection('agents').doc(agentUid);
        
        // 1. CLEANUP HOUSES
        const housesSnapshot = await agentRef.collection('houses').get();
        console.log(`-> Analisando ${housesSnapshot.size} documentos de imóveis.`);
        
        const houseGroups = {};
        housesSnapshot.docs.forEach(doc => {
            const d = doc.data();
            const date = normalizeName(d.data || d.date);
            const street = normalizeName(d.streetName);
            const block = normalizeName(d.blockNumber);
            const bSeq = normalizeName(d.blockSequence || "0");
            const num = normalizeName(d.number);
            const comp = normalizeName(d.complement || "0");
            const seq = d.sequence || 0;
            const segment = d.visitSegment || 0;
            
            // ROCK-SOLID KEY: Matches Android's internal identity logic
            // Including 'seq' (sequence) is CRITICAL to distinguish S/N houses in the same block.
            const key = `H_${date}_${block}_${bSeq}_${street}_${num}_${comp}_${seq}_${segment}`;
            
            if (!houseGroups[key]) houseGroups[key] = [];
            
            let ts = 0;
            if (d.lastUpdated && d.lastUpdated.seconds) ts = d.lastUpdated.seconds;
            else if (typeof d.lastUpdated === 'number') ts = d.lastUpdated;
            else if (d.lastSyncTime) ts = d.lastSyncTime;

            houseGroups[key].push({ id: doc.id, ref: doc.ref, ts, street, num, seq });
        });

        const toDelete = [];
        Object.entries(houseGroups).forEach(([key, group]) => {
            if (group.length > 1) {
                // Sort by lastUpdated descending (keep newest)
                group.sort((a, b) => b.ts - a.ts);
                const kept = group[0];
                console.log(`%c[DUPLICATA] Mantendo: ${kept.street} ${kept.num} (Seq: ${kept.seq})`, "color: #4caf50;");
                
                group.slice(1).forEach(extra => {
                    console.log(`%c   -> Removendo duplicata ID: ${extra.id}`, "color: #f44336;");
                    toDelete.push(extra.ref);
                });
            }
        });

        // 2. CLEANUP ACTIVITIES (Date-based)
        const activitySnapshot = await agentRef.collection('day_activities').get();
        const activityGroups = {};
        activitySnapshot.docs.forEach(doc => {
            const d = doc.data();
            const date = normalizeName(d.date || d.data);
            if (!activityGroups[date]) activityGroups[date] = [];
            let ts = d.lastUpdated?.seconds || d.lastUpdated || d.lastSyncTime || 0;
            activityGroups[date].push({ id: doc.id, ref: doc.ref, ts });
        });

        Object.values(activityGroups).forEach(group => {
            if (group.length > 1) {
                group.sort((a, b) => b.ts - a.ts);
                group.slice(1).forEach(extra => toDelete.push(extra.ref));
            }
        });

        if (toDelete.length > 0) {
            console.log(`%c[AÇÃO] Deletando ${toDelete.length} registros redundantes...`, "color: #ff9800; font-weight: bold;");
            for (let i = 0; i < toDelete.length; i += 400) {
                const batch = db.batch();
                toDelete.slice(i, i + 400).forEach(ref => batch.delete(ref));
                await batch.commit();
            }
            alert(`Limpeza segura concluída! Foram removidas ${toDelete.length} duplicatas.\nOs imóveis agora estão organizados corretamente.`);
        } else {
            alert("Nenhuma duplicata encontrada com os novos critérios de segurança.");
        }
        
        await refreshDashboardData();
    } catch (err) {
        console.error("!!! Erro Crítico na Limpeza:", err);
        alert("Erro: " + err.message);
    } finally {
        isCleaning = false;
        if (btn) {
            btn.disabled = false;
            btn.textContent = "🛡️ Curar";
            btn.style.background = "rgba(33, 150, 243, 0.1)";
        }
    }
}

// --- Remote Management Functions (Admin Only) ---

async function toggleDayLock(agentUid, date, isLocked) {
    const admin = BOOTSTRAP_ADMINS.includes(currentUserEmail);
    if (!admin) {
        alert("🛡️ Acesso Negado: Apenas supervisores administradores podem alterar bloqueios.");
        return;
    }

    const newStatus = !isLocked;
    const dateKey = date.replace(/\//g, "-");
    const confirmMsg = newStatus 
        ? `Deseja BLOQUEAR o dia ${date}? O agente não poderá mais editar visitas localmente.`
        : `Deseja DESBLOQUEAR o dia ${date}? Isso permitirá que o agente corrija/delete visitas no app.`;

    if (!confirm(confirmMsg)) return;

    try {
        const agentDoc = await db.collection('agents').doc(agentUid).get();
        const agentName = agentDoc.get('agentName') || '';

        await db.collection('agents').doc(agentUid).collection('day_activities').doc(dateKey).set({
            date: dateKey,
            agentName: agentName,
            agentUid: agentUid,
            isClosed: newStatus,
            isManualUnlock: !newStatus,
            lastUpdated: firebase.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
        
        console.log(`[MANAGEMENT] Day ${dateKey} ${newStatus ? 'Locked' : 'Unlocked'} for UID: ${agentUid}`);
        await refreshDashboardData();
    } catch (err) {
        alert("Erro: " + err.message);
    }
}

async function deleteHouseRecord(agentUid, houseId, street, num) {
    const admin = BOOTSTRAP_ADMINS.includes(currentUserEmail);
    if (!admin) return;

    if (!confirm(`⚠️ TEM CERTEZA?\n\nDeseja excluir permanentemente a visita em:\n${street}, ${num}?\n\nEsta ação não pode ser desfeita.`)) return;

    try {
        const agentRef = db.collection('agents').doc(agentUid);
        const batch = db.batch();

        // 1. Delete document
        batch.delete(agentRef.collection('houses').doc(houseId));

        // 2. Add to tombstones (Matches Android Sync Logic)
        batch.update(agentRef, {
            deleted_house_ids: firebase.firestore.FieldValue.arrayUnion(houseId)
        });

        await batch.commit();
        console.log(`[MANAGEMENT] Surgical deletion for House ID: ${houseId} successful.`);
        
        // Hide modal if everything was deleted, or just refresh
        await refreshDashboardData();
        const modal = document.getElementById("day-details-modal");
        if (modal) modal.style.display = "none";
        
        alert("Registro removido com sucesso!");
    } catch (err) {
        alert("Erro ao excluir: " + err.message);
    }
}

function createDayDetailsModal() {
    let modal = document.getElementById("day-details-modal");
    if (!modal) {
        console.log("[UI] Creating day-details-modal...");
        modal = document.createElement("div");
        modal.id = "day-details-modal";
        document.body.appendChild(modal);
    }
    return modal;
}

function viewDayDetails(uid, date) {
    const agent = agentsData.find(a => a.uid === uid);
    if (!agent) return;
    
    console.log(`[UI] Opening Day Inspector for ${uid} on ${date}`);
    const modal = createDayDetailsModal();
    
    const isAdmin = BOOTSTRAP_ADMINS.includes(currentUserEmail);
    const dayKey = date.replace(/\//g, "-");
    
    // Filter houses precisely for this day
    const dayHouses = agent.houses.filter(h => {
        const hDate = (h.data || h.date || "").replace(/\//g, "-");
        return hDate === dayKey;
    });

    console.log(`[UI] Found ${dayHouses.length} houses for day ${dayKey}`);

    modal.innerHTML = `
        <div class="modal-content glass" style="max-width: 600px; padding: 25px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
                <div>
                    <h3 style="margin-bottom: 4px;">Produção: ${date}</h3>
                    <p style="font-size: 0.8rem; color: var(--text-secondary);">${agent.agentName}</p>
                </div>
                <span class="close-btn" onclick="document.getElementById('day-details-modal').style.display='none'">&times;</span>
            </div>
            
            <div class="house-list-inspector" style="max-height: 400px; overflow-y: auto;">
                ${dayHouses.map(h => `
                    <div class="house-item-mini" style="display: flex; justify-content: space-between; align-items: center; padding: 12px 15px; background: rgba(255,255,255,0.03); border-radius: 8px; margin-bottom: 8px; border: 1px solid rgba(255,255,255,0.05);">
                        <div style="flex: 1;">
                            <div style="font-weight: 600; font-size: 0.9rem;">${h.streetName || "Sem Rua"}, ${h.number || "SN"}</div>
                            <div style="font-size: 0.75rem; color: var(--text-secondary); display: flex; gap: 10px; margin-top: 4px;">
                                <span>Seq: ${h.sequence}</span>
                                <span style="color: ${h.situation === SITUATION.CLOSED ? 'var(--accent-red)' : 'var(--text-secondary)'}">${h.situation || "Aberto"}</span>
                                <span>${h.propertyType || "RES"}</span>
                            </div>
                        </div>
                        ${isAdmin ? `
                            <button class="delete-mini-btn" onclick="deleteHouseRecord('${uid}', '${h.id}', '${h.streetName}', '${h.number}')" title="Excluir Visita">
                                🗑️
                            </button>
                        ` : ''}
                    </div>
                `).join('')}
                ${dayHouses.length === 0 ? '<p style="text-align: center; color: var(--text-secondary);">Nenhuma visita encontrada para este dia.</p>' : ''}
            </div>
        </div>
    `;

    modal.style.display = "flex";
}

// Expose to window for console access
window.cleanupAgentDuplicates = cleanupAgentDuplicates;
window.normalizeName = normalizeName;
window.toggleDayLock = toggleDayLock;
window.viewDayDetails = viewDayDetails;
window.deleteHouseRecord = deleteHouseRecord;

// --- Existing Data Fetching Logic ---

async function refreshDashboardData() {
    isLoadingData = true;
    renderView(document.querySelector('.menu-btn.active')?.dataset.view || 'summary');

    try {
        const agentsSnapshot = await db.collection('agents').get();
        const agentPromises = agentsSnapshot.docs.map(async (agentDoc) => {
            const uid = agentDoc.id;
            const data = agentDoc.data();
            
            const housesSnapshot = await agentDoc.ref.collection('houses').get();
            const activitiesSnapshot = await agentDoc.ref.collection('day_activities').get();

            const deletedHouseIds = data.deleted_house_ids || [];
            const deletedActivityDates = data.deleted_activity_dates || [];

            const houses = housesSnapshot.docs.map(d => ({ id: d.id, ...d.data() }))
                .filter(h => !deletedHouseIds.includes(h.id));

            const activities = activitiesSnapshot.docs.map(d => ({ id: d.id, ...d.data() }))
                .filter(a => {
                    const dateKey = (a.date || a.data || "").replace(/\//g, "-");
                    return !deletedActivityDates.includes(dateKey);
                });

            // Robust read-only deduplication
            const houseGroups = {};
            let duplicateCount = 0;
            
            houses.forEach(h => {
                const date = normalizeName(h.data || h.date);
                const street = normalizeName(h.streetName);
                const num = normalizeName(h.number);
                const bNum = normalizeName(h.blockNumber);
                const bSeq = normalizeName(h.blockSequence || "0");
                const seq = h.sequence || 0;
                const comp = h.complement || 0;
                const seg = h.visitSegment || 0;
                
                // Super-stable key
                const key = `DEDUP|${date}|${bNum}|${bSeq}|${street}|${num}|${seq}|${comp}|${seg}`;
                
                const ts = h.lastUpdated?.seconds || h.lastUpdated || h.lastSyncTime || 0;
                if (!houseGroups[key]) {
                    houseGroups[key] = { ...h, ts };
                } else {
                    duplicateCount++;
                    if (ts > houseGroups[key].ts) {
                        houseGroups[key] = { ...h, ts };
                    }
                }
            });
            const validHouses = Object.values(houseGroups);
            if (duplicateCount > 0) {
                console.log(`%c[DEDUP] Ocultadas ${duplicateCount} duplicatas em memória para ${data.agentName}`, "color: #ff9800;");
            }

            // Calculate lastSyncTime based on latest valid house update
            let lastSync = 0;
            validHouses.forEach(h => {
                if (h.ts > lastSync) lastSync = h.ts;
            });

            return {
                uid: uid,
                ...data,
                agentName: data.agentName || "SEM NOME",
                photoUrl: data.photoUrl,
                lastSyncTime: lastSync,
                houses: validHouses,
                activities: activities
            };
        });

        agentsData = await Promise.all(agentPromises);
        calculateAggregateStats();
    } catch (err) {
        console.error("Error fetching agents data:", err);
    } finally {
        isLoadingData = false;
        renderView(document.querySelector('.menu-btn.active')?.dataset.view || 'summary');
    }
}

function calculateAggregateStats() {
    let totals = { worked: 0, focuses: 0, treated: 0, closed: 0, abandoned: 0, refused: 0, vacant: 0, visits: 0 };
    let agentDetails = agentsData.map(agent => {
        const filteredHouses = filterByPeriod(agent.houses, 'data');
        const filteredActivities = filterByPeriod(agent.activities, 'date');
        
        const stats = {
            worked: filteredHouses.filter(h => ![SITUATION.VACANT, SITUATION.CLOSED, SITUATION.REFUSED, SITUATION.ABANDONED, SITUATION.L_V, SITUATION.L_F, SITUATION.L_REC, SITUATION.L_A].includes(h.situation)).length,
            vacant: filteredHouses.filter(h => h.situation === SITUATION.VACANT || h.situation === SITUATION.L_V).length,
            closed: filteredHouses.filter(h => h.situation === SITUATION.CLOSED || h.situation === SITUATION.L_F).length,
            abandoned: filteredHouses.filter(h => h.situation === SITUATION.ABANDONED || h.situation === SITUATION.L_A).length,
            refused: filteredHouses.filter(h => h.situation === SITUATION.REFUSED || h.situation === SITUATION.L_REC).length,
            focuses: filteredHouses.filter(h => h.comFoco).length,
            treated: filteredHouses.filter(h => isTreated(h)).length,
            visits: filteredHouses.length,
            res: filteredHouses.filter(h => h.propertyType === PROPERTY_TYPE.RES || h.propertyType === PROPERTY_TYPE.EMPTY).length,
            com: filteredHouses.filter(h => h.propertyType === PROPERTY_TYPE.COM).length,
            tb: filteredHouses.filter(h => h.propertyType === PROPERTY_TYPE.TB).length,
            pe: filteredHouses.filter(h => h.propertyType === PROPERTY_TYPE.PE).length,
            out: filteredHouses.filter(h => h.propertyType === PROPERTY_TYPE.OUT).length,
            activeDays: filteredActivities.length
        };

        totals.worked += stats.worked;
        totals.vacant += stats.vacant;
        totals.closed += stats.closed;
        totals.abandoned += stats.abandoned;
        totals.refused += stats.refused;
        totals.focuses += stats.focuses;
        totals.treated += stats.treated;
        totals.visits += stats.visits;

        // Recently played activities for "Últimas Produções" (Max 5)
        const sortedActivities = [...filteredActivities].sort((a, b) => {
            const da = parseDate((a.date || a.data).replace(/\//g, "-"));
            const db = parseDate((b.date || b.data).replace(/\//g, "-"));
            return db - da;
        }).slice(0, 5).map(act => {
            const date = act.date || act.data;
            const houseCount = agent.houses.filter(h => h.data === date && ![SITUATION.VACANT, SITUATION.CLOSED, SITUATION.REFUSED, SITUATION.ABANDONED, SITUATION.L_V, SITUATION.L_F, SITUATION.L_REC, SITUATION.L_A].includes(h.situation)).length;
            return { date, workedCount: houseCount, isLocked: act.isLocked || act.finalized };
        });

        return { ...agent, stats, recentActivities: sortedActivities };
    });

    aggregateSummary = { totals, agents: agentDetails };
}

function isTreated(house) {
    return (house.a1 || 0) + (house.a2 || 0) + (house.b || 0) + (house.c || 0) + 
           (house.d1 || 0) + (house.d2 || 0) + (house.e || 0) + (house.eliminados || 0) > 0 || 
           (house.larvicida || 0) > 0 || house.comFoco;
}

function filterByPeriod(items, dateField) {
    const today = new Date();
    today.setHours(23, 59, 59, 999);

    // CRITICAL: Block future data. Prevent anomalous agent device clocks from impacting stats.
    let currentItems = items.filter(item => {
        const dateStr = (item[dateField] || "").replace(/\//g, "-");
        const dateObj = parseDate(dateStr);
        return dateObj <= today;
    });

    if (selectedMonth === -1) { // Whole Year
        return currentItems.filter(item => {
            const dateStr = (item[dateField] || "").replace(/\//g, "-");
            return dateStr.endsWith(`-${selectedYear}`);
        });
    }

    const monthStr = String(selectedMonth + 1).padStart(2, '0');
    const targetSuffix = `-${monthStr}-${selectedYear}`;
    
    let filtered = currentItems.filter(item => {
        const dateStr = (item[dateField] || "").replace(/\//g, "-");
        return dateStr.endsWith(targetSuffix);
    });

    if (selectedWeekIndex !== -1) {
        const weeks = getWeeksForMonth(selectedYear, selectedMonth);
        const week = weeks[selectedWeekIndex];
        filtered = filtered.filter(item => {
            const date = parseDate((item[dateField] || "").replace(/\//g, "-"));
            return date >= week.start && date <= week.end;
        });
    }

    return filtered;
}

function parseDate(dateStr) {
    const parts = dateStr.split("-");
    if (parts.length !== 3) return new Date(0);
    return new Date(parts[2], parts[1] - 1, parts[0], 12, 0, 0); // Noon to avoid DST issues
}

function getWeeksForMonth(year, month) {
    const weeks = [];
    let date = new Date(year, month, 1);
    let weekNum = 1;
    const now = new Date();

    while (date.getMonth() === month && date <= now) {
        const start = new Date(date);
        const end = new Date(date);
        end.setDate(date.getDate() + 6);
        if (end.getMonth() !== month) {
            end.setDate(0); // Last day of month
        }
        
        const label = `Semana ${weekNum} (${formatSmallDate(start)} - ${formatSmallDate(end)})`;
        weeks.push({ label, start, end });
        
        date.setDate(date.getDate() + 7);
        weekNum++;
    }
    return weeks;
}

function formatSmallDate(date) {
    return `${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}`;
}

// --- UI Rendering ---

function renderView(view) {
    if (isLoadingData) {
        dashboardContent.innerHTML = `
            <div class="fade-in" style="height: 400px; display: flex; flex-direction: column; align-items: center; justify-content: center;">
                <div class="spinner"></div>
                <h3 style="margin-top: 20px;">Carregando Produção...</h3>
                <p style="color: var(--text-secondary); font-size: 0.9rem;">Sincronizando dados dos agentes ACE</p>
            </div>
        `;
        return;
    }

    switch (view) {
        case 'summary':
            dashboardContent.innerHTML = getFiltersHTML() + getSummaryHTML();
            break;
        case 'map':
            dashboardContent.innerHTML = getMapHTML();
            setTimeout(() => initLeafletMap(), 100);
            break;
        case 'ace-list':
            dashboardContent.innerHTML = getFiltersHTML() + getAceListHTML();
            break;
    }
}

function getFiltersHTML() {
    const weeks = selectedMonth === -1 ? [] : getWeeksForMonth(selectedYear, selectedMonth);
    
    return `
        <div class="glass" style="padding: 15px 20px; margin-bottom: 30px; display: flex; gap: 20px; align-items: center; flex-wrap: wrap; border-radius: 16px;">
            <div class="filter-group">
                <label style="font-size: 0.75rem; color: var(--text-secondary); display: block; margin-bottom: 5px;">Ano</label>
                <select onchange="updateFilter('year', this.value)" class="glass-select">
                    <option value="2025" ${selectedYear === 2025 ? 'selected' : ''}>2025</option>
                    <option value="2026" ${selectedYear === 2026 ? 'selected' : ''}>2026</option>
                </select>
            </div>
            
            <div class="filter-group">
                <label style="font-size: 0.75rem; color: var(--text-secondary); display: block; margin-bottom: 5px;">Período</label>
                <select onchange="updateFilter('month', this.value)" class="glass-select">
                    ${MONTHS.map((m, i) => `<option value="${i-1}" ${selectedMonth === i-1 ? 'selected' : ''}>${m}</option>`).join('')}
                </select>
            </div>

            ${selectedMonth !== -1 ? `
            <div class="filter-group">
                <label style="font-size: 0.75rem; color: var(--text-secondary); display: block; margin-bottom: 5px;">Recorte Semanal</label>
                <select onchange="updateFilter('week', this.value)" class="glass-select">
                    <option value="-1" ${selectedWeekIndex === -1 ? 'selected' : ''}>Todo o Mês</option>
                    ${weeks.map((w, i) => `<option value="${i}" ${selectedWeekIndex === i ? 'selected' : ''}>${w.label}</option>`).join('')}
                </select>
            </div>
            ` : ''}

            <div style="margin-left: auto;">
                <button onclick="refreshDashboardData()" class="btn-secondary" style="padding: 5px 15px; font-size: 0.8rem;">🔄 Atualizar</button>
            </div>
        </div>
    `;
}

function updateFilter(type, value) {
    const val = parseInt(value);
    if (type === 'year') selectedYear = val;
    if (type === 'month') {
        selectedMonth = val;
        selectedWeekIndex = -1;
    }
    if (type === 'week') selectedWeekIndex = val;
    
    calculateAggregateStats();
    renderView(document.querySelector('.menu-btn.active')?.dataset.view || 'summary');
}

function getSummaryHTML() {
    if (!aggregateSummary) return '<div>Erro ao carregar sumário.</div>';
    const s = aggregateSummary.totals;
    const activeAgents = aggregateSummary.agents.filter(a => a.stats.activeDays > 0).length;
    const totalAgents = aggregateSummary.agents.length;

    return `
        <div class="fade-in">
            <h2 class="section-title" style="text-align: left; margin-bottom: 25px;">Resumo da Rede</h2>
            
            <div class="summary-card glass">
                <p class="summary-subtitle">PRODUÇÃO CONSOLIDADA</p>
                
                <div class="summary-grid">
                    <div class="summary-item">
                        <span class="icon">🏠</span>
                        <span class="val">${s.worked}</span>
                        <span class="label">TRABALHADOS</span>
                    </div>
                    <div class="summary-item">
                        <span class="icon">💧</span>
                        <span class="val">${s.treated}</span>
                        <span class="label">TRATADOS</span>
                    </div>
                    <div class="summary-item">
                        <span class="icon yellow">⚠️</span>
                        <span class="val danger">${s.focuses}</span>
                        <span class="label danger">COM FOCO</span>
                    </div>
                    <div class="summary-item">
                        <span class="icon">🚪</span>
                        <span class="val">${s.closed}</span>
                        <span class="label">FECHADOS</span>
                    </div>
                    <div class="summary-item">
                        <span class="icon">🏡</span>
                        <span class="val">${s.abandoned}</span>
                        <span class="label">ABANDONADOS</span>
                    </div>
                    <div class="summary-item">
                        <span class="icon">🚫</span>
                        <span class="val">${s.refused}</span>
                        <span class="label">RECUSADOS</span>
                    </div>
                </div>

                <div class="active-agents-section">
                    <span class="icon">👤</span>
                    <span class="val">${activeAgents}/${totalAgents}</span>
                    <span class="label">AGENTES ATIVOS</span>
                </div>
            </div>

            <p class="summary-footer-text">
                Este resumo contempla todos os registros enviados pelos agentes para o período selecionado.
            </p>
        </div>
    `;
}

function getAceListHTML() {
    if (!aggregateSummary) return '<div>Carregando agentes...</div>';
    
    const sortedAgents = [...aggregateSummary.agents].sort((a, b) => b.stats.worked - a.stats.worked);

    return `
        <div class="fade-in">
            <h2 class="section-title" style="text-align: left; margin-bottom: 25px;">Produção por Agente (ACE)</h2>
            <div class="ace-grid">
                ${sortedAgents.map(agent => {
                    const syncDateStr = agent.lastSyncTime ? new Date(agent.lastSyncTime * 1000).toLocaleDateString('pt-BR') : "—";
                    const syncTimeStr = agent.lastSyncTime ? new Date(agent.lastSyncTime * 1000).toLocaleTimeString('pt-BR', {hour: '2-digit', minute:'2-digit'}) : "";
                    
                    return `
                    <div class="agent-card glass ${agent.stats.activeDays === 0 ? 'inactive' : ''}">
                        <div class="agent-header">
                            <div class="agent-avatar">
                                ${agent.photoUrl ? `<img src="${agent.photoUrl}" alt="${agent.agentName}">` : '👤'}
                            </div>
                            <div class="agent-meta">
                                <h4 class="agent-name">${agent.agentName}</h4>
                                <p class="sync-tag">Sinc: ${syncDateStr} ${syncTimeStr}</p>
                            </div>
                            <div class="status-dot ${agent.stats.activeDays > 0 ? 'online' : 'offline'}"></div>
                        </div>

                        <!-- Core Stats Row -->
                        <div class="core-stats-row">
                            <div class="core-stat">
                                <span class="val">${agent.stats.worked}</span>
                                <span class="lbl">TRABALHADOS</span>
                            </div>
                            <div class="core-stat">
                                <span class="val">${agent.stats.treated}</span>
                                <span class="lbl">TRATADOS</span>
                            </div>
                            <div class="core-stat ${agent.stats.focuses > 0 ? 'danger' : ''}">
                                <span class="val">${agent.stats.focuses}</span>
                                <span class="lbl">FOCOS</span>
                            </div>
                        </div>

                        <!-- Situation Grid (Compact) -->
                        <div class="compact-stats-grid">
                            <div class="grid-item"><span>Vazio:</span> <strong>${agent.stats.vacant}</strong></div>
                            <div class="grid-item"><span>Fechado:</span> <strong>${agent.stats.closed}</strong></div>
                            <div class="grid-item"><span>Recusado:</span> <strong>${agent.stats.refused}</strong></div>
                            <div class="grid-item"><span>Aband.:</span> <strong>${agent.stats.abandoned}</strong></div>
                        </div>

                        <!-- Imóveis Grid (Compact) -->
                        <div class="compact-stats-grid light">
                            <div class="grid-item"><span>RES:</span> <strong>${agent.stats.res}</strong></div>
                            <div class="grid-item"><span>COM:</span> <strong>${agent.stats.com}</strong></div>
                            <div class="grid-item"><span>TB:</span> <strong>${agent.stats.tb}</strong></div>
                            <div class="grid-item"><span>PE/OUT:</span> <strong>${agent.stats.pe + agent.stats.out}</strong></div>
                        </div>

                        <!-- Recent Production -->
                        <div class="recent-prod-compact">
                            ${agent.recentActivities.slice(0, 3).map(act => `
                                <div class="prod-line">
                                    <span class="p-date" onclick="viewDayDetails('${agent.uid}', '${act.date}')">${act.date.split('-')[0]}/${act.date.split('-')[1]}</span>
                                    <span class="p-count" onclick="viewDayDetails('${agent.uid}', '${act.date}')">${act.workedCount} trabalhados</span>
                                    <span class="p-status" onclick="toggleDayLock('${agent.uid}', '${act.date}', ${act.isLocked})">${act.isLocked ? '🔒' : '🔓'}</span>
                                </div>
                            `).join('')}
                        </div>

                        <!-- Footer -->
                        <div class="card-footer-compact">
                            <span>Ativo: <strong>${agent.stats.activeDays} dias</strong></span>
                            ${userProfile.role === 'ADMIN' ? `
                                <button onclick="cleanupAgentDuplicates('${agent.uid}')" class="btn-heal-mini">🛡️ Curar</button>
                            ` : ''}
                        </div>
                    </div>
                    `;
                }).join('')}
            </div>
        </div>
    `;
}

// --- Map Functions (Kept from previous version) ---

function getMapHTML() {
    const isAdmin = userProfile.role === 'ADMIN';

    return `
        <div class="fade-in">
            <h2 class="section-title" style="text-align: left; margin-bottom: 10px;">Monitoramento Geospacial</h2>
            <p style="color: var(--text-secondary); margin-bottom: 24px;">Visualização em tempo real dos quarteirões e áreas de risco.</p>
            
            <div class="map-container">
                <div id="map"></div>
                <div id="map-loading-state" class="map-loading">
                    <div class="spinner"></div>
                    <div><h3>Carregando Mapa...</h3></div>
                </div>
                <div class="map-overlay-info">
                    <div class="map-badge"><span style="color: #4caf50;">●</span> Project: BOM JARDIM - RJ</div>
                    <div id="kml-status" class="map-badge" style="display: none;"></div>
                </div>
            </div>

            <div class="db-grid">
                <div class="db-card glass">
                    <h4>Legenda do Projeto</h4>
                    <div style="display: flex; flex-direction: column; gap: 12px; margin-top: 10px;">
                        <div style="display: flex; align-items: center; gap: 12px;">
                            <div style="width: 14px; height: 14px; background: rgba(76, 175, 80, 0.4); border: 2px solid #4caf50; border-radius: 4px;"></div>
                            <span style="font-size: 0.9rem;">Quarteirão Normal</span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 12px;">
                            <div style="width: 14px; height: 14px; background: rgba(255, 82, 82, 0.4); border: 2px solid #ff5252; border-radius: 4px;"></div>
                            <span style="font-size: 0.9rem;">Área de Risco</span>
                        </div>
                    </div>
                </div>
                
                ${isAdmin ? `
                <div class="db-card glass" style="flex: 1; border-color: rgba(76, 175, 80, 0.3);">
                    <h4>Gestão de KML (Admin)</h4>
                    <div style="display: flex; gap: 10px; flex-wrap: wrap; margin-top: 15px;">
                        <button onclick="fetchKML()" class="btn-secondary" style="padding: 8px 16px; font-size: 0.8rem;">🔄 Sincronizar</button>
                        <button onclick="triggerKmlUpload()" class="btn-primary" style="padding: 8px 16px; font-size: 0.8rem;">📂 Importar</button>
                    </div>
                    <input type="file" id="kml-file-input" accept=".kml" style="display: none;" onchange="handleKmlFile(event)">
                </div>
                ` : `
                <div class="db-card glass" style="flex: 1; opacity: 0.8;">
                    <h4>Modo Visualização</h4>
                    <p style="font-size: 0.85rem; color: var(--text-secondary);">As camadas são geridas pela administração.</p>
                </div>
                `}
            </div>
        </div>
    `;
}

function initLeafletMap() {
    if (leafletMap) leafletMap.remove();
    leafletMap = L.map('map', { zoomControl: false }).setView([-22.156068, -42.428657], 14);
    L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager_labels_under/{z}/{x}/{y}{r}.png').addTo(leafletMap);
    L.control.zoom({ position: 'topright' }).addTo(leafletMap);
    fetchKML();
}

async function fetchKML() {
    const loadingEl = document.getElementById('map-loading-state');
    const statusEl = document.getElementById('kml-status');
    if (loadingEl) loadingEl.style.display = 'flex';
    
    try {
        const driveUrl = `https://drive.google.com/uc?id=1gHbsFGvKEBwI9N0ZAUAgiFpIjpdwFRWQ&export=download`;
        const proxyUrl = CORS_PROXY + encodeURIComponent(driveUrl);
        if (currentKmlLayer) leafletMap.removeLayer(currentKmlLayer);
        currentKmlLayer = omnivore.kml(proxyUrl, null, L.geoJson(null, { style: kmlStyle, onEachFeature: kmlOnEachFeature }));
        currentKmlLayer.on('ready', () => {
            currentKmlLayer.addTo(leafletMap);
            leafletMap.fitBounds(currentKmlLayer.getBounds(), { padding: [50, 50] });
            if (loadingEl) loadingEl.style.display = 'none';
        });
    } catch (e) {
        if (loadingEl) loadingEl.style.display = 'none';
    }
}

function triggerKmlUpload() { document.getElementById('kml-file-input').click(); }
function handleKmlFile(event) {
    const file = event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (e) => {
        if (currentKmlLayer) leafletMap.removeLayer(currentKmlLayer);
        currentKmlLayer = omnivore.kml.parse(e.target.result, null, L.geoJson(null, { style: kmlStyle, onEachFeature: kmlOnEachFeature }));
        currentKmlLayer.addTo(leafletMap);
        leafletMap.fitBounds(currentKmlLayer.getBounds());
    };
    reader.readAsText(file);
}

function kmlStyle() { return { color: '#4caf50', weight: 2, fillOpacity: 0.1 }; }
function kmlOnEachFeature(feature, layer) {
    if (feature.properties && feature.properties.name) {
        layer.bindPopup(`<strong>${feature.properties.name}</strong><p>${feature.properties.description || ''}</p>`);
    }
}

// Global initialization
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) target.scrollIntoView({ behavior: 'smooth' });
    });
});

