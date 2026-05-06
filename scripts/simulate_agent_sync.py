#!/usr/bin/env python3
"""
simulate_agent_sync.py

Simula a sincronização (Push) de um agente diretamente para o Firestore.
Útil para testar limites do banco e da sincronização sem usar um emulador Android.
Gera 100 casas (5 quarteirões com 20 casas cada).

Uso:
    python3 simulate_agent_sync.py /caminho/para/serviceAccountKey.json
"""

import sys
import os
import time
from pathlib import Path
from datetime import datetime

if len(sys.argv) != 2:
    print("Uso: python3 simulate_agent_sync.py <service_account_json>")
    sys.exit(1)

service_account_path = Path(sys.argv[1]).expanduser().resolve()
if not service_account_path.is_file():
    print(f"Erro: Arquivo JSON da service account não encontrado: {service_account_path}")
    sys.exit(1)

try:
    import firebase_admin
    from firebase_admin import credentials, firestore, auth
except ImportError:
    print("Erro: Instale o firebase-admin: pip install firebase-admin")
    sys.exit(1)

cred = credentials.Certificate(str(service_account_path))
if not firebase_admin._apps:
    firebase_admin.initialize_app(cred)

db = firestore.client()

TEST_EMAIL = "gmellobkp@gmail.com"
AGENT_NAME = "MOCK AGENT"
CURRENT_DATE = datetime.now().strftime("%d-%m-%Y")

# 1. Encontrar o UID do usuário de teste
try:
    user = auth.get_user_by_email(TEST_EMAIL)
    uid = user.uid
    print(f"Usuário encontrado! Email: {TEST_EMAIL} | UID: {uid}")
except auth.UserNotFoundError:
    print(f"Erro: O usuário de teste {TEST_EMAIL} não existe no Firebase Auth.")
    print("Crie-o pelo painel do Firebase ou logue no app com esta conta uma vez.")
    sys.exit(1)

# 2. Referência ao documento do agente
agent_ref = db.collection("agents").document(uid)

print(f"Iniciando injeção de dados de teste para a data: {CURRENT_DATE}...")

# 3. Criar atividade do dia (DayActivity)
day_activity = {
    "date": CURRENT_DATE,
    "agentName": AGENT_NAME,
    "agentUid": uid,
    "status": "NORMAL",
    "isClosed": False,
    "isSynced": 1,
    "editedByAdmin": 0,
    "lastUpdated": int(time.time() * 1000)
}
agent_ref.collection("day_activities").document(CURRENT_DATE).set(day_activity, merge=True)
print("-> DayActivity criada.")

# 4. Criar as Casas (Houses) em batch para maior velocidade
batch = db.batch()
houses_count = 0
list_order_counter = 1000

# 5 Quarteirões, 20 casas cada
for block_idx in range(1, 6):
    block_str = f"{block_idx:03d}"
    for house_idx in range(1, 21):
        house_key = f"{CURRENT_DATE}_{block_str}_A_RUA TESTE {block_idx}_{house_idx}_1_0_CENTRO"
        
        house_data = {
            "data": CURRENT_DATE,
            "agentName": AGENT_NAME,
            "agentUid": uid,
            "bairro": "CENTRO",
            "blockNumber": block_str,
            "blockSequence": "A",
            "streetName": f"RUA TESTE {block_idx}",
            "number": str(house_idx),
            "sequence": 1,
            "complement": 0,
            "propertyType": "RESIDENCIAL",
            "situation": "NONE",
            "visitSegment": 1, # INTRADOMICILIAR
            "listOrder": list_order_counter,
            "isSynced": 1,
            "editedByAdmin": 0,
            "lastUpdated": int(time.time() * 1000),
            
            # Zerar todos os indicadores epidemiológicos
            "a1": 0, "a2": 0, "b": 0, "c": 0, "d1": 0, "d2": 0, "e": 0,
            "eliminados": 0, "larvicida": 0.0, "comFoco": 0, "localidadeConcluida": 0,
            "quarteiraoConcluido": 0, "observation": "", "createdAt": int(time.time() * 1000),
            "municipio": "Bom Jardim", "categoria": "BRR", "zona": "URB", "tipo": 2,
            "ciclo": "1º", "atividade": 4
        }
        
        doc_ref = agent_ref.collection("houses").document(house_key)
        batch.set(doc_ref, house_data, merge=True)
        
        houses_count += 1
        list_order_counter += 1

# Atualiza metadata do agente
metadata = {
    "email": TEST_EMAIL,
    "agentName": AGENT_NAME,
    "lastSyncTime": int(time.time() * 1000),
    "lastUpdated": firestore.SERVER_TIMESTAMP
}
batch.set(agent_ref, metadata, merge=True)

print("Commitando batch de casas...")
batch.commit()

print(f"Sucesso! {houses_count} casas de teste injetadas no Firebase para o UID: {uid}.")
