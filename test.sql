CREATE TABLE houses (
    blockNumber TEXT,
    streetName TEXT,
    number TEXT,
    sequence INTEGER,
    complement INTEGER,
    propertyType TEXT,
    situation TEXT,
    bairro TEXT,
    categoria TEXT,
    zona TEXT,
    tipo INTEGER,
    data TEXT,
    ciclo TEXT,
    atividade INTEGER,
    agentName TEXT,
    visitSegment INTEGER,
    agentUid TEXT,
    observation TEXT,
    createdAt INTEGER,
    isSynced INTEGER,
    latitude REAL,
    longitude REAL,
    focusCaptureTime INTEGER,
    lastUpdated INTEGER
);

INSERT INTO houses (blockNumber, streetName, number, sequence, complement) VALUES ('A', 'B', '1', NULL, NULL);
INSERT INTO houses (blockNumber, streetName, number, sequence, complement) VALUES ('C', 'D', '2', 1, 2);
INSERT INTO houses (blockNumber, streetName, number, sequence, complement) VALUES ('E', 'F', '3', '0', '0');
INSERT INTO houses (blockNumber, streetName, number, sequence, complement) VALUES ('G', 'H', '4', '', '');

CREATE TABLE houses_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    blockNumber TEXT NOT NULL,
    streetName TEXT NOT NULL,
    number TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    complement INTEGER NOT NULL,
    propertyType TEXT NOT NULL,
    situation TEXT NOT NULL
);

INSERT INTO houses_new (
    blockNumber, streetName, number, sequence, complement, propertyType, situation
)
SELECT 
    COALESCE(CAST(blockNumber AS TEXT), ''),
    COALESCE(CAST(streetName AS TEXT), ''),
    COALESCE(CAST(number AS TEXT), ''),
    COALESCE(CAST(sequence AS INTEGER), 0),
    COALESCE(CAST(complement AS INTEGER), 0),
    COALESCE(CAST(propertyType AS TEXT), 'EMPTY'),
    COALESCE(CAST(situation AS TEXT), 'EMPTY')
FROM houses
GROUP BY
    COALESCE(CAST(blockNumber AS TEXT), ''),
    COALESCE(CAST(streetName AS TEXT), ''),
    COALESCE(CAST(number AS TEXT), ''),
    COALESCE(CAST(sequence AS INTEGER), 0),
    COALESCE(CAST(complement AS INTEGER), 0);
