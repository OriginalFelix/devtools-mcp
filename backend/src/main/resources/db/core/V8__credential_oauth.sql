-- Zugangsdaten mit Anmeldung im Browser (OAuth 2.0 / OpenID Connect): auth = TOKEN (von Hand) oder OAUTH. Bei OAUTH
-- ist secret das Access-Token; client_secret und refresh_token sind wie secret mit secret.key verschlüsselt und gehen
-- nie aus dem Backend heraus – das Backend erneuert das Access-Token selbst.
ALTER TABLE credential ADD COLUMN auth VARCHAR(8) DEFAULT 'TOKEN' NOT NULL;
ALTER TABLE credential ADD COLUMN issuer VARCHAR(1000);
ALTER TABLE credential ADD COLUMN client_id VARCHAR(500);
ALTER TABLE credential ADD COLUMN client_secret VARCHAR(8000);
ALTER TABLE credential ADD COLUMN scopes VARCHAR(1000);
ALTER TABLE credential ADD COLUMN refresh_token VARCHAR(8000);
ALTER TABLE credential ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
-- Ohne Anmeldung hat ein OAuth-Eintrag noch kein Access-Token.
ALTER TABLE credential ALTER COLUMN secret DROP NOT NULL;
