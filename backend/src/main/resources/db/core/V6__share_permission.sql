-- Neues Systemrecht „Mit allen teilen“ (Skills und Memories für alle Benutzer freigeben); die vorbelegte Rolle
-- Benutzer bekommt es, Administratoren haben ohnehin alle Rechte.
INSERT INTO role_permission (role_id, permission) SELECT id, 'shares.all' FROM app_role WHERE name = 'Benutzer';
