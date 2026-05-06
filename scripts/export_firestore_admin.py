#!/usr/bin/env python3
"""Export Firestore collections to CSV using Firebase Admin SDK.

Usage:
    python3 export_firestore_admin.py /path/to/serviceAccountKey.json

The script will create a directory `firestore_export` next to this file and write:
    - cloud_houses.csv
    - cloud_activities.csv

Both CSVs contain all documents from the respective collections with the field names
as column headers.  The script is read‑only – it never writes back to Firestore.
"""

import sys
import os
import csv
from pathlib import Path

# ------------------------------------------------------------
# Verify arguments
# ------------------------------------------------------------
if len(sys.argv) != 2:
    print("Usage: python3 export_firestore_admin.py <service_account_json>")
    sys.exit(1)

service_account_path = Path(sys.argv[1]).expanduser().resolve()
if not service_account_path.is_file():
    print(f"Error: service account file not found: {service_account_path}")
    sys.exit(1)

# ------------------------------------------------------------
# Initialise Firebase Admin SDK
# ------------------------------------------------------------
try:
    import firebase_admin
    from firebase_admin import credentials, firestore
except ImportError:
    print("Error: firebase-admin package not installed. Install with: pip install firebase-admin")
    sys.exit(1)

cred = credentials.Certificate(str(service_account_path))
# Initialise the app only once; reuse if already initialized
if not firebase_admin._apps:
    firebase_admin.initialize_app(cred)
else:
    # If an app is already initialised with a different credential the SDK may raise
    #, but for this script we just reuse the existing one.
    pass

db = firestore.client()

# ------------------------------------------------------------
# Helper to export a collection to CSV
# ------------------------------------------------------------
def export_collection(collection_name: str, output_path: Path):
    print(f"Exporting collection '{collection_name}' to {output_path}")
    docs = db.collection(collection_name).stream()
    rows = []
    for doc in docs:
        data = doc.to_dict()
        # Preserve the document ID as a column (useful for identityKey)
        data["_id"] = doc.id
        rows.append(data)

    if not rows:
        print(f"  No documents found in collection '{collection_name}'. Writing empty CSV.")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", newline="", encoding="utf-8") as f:
            f.write("\n")
        return

    # Determine header order – put _id first, then sorted keys
    header = ["_id"] + sorted([k for k in rows[0].keys() if k != "_id"])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    print(f"  Exported {len(rows)} documents.")

# ------------------------------------------------------------
# Perform the exports
# ------------------------------------------------------------
base_dir = Path(__file__).parent
export_dir = base_dir / "firestore_export"
export_dir.mkdir(parents=True, exist_ok=True)

export_collection("houses", export_dir / "cloud_houses.csv")
export_collection("day_activities", export_dir / "cloud_activities.csv")

print("Export completed.")
