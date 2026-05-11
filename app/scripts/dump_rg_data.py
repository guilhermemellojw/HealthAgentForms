import sqlite3
import os

db_path = "/home/guilherme/Documentos/HealthAgentForms/app/src/main/assets/healthagent.db"
# If asset doesn't exist, try common local paths
if not os.path.exists(db_path):
    # Search for any .db file in the project
    for root, dirs, files in os.walk("/home/guilherme/Documentos/HealthAgentForms"):
        for file in files:
            if file.endswith(".db"):
                db_path = os.path.join(root, file)
                break

if os.path.exists(db_path):
    print(f"Using database: {db_path}")
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # Check tables
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
    tables = cursor.fetchall()
    print(f"Tables: {tables}")
    
    # Query houses for Ouro Verde Q1
    # We use LIKE for Bairro to be safe with normalization
    query = """
    SELECT id, data, streetName, number, sequence, complement, listOrder, agentUid, bairro, blockNumber 
    FROM houses 
    WHERE bairro LIKE '%OURO VERDE%' AND blockNumber = '1'
    ORDER BY data, listOrder
    """
    cursor.execute(query)
    rows = cursor.fetchall()
    
    print("\n--- Houses in Jardim Ouro Verde Q1 ---")
    print("ID | Data | Street | Num | Seq | Comp | LO | Agent")
    for row in rows:
        print(row)
        
    conn.close()
else:
    print("Database not found.")
