-- OAuth-Angaben von Hand (name=wert; …): Endpunkte für Server ohne Discovery, feste Rückleitung, weitere Parameter.
ALTER TABLE credential ADD COLUMN oauth_options VARCHAR(2000);
