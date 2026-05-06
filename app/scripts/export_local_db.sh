#!/usr/bin/env bash
# export_local_db.sh
# Extracts the HealthAgentForms SQLite database from a connected Android device/emulator,
# copies it to the host machine, and exports the `houses` and `day_activities` tables to CSV.

set -euo pipefail

# Package name and DB filename (adjust if they change)
# Package name (debug build required)
PACKAGE="com.antigravity.healthagent"

# Determine the DB file name inside the app's databases directory
# This uses run-as to list .db files as the app user
# Use the primary app DB file name (known)
DB_NAME="health_agent_db"


# Destination directory (relative to the script location)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="$SCRIPT_DIR/local_export"
mkdir -p "$DEST_DIR"

# Pull the DB using run-as (requires debug build)
adb exec-out run-as $PACKAGE cat /data/data/$PACKAGE/databases/$DB_NAME > "$DEST_DIR/$DB_NAME"

# Export tables using sqlite3 (must be installed on host)
sqlite3 "$DEST_DIR/$DB_NAME" "SELECT * FROM houses;" > "$DEST_DIR/local_houses.csv"
sqlite3 "$DEST_DIR/$DB_NAME" "SELECT * FROM day_activities;" > "$DEST_DIR/local_activities.csv"

echo "Export complete. CSV files are in $DEST_DIR"
