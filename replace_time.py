import glob
import re

files = [
    "app/src/main/java/com/antigravity/healthagent/data/repository/HouseRepositoryImpl.kt",
    "app/src/main/java/com/antigravity/healthagent/data/repository/AuthRepositoryImpl.kt",
    "app/src/main/java/com/antigravity/healthagent/data/backup/BackupManager.kt",
    "app/src/main/java/com/antigravity/healthagent/data/repository/BackupRepositoryImpl.kt",
    "app/src/main/java/com/antigravity/healthagent/data/repository/SyncRepositoryImpl.kt",
    "app/src/main/java/com/antigravity/healthagent/data/repository/AgentRepositoryImpl.kt",
]

for f in files:
    with open(f, 'r') as file:
        content = file.read()
    
    content = re.sub(r'=\s*System\.currentTimeMillis\(\)', '= com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()', content)
    content = re.sub(r'to\s*System\.currentTimeMillis\(\)', 'to com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()', content)
    
    with open(f, 'w') as file:
        file.write(content)

