-- Stand vor dem User-Scoping (Commit d3a4168): Skill-Name global eindeutig, keine Spalte owner.
-- Erzeugt mit org.h2.tools.Script aus einer echten Datenbank der App.
-- H2 2.4.240; 
;              
CREATE SEQUENCE "PUBLIC"."SKILL_FILE_SEQ" START WITH 1 RESTART WITH 51 INCREMENT BY 50;        
CREATE SEQUENCE "PUBLIC"."SKILL_REVISION_SEQ" START WITH 1 RESTART WITH 101 INCREMENT BY 50;   
CREATE SEQUENCE "PUBLIC"."SKILL_SEQ" START WITH 1 RESTART WITH 101 INCREMENT BY 50;            
CREATE CACHED TABLE "PUBLIC"."SKILL"(
    "ID" BIGINT NOT NULL,
    "CATEGORY" CHARACTER VARYING(64),
    "CONTENT" CHARACTER VARYING(200000) NOT NULL,
    "CREATED_AT" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "DESCRIPTION" CHARACTER VARYING(1024) NOT NULL,
    "LAST_USED_AT" TIMESTAMP(6) WITH TIME ZONE,
    "NAME" CHARACTER VARYING(64) NOT NULL,
    "REVISION" INTEGER NOT NULL,
    "TAGS" CHARACTER VARYING(500),
    "UPDATED_AT" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "USE_COUNT" BIGINT NOT NULL,
    "VERSION" BIGINT NOT NULL
);     
ALTER TABLE "PUBLIC"."SKILL" ADD CONSTRAINT "PUBLIC"."CONSTRAINT_4" PRIMARY KEY("ID");         
-- 3 +/- SELECT COUNT(*) FROM PUBLIC.SKILL;    
INSERT INTO "PUBLIC"."SKILL" VALUES
(1, 'software-development', U&'## Schritte\000a1. `jvm_processes` \2013 PID des WildFly ermitteln (\00e4ndert sich nach jedem Redeploy).\000a2. `jvm_heap` zweimal im Abstand von 1\20132 Minuten, wachsende Klassen vergleichen.\000a3. `jvm_heap_dump`, dann `visualvm_heap_analyze` mit Pfad zur GC-Wurzel.\000a\000a## Fallstricke\000a- ThreadLocals in Pool-Threads sind h\00e4ufige Halter \2013 im GC-Wurzel-Pfad nach `ThreadLocalMap` suchen.\000a- Vor dem Heap-Dump fragen: der Dump h\00e4lt die JVM kurz an.\000a', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.572598+00', U&'Verwenden, wenn der WildFly-Heap w\00e4chst: Leck mit jvm_heap und visualvm_heap_analyze eingrenzen.', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.638006+00', 'wildfly-heap-leak', 3, 'wildfly,heap,leak', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.622346+00', 1, 2),
(2, 'build', U&'## Regel\000aJAVA_HOME auf ein vom Wrapper unterst\00fctztes JDK setzen \2013 unabh\00e4ngig von der Toolchain des Projekts.\000a', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.628967+00', 'Verwenden, wenn der Gradle-Wrapper mit ''Unsupported class file major version'' abbricht.', NULL, 'gradle-toolchain-jdk', 1, 'gradle,jdk', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.628967+00', 0, 0),
(3, NULL, U&'- Deutsch, Fachbegriffe englisch\000a- Ergebnis zuerst, dann Belege\000a', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.63348+00', 'Verwenden bei jeder Antwort an Felix: deutsch, knapp, empirisch belegt.', NULL, 'antwortstil-felix', 1, 'stil', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.63348+00', 0, 0); 
CREATE CACHED TABLE "PUBLIC"."SKILL_FILE"(
    "ID" BIGINT NOT NULL,
    "CONTENT" CHARACTER VARYING(200000) NOT NULL,
    "PATH" CHARACTER VARYING(200) NOT NULL,
    "UPDATED_AT" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "SKILL_ID" BIGINT NOT NULL
);    
ALTER TABLE "PUBLIC"."SKILL_FILE" ADD CONSTRAINT "PUBLIC"."CONSTRAINT_7" PRIMARY KEY("ID");    
-- 1 +/- SELECT COUNT(*) FROM PUBLIC.SKILL_FILE;               
INSERT INTO "PUBLIC"."SKILL_FILE" VALUES
(1, U&'# jcmd-Befehle\000a- GC.class_histogram\000a- GC.heap_dump\000a- VM.native_memory summary\000a', 'references/jcmd.md', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.622346+00', 1);           
CREATE CACHED TABLE "PUBLIC"."SKILL_REVISION"(
    "ID" BIGINT NOT NULL,
    "ACTION" CHARACTER VARYING(20) NOT NULL,
    "CHANGED_AT" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "CONTENT" CHARACTER VARYING(200000) NOT NULL,
    "DESCRIPTION" CHARACTER VARYING(1024) NOT NULL,
    "NOTE" CHARACTER VARYING(500),
    "REVISION" INTEGER NOT NULL,
    "SKILL_ID" BIGINT NOT NULL
);       
ALTER TABLE "PUBLIC"."SKILL_REVISION" ADD CONSTRAINT "PUBLIC"."CONSTRAINT_2" PRIMARY KEY("ID");
-- 5 +/- SELECT COUNT(*) FROM PUBLIC.SKILL_REVISION;           
INSERT INTO "PUBLIC"."SKILL_REVISION" VALUES
(1, 'create', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.572598+00', U&'## Schritte\000a1. `jvm_processes` \2013 PID des WildFly ermitteln (\00e4ndert sich nach jedem Redeploy).\000a2. `jvm_heap` zweimal im Abstand von 1\20132 Minuten, wachsende Klassen vergleichen.\000a3. `jvm_heap_dump`, dann `visualvm_heap_analyze` mit Pfad zur GC-Wurzel.\000a\000a## Fallstricke\000a- Vor dem Heap-Dump fragen: der Dump h\00e4lt die JVM kurz an.\000a', U&'Verwenden, wenn der WildFly-Heap w\00e4chst: Leck mit jvm_heap und visualvm_heap_analyze eingrenzen.', NULL, 1, 1),
(2, 'patch', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.611894+00', U&'## Schritte\000a1. `jvm_processes` \2013 PID des WildFly ermitteln (\00e4ndert sich nach jedem Redeploy).\000a2. `jvm_heap` zweimal im Abstand von 1\20132 Minuten, wachsende Klassen vergleichen.\000a3. `jvm_heap_dump`, dann `visualvm_heap_analyze` mit Pfad zur GC-Wurzel.\000a\000a## Fallstricke\000a- ThreadLocals in Pool-Threads sind h\00e4ufige Halter \2013 im GC-Wurzel-Pfad nach `ThreadLocalMap` suchen.\000a- Vor dem Heap-Dump fragen: der Dump h\00e4lt die JVM kurz an.\000a', U&'Verwenden, wenn der WildFly-Heap w\00e4chst: Leck mit jvm_heap und visualvm_heap_analyze eingrenzen.', U&'ThreadLocal-Hinweis erg\00e4nzt', 2, 1),
(3, 'write_file', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.622346+00', U&'## Schritte\000a1. `jvm_processes` \2013 PID des WildFly ermitteln (\00e4ndert sich nach jedem Redeploy).\000a2. `jvm_heap` zweimal im Abstand von 1\20132 Minuten, wachsende Klassen vergleichen.\000a3. `jvm_heap_dump`, dann `visualvm_heap_analyze` mit Pfad zur GC-Wurzel.\000a\000a## Fallstricke\000a- ThreadLocals in Pool-Threads sind h\00e4ufige Halter \2013 im GC-Wurzel-Pfad nach `ThreadLocalMap` suchen.\000a- Vor dem Heap-Dump fragen: der Dump h\00e4lt die JVM kurz an.\000a', U&'Verwenden, wenn der WildFly-Heap w\00e4chst: Leck mit jvm_heap und visualvm_heap_analyze eingrenzen.', 'jcmd-Referenz', 3, 1),
(4, 'create', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.628967+00', U&'## Regel\000aJAVA_HOME auf ein vom Wrapper unterst\00fctztes JDK setzen \2013 unabh\00e4ngig von der Toolchain des Projekts.\000a', 'Verwenden, wenn der Gradle-Wrapper mit ''Unsupported class file major version'' abbricht.', NULL, 1, 2),
(5, 'create', TIMESTAMP WITH TIME ZONE '2026-09-28 10:50:42.63348+00', U&'- Deutsch, Fachbegriffe englisch\000a- Ergebnis zuerst, dann Belege\000a', 'Verwenden bei jeder Antwort an Felix: deutsch, knapp, empirisch belegt.', NULL, 1, 3);  
ALTER TABLE "PUBLIC"."SKILL" ADD CONSTRAINT "PUBLIC"."UK_SKILL_NAME" UNIQUE NULLS DISTINCT ("NAME");           
ALTER TABLE "PUBLIC"."SKILL_FILE" ADD CONSTRAINT "PUBLIC"."UK_SKILL_FILE_PATH" UNIQUE NULLS DISTINCT ("SKILL_ID", "PATH");     
ALTER TABLE "PUBLIC"."SKILL_REVISION" ADD CONSTRAINT "PUBLIC"."UK_SKILL_REVISION" UNIQUE NULLS DISTINCT ("SKILL_ID", "REVISION");              
ALTER TABLE "PUBLIC"."SKILL_FILE" ADD CONSTRAINT "PUBLIC"."FK_SKILL_FILE_SKILL" FOREIGN KEY("SKILL_ID") REFERENCES "PUBLIC"."SKILL"("ID") NOCHECK;             
ALTER TABLE "PUBLIC"."SKILL_REVISION" ADD CONSTRAINT "PUBLIC"."FK_SKILL_REVISION_SKILL" FOREIGN KEY("SKILL_ID") REFERENCES "PUBLIC"."SKILL"("ID") NOCHECK;     
