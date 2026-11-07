-- Utilidades de prueba (solo en bases de prueba; nunca en producción)
CREATE SCHEMA IF NOT EXISTS t;
GRANT USAGE ON SCHEMA t TO crdo_app, crdo_ops, crdo_readonly, crdo_backup;

CREATE OR REPLACE FUNCTION t.ok(cond boolean, name text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF cond IS NOT TRUE THEN RAISE EXCEPTION 'FALLA: %', name; END IF;
  RAISE NOTICE 'PASA: %', name;
END $$;

-- Ejecuta q con el rol actual y exige que falle
CREATE OR REPLACE FUNCTION t.must_fail(q text, name text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE q;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'PASA: % (rechazado: %)', name, SQLERRM;
    RETURN;
  END;
  RAISE EXCEPTION 'FALLA: % (se esperaba rechazo)', name;
END $$;

CREATE OR REPLACE FUNCTION t.explain_text(q text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE r text; acc text := '';
BEGIN
  FOR r IN EXECUTE 'EXPLAIN ' || q LOOP acc := acc || r || E'\n'; END LOOP;
  RETURN acc;
END $$;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA t TO crdo_app, crdo_ops, crdo_readonly, crdo_backup;

-- Código TOTP actual de un usuario (solo para pruebas; la app no tiene este poder)
CREATE OR REPLACE FUNCTION t.code(p_user uuid, p_offset int DEFAULT 0) RETURNS text LANGUAGE sql AS $$
  SELECT auth.hotp(secret, floor(extract(epoch FROM clock_timestamp()) / 30)::bigint + p_offset)
    FROM auth.totp_secrets WHERE user_id = p_user;
$$;

-- Fixtures: 4 usuarios con secretos distintos (cada código TOTP sirve una sola vez por usuario)
INSERT INTO auth.users (id, email, display_name, role, password_hash) VALUES
  ('00000000-0000-0000-0000-000000000001', 'aprobador@crdo.test', 'Aprobador', 'owner', 'x'),
  ('00000000-0000-0000-0000-000000000002', 'killswitch@crdo.test', 'Kill switch', 'owner', 'x'),
  ('00000000-0000-0000-0000-000000000003', 'bloqueo@crdo.test',   'Bloqueo',  'owner', 'x'),
  ('00000000-0000-0000-0000-000000000004', 'admin@crdo.test',     'Admin cmd', 'owner', 'x'),
  ('00000000-0000-0000-0000-000000000005', 'lector@crdo.test',    'Lector',   'viewer', 'x');
INSERT INTO auth.totp_secrets (user_id, secret)
SELECT id, gen_random_bytes(20) FROM auth.users;
INSERT INTO org.agents (id, department_id, code, name) VALUES
  ('00000000-0000-0000-0000-0000000000a1', 2, 'inv-prueba', 'Agente de prueba');
