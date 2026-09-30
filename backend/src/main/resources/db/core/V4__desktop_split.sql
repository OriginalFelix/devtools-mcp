-- Der Server ist kein MCP-Server mehr: Projekte sind nur noch Metadaten, das Verzeichnis ordnet jede Desktop-App zu.
ALTER TABLE project DROP COLUMN root_path;

-- Module der Desktop-Apps (Felder, Tools) für die Einstellungs-Oberfläche; die zuletzt gemeldete Beschreibung gilt.
CREATE TABLE module_catalog (
    module_id  VARCHAR(32)              NOT NULL PRIMARY KEY,
    descriptor TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- module_override.level kennt jetzt auch GLOBAL (level_id = 0): die Vorgaben des Administrators für alle.
