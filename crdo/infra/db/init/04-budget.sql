-- C.R.D.O. — Control presupuestario (BORRADOR v3, Etapa 1)
-- Tope oficial de infraestructura: US$ 20/mes TODO INCLUIDO (servicios, almacenamiento, respaldos, dominio
-- prorrateado, impuestos y comisiones). La IA (Claude API) tiene un presupuesto SEPARADO.
--
-- Principios:
--   * Se presupuesta el costo BRUTO: precio neto x tipo de cambio conservador x impuestos y comisiones.
--   * Reserva obligatoria (15 % por defecto): el tope operativo es US$ 17.
--   * Un servicio OPCIONAL no se puede activar si la proyección superaría el tope operativo.
--   * Si la proyección supera el tope operativo, el guardián pausa los opcionales y avisa.
--   * Los servicios INDISPENSABLES nunca se apagan solos (servidor, IPv4, respaldos): se avisa al dueño.

BEGIN;

ALTER TABLE finance.providers    ADD COLUMN category text NOT NULL DEFAULT 'infra' CHECK (category IN ('infra','ai'));
ALTER TABLE finance.budgets      ADD COLUMN category text NOT NULL DEFAULT 'infra' CHECK (category IN ('infra','ai'));
ALTER TABLE finance.usage_events ADD COLUMN category text NOT NULL DEFAULT 'infra' CHECK (category IN ('infra','ai'));
UPDATE finance.providers SET category = 'ai' WHERE code = 'anthropic';
-- El registro de consumo es append-only y no admite montos negativos: nadie "libera" presupuesto borrando o restando
ALTER TABLE finance.usage_events ADD CONSTRAINT usage_non_negative CHECK (cost_usd_est >= 0 AND quantity >= 0);
REVOKE UPDATE, DELETE ON finance.usage_events FROM crdo_app;
INSERT INTO finance.providers (code, name, billing_kind, notes) VALUES
  ('tailscale', 'Tailscale', 'free', 'Plan Personal: acceso administrativo');

-- ------------------------------------------------------------------
-- Política: tope y reserva por categoría
-- ------------------------------------------------------------------
CREATE TABLE finance.budget_policy (
  category      text PRIMARY KEY CHECK (category IN ('infra','ai')),
  hard_cap_usd  numeric(10,2) NOT NULL CHECK (hard_cap_usd >= 0),
  reserve_pct   numeric(4,1)  NOT NULL CHECK (reserve_pct BETWEEN 10 AND 50),
  alert_pcts    int[] NOT NULL DEFAULT '{50,75,90}',
  updated_by    uuid REFERENCES auth.users(id),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT tope_oficial_infra CHECK (category <> 'infra' OR hard_cap_usd <= 20)   -- decisión del dueño, 7/11/2026
);

-- ------------------------------------------------------------------
-- Perfil impositivo y cambiario (conservador). Valores A VERIFICAR con el banco y ARCA.
-- ------------------------------------------------------------------
CREATE TABLE finance.tax_profile (
  id                boolean PRIMARY KEY DEFAULT true CHECK (id),
  eur_usd           numeric(8,4) NOT NULL CHECK (eur_usd > 0),  -- cambio conservador (incluye margen)
  foreign_vat_pct   numeric(5,2) NOT NULL DEFAULT 0,            -- IVA del país del proveedor, si lo factura
  ar_iva_pct        numeric(5,2) NOT NULL DEFAULT 21,           -- IVA servicios digitales (RG 4240)
  ar_perception_pct numeric(5,2) NOT NULL DEFAULT 30,           -- percepción Ganancias si se paga en pesos; 0 con dólares propios
  ar_iibb_pct       numeric(5,2) NOT NULL DEFAULT 2,            -- Ingresos Brutos (varía por provincia)
  card_fee_pct      numeric(5,2) NOT NULL DEFAULT 1,            -- comisiones bancarias posibles
  verified_at       date,
  notes             text,
  updated_by        uuid REFERENCES auth.users(id),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------
-- Partidas de costo: indispensables y opcionales
-- ------------------------------------------------------------------
CREATE TABLE finance.cost_items (
  code             text PRIMARY KEY,
  provider_code    text NOT NULL REFERENCES finance.providers(code),
  category         text NOT NULL DEFAULT 'infra' CHECK (category IN ('infra','ai')),
  description      text NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('fixed','annual','variable')),
  essential        boolean NOT NULL,
  active           boolean NOT NULL DEFAULT false,
  currency         text NOT NULL CHECK (currency IN ('EUR','USD')),
  net_amount       numeric(12,4) NOT NULL CHECK (net_amount >= 0),  -- fijo: por mes · anual: por año · variable: por unidad
  unit             text,                                            -- variable: 'GB-mes'
  free_quota       numeric NOT NULL DEFAULT 0,                      -- unidades gratis (variable)
  metric           text,                                            -- medidor que alimenta la partida variable
  stop_action      text,                                            -- qué hace el guardián si hay riesgo
  price_verified_at date,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (essential OR stop_action IS NOT NULL),                     -- todo opcional debe poder detenerse
  CHECK (kind <> 'variable' OR (metric IS NOT NULL AND unit IS NOT NULL))
);

-- Medidores (valor actual de consumo, p. ej. GB almacenados)
CREATE TABLE finance.meter_readings (
  id       bigserial PRIMARY KEY,
  metric   text NOT NULL,
  value    numeric NOT NULL CHECK (value >= 0),
  read_at  timestamptz NOT NULL DEFAULT now(),
  source   text NOT NULL
);
CREATE INDEX ON finance.meter_readings (metric, read_at DESC);

-- ------------------------------------------------------------------
-- Datos iniciales (precios netos de fuentes secundarias, nov 2026: verificar antes de contratar)
-- ------------------------------------------------------------------
INSERT INTO finance.budget_policy (category, hard_cap_usd, reserve_pct) VALUES
  ('infra', 20, 15),     -- tope operativo = US$ 17
  ('ai',     0, 15);     -- IA deshabilitada hasta que el dueño fije su presupuesto propio

-- Escenario por defecto = el PEOR razonable: pago con tarjeta en pesos (con percepción del 30 %)
INSERT INTO finance.tax_profile (eur_usd, foreign_vat_pct, ar_iva_pct, ar_perception_pct, ar_iibb_pct, card_fee_pct, notes)
VALUES (1.20, 0, 21, 30, 2, 1,
        'Peor escenario: tarjeta en pesos. Con dólares propios la percepción es 0. Verificar si Hetzner factura IVA a Argentina.');

INSERT INTO finance.cost_items
  (code, provider_code, description, kind, essential, active, currency, net_amount, unit, free_quota, metric, stop_action)
VALUES
  ('hetzner_cx33',     'hetzner',       'VPS CX33 (4 vCPU / 8 GB), Europa',           'fixed',    true,  true,  'EUR', 8.49,  NULL,     0,  NULL,             NULL),
  ('hetzner_ipv4',     'hetzner',       'IPv4 primaria',                               'fixed',    true,  true,  'EUR', 0.50,  NULL,     0,  NULL,             NULL),
  ('b2_backups',       'backblaze_b2',  'Respaldos restic (10 GB gratis)',             'variable', true,  true,  'USD', 0.006, 'GB-mes', 10, 'b2_storage_gb',  NULL),
  ('cloudflare_free',  'cloudflare',    'DNS, protección, Tunnel y Access (plan gratis)', 'fixed', true,  false, 'USD', 0,     NULL,     0,  NULL,             NULL),
  ('tailscale_free',   'tailscale',     'Acceso administrativo (plan Personal)',       'fixed',    true,  true,  'USD', 0,     NULL,     0,  NULL,             NULL),
  ('r2_objects',       'cloudflare_r2', 'Archivos de trabajo (10 GB gratis)',          'variable', false, false, 'USD', 0.015, 'GB-mes', 10, 'r2_storage_gb',  'pause_r2_writes'),
  ('hetzner_backups',  'hetzner',       'Backups automáticos Hetzner (+20 %)',         'fixed',    false, false, 'EUR', 1.70,  NULL,     0,  NULL,             'owner_disable_hetzner_backups'),
  ('domain',           'domain',        'Dominio propio (anual, prorrateado)',         'annual',   false, false, 'USD', 15.00, NULL,     0,  NULL,             'owner_do_not_renew');

INSERT INTO sys.settings (key, value) VALUES
  ('optional_services_paused', 'false'),
  ('storage_writes_enabled',   'true');

-- ------------------------------------------------------------------
-- Cálculo
-- ------------------------------------------------------------------
CREATE FUNCTION finance.gross_factor() RETURNS numeric
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = finance, pg_catalog AS $$
  SELECT (1 + foreign_vat_pct / 100) * (1 + (ar_iva_pct + ar_perception_pct + ar_iibb_pct + card_fee_pct) / 100)
    FROM finance.tax_profile;
$$;

-- Proyección mensual BRUTA en US$ de las partidas activas
CREATE FUNCTION finance.projection(p_category text DEFAULT 'infra')
RETURNS TABLE (code text, essential boolean, kind text, net_usd numeric, gross_usd numeric, stop_action text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = finance, pg_catalog AS $$
  SELECT c.code, c.essential, c.kind, round(n.net, 4), round(n.net * finance.gross_factor(), 4), c.stop_action
    FROM finance.cost_items c
    CROSS JOIN LATERAL (
      SELECT (CASE c.currency WHEN 'EUR' THEN (SELECT eur_usd FROM finance.tax_profile) ELSE 1 END) *
             CASE c.kind
               WHEN 'fixed'  THEN c.net_amount
               WHEN 'annual' THEN c.net_amount / 12
               ELSE greatest(0, coalesce((SELECT m.value FROM finance.meter_readings m WHERE m.metric = c.metric
                                           ORDER BY m.read_at DESC LIMIT 1), 0) - c.free_quota) * c.net_amount
             END AS net) n
   WHERE c.active AND c.category = p_category;
$$;

-- Niveles: ok < 50 % · atencion ≥ 50 % · aviso ≥ 75 % · critico > tope operativo (reserva invadida) · excedido ≥ tope
CREATE FUNCTION finance.budget_status(p_category text DEFAULT 'infra')
RETURNS TABLE (projected_usd numeric, hard_cap_usd numeric, operating_cap_usd numeric,
               pct_of_cap numeric, alert_pct int, level text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = finance, pg_catalog AS $$
DECLARE p finance.budget_policy%ROWTYPE; total numeric;
BEGIN
  SELECT * INTO p FROM finance.budget_policy bp WHERE bp.category = p_category;
  IF p_category = 'infra' THEN
    SELECT coalesce(sum(x.gross_usd), 0) INTO total FROM finance.projection('infra') x;
  ELSE  -- IA: gasto real del mes (lo registra llm_gateway)
    SELECT coalesce(sum(u.cost_usd_est), 0) INTO total FROM finance.usage_events u
     WHERE u.category = 'ai' AND u.occurred_at >= date_trunc('month', now());
  END IF;
  projected_usd     := round(total, 2);
  hard_cap_usd      := p.hard_cap_usd;
  operating_cap_usd := round(p.hard_cap_usd * (1 - p.reserve_pct / 100), 2);
  pct_of_cap        := CASE WHEN p.hard_cap_usd > 0 THEN round(100 * total / p.hard_cap_usd, 1) ELSE 100 END;
  alert_pct         := (SELECT max(a) FROM unnest(p.alert_pcts) a WHERE a <= pct_of_cap);
  level := CASE WHEN total >= p.hard_cap_usd     THEN 'excedido'
                WHEN total >  operating_cap_usd  THEN 'critico'
                WHEN pct_of_cap >= 75            THEN 'aviso'
                WHEN pct_of_cap >= 50            THEN 'atencion'
                ELSE 'ok' END;
  RETURN NEXT;
END $$;

-- ------------------------------------------------------------------
-- Guardián: lo ejecuta budget-guard (host) cada hora
-- ------------------------------------------------------------------
CREATE FUNCTION finance.run_budget_guard()
RETURNS TABLE (level text, projected_usd numeric, operating_cap_usd numeric, actions text[])
LANGUAGE plpgsql SECURITY DEFINER SET search_path = finance, pg_catalog, public AS $$
DECLARE s record; acts text[] := '{}';
BEGIN
  SELECT * INTO s FROM finance.budget_status('infra');
  IF s.level IN ('critico', 'excedido') THEN
    SELECT coalesce(array_agg(DISTINCT x.stop_action ORDER BY x.stop_action), '{}') INTO acts
      FROM finance.projection('infra') x WHERE NOT x.essential;
    UPDATE sys.settings SET value = 'true', updated_at = now() WHERE key = 'optional_services_paused';
    IF 'pause_r2_writes' = ANY (acts) THEN
      UPDATE sys.settings SET value = 'false', updated_at = now() WHERE key = 'storage_writes_enabled';
    END IF;
    INSERT INTO audit.events (actor_type, action, outcome, severity, detail)
    VALUES ('system', 'budget.optional_paused', 'success', 'high',
            jsonb_build_object('projected_usd', s.projected_usd, 'level', s.level, 'actions', acts));
  END IF;
  RETURN QUERY SELECT s.level, s.projected_usd, s.operating_cap_usd, acts;
END $$;

-- Registro de medidores (lo usan el scheduler y backup.sh)
CREATE FUNCTION finance.record_meter(p_metric text, p_value numeric, p_source text) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = finance, pg_catalog AS $$
  INSERT INTO finance.meter_readings (metric, value, source) VALUES (p_metric, p_value, p_source);
$$;

-- IA: presupuesto independiente. llm_gateway llama a esto ANTES de cada llamada.
CREATE FUNCTION finance.ai_authorize(p_estimated_usd numeric) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = finance, pg_catalog AS $$
DECLARE s record;
BEGIN
  SELECT * INTO s FROM finance.budget_status('ai');
  RETURN s.hard_cap_usd > 0 AND s.projected_usd + p_estimated_usd <= s.operating_cap_usd;
END $$;

-- ------------------------------------------------------------------
-- Cambios del dueño (desde el panel, con TOTP de un solo uso)
-- ------------------------------------------------------------------
CREATE FUNCTION finance.set_budget_policy(p_user uuid, p_code text, p_category text,
                                          p_hard_cap numeric, p_reserve_pct numeric)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = finance, pg_catalog, public AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN RETURN 'forbidden'; END IF;
  IF p_category = 'infra' AND p_hard_cap > 20 THEN RETURN 'above_official_cap'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE finance.budget_policy SET hard_cap_usd = p_hard_cap, reserve_pct = p_reserve_pct,
         updated_by = p_user, updated_at = now() WHERE category = p_category;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity, detail)
  VALUES ('user', p_user, 'budget.policy_changed', p_category, 'success', 'warning',
          jsonb_build_object('hard_cap_usd', p_hard_cap, 'reserve_pct', p_reserve_pct));
  RETURN 'updated';
END $$;

CREATE FUNCTION finance.set_tax_profile(p_user uuid, p_code text, p_eur_usd numeric, p_foreign_vat numeric,
                                        p_iva numeric, p_perception numeric, p_iibb numeric, p_fee numeric)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = finance, pg_catalog, public AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN RETURN 'forbidden'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE finance.tax_profile SET eur_usd = p_eur_usd, foreign_vat_pct = p_foreign_vat, ar_iva_pct = p_iva,
         ar_perception_pct = p_perception, ar_iibb_pct = p_iibb, card_fee_pct = p_fee,
         verified_at = current_date, updated_by = p_user, updated_at = now();
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity)
  VALUES ('user', p_user, 'budget.tax_profile_changed', 'success', 'warning');
  RETURN 'updated';  -- si la realidad empeora, el guardián reaccionará en su próxima ejecución
END $$;

-- Activar/desactivar o actualizar el precio de una partida.
-- Un OPCIONAL que haría superar el tope operativo se RECHAZA. Un INDISPENSABLE se registra igual
-- (es la realidad) y se informa 'accepted_over_budget' para que el dueño decida (p. ej. bajar a CX23).
CREATE FUNCTION finance.set_cost_item(p_user uuid, p_code text, p_item text, p_net_amount numeric,
                                      p_active boolean, p_price_verified boolean DEFAULT false)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = finance, pg_catalog, public AS $$
DECLARE old finance.cost_items%ROWTYPE; s record;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN RETURN 'forbidden'; END IF;
  SELECT * INTO old FROM finance.cost_items WHERE code = p_item FOR UPDATE;
  IF NOT FOUND THEN RETURN 'not_found'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE finance.cost_items SET net_amount = p_net_amount, active = p_active, updated_at = now(),
         price_verified_at = CASE WHEN p_price_verified THEN current_date ELSE price_verified_at END
   WHERE code = p_item;
  SELECT * INTO s FROM finance.budget_status('infra');
  IF s.projected_usd > s.operating_cap_usd THEN
    IF NOT old.essential AND (p_active OR p_net_amount > old.net_amount) THEN
      UPDATE finance.cost_items SET net_amount = old.net_amount, active = old.active WHERE code = p_item;
      INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity, detail)
      VALUES ('user', p_user, 'budget.optional_refused', p_item, 'denied', 'warning',
              jsonb_build_object('projected_usd', s.projected_usd, 'operating_cap_usd', s.operating_cap_usd));
      RETURN 'would_exceed_budget';
    END IF;
    INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity, detail)
    VALUES ('user', p_user, 'budget.essential_over_budget', p_item, 'success', 'high',
            jsonb_build_object('projected_usd', s.projected_usd));
    RETURN 'accepted_over_budget';
  END IF;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, detail)
  VALUES ('user', p_user, 'budget.cost_item_changed', p_item, 'success',
          jsonb_build_object('net_amount', p_net_amount, 'active', p_active));
  RETURN 'updated';
END $$;

-- Reanudar opcionales: solo el dueño, con TOTP, y solo si ya no hay riesgo
CREATE FUNCTION finance.resume_optional(p_user uuid, p_code text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = finance, pg_catalog, public AS $$
DECLARE s record;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN RETURN 'forbidden'; END IF;
  SELECT * INTO s FROM finance.budget_status('infra');
  IF s.level IN ('critico', 'excedido') THEN RETURN 'still_at_risk'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE sys.settings SET value = 'false', updated_at = now() WHERE key = 'optional_services_paused';
  UPDATE sys.settings SET value = 'true',  updated_at = now() WHERE key = 'storage_writes_enabled';
  INSERT INTO audit.events (actor_type, actor_user, action, outcome) VALUES ('user', p_user, 'budget.optional_resumed', 'success');
  RETURN 'resumed';
END $$;

-- ------------------------------------------------------------------
-- Permisos
-- ------------------------------------------------------------------
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA finance FROM PUBLIC;
GRANT SELECT ON finance.budget_policy, finance.tax_profile, finance.cost_items, finance.meter_readings
  TO crdo_app, crdo_readonly, crdo_backup;
REVOKE INSERT, UPDATE, DELETE ON finance.budget_policy, finance.tax_profile, finance.cost_items, finance.meter_readings
  FROM crdo_app;
GRANT USAGE ON SCHEMA finance TO crdo_ops;
GRANT EXECUTE ON FUNCTION finance.projection(text), finance.budget_status(text), finance.ai_authorize(numeric),
  finance.record_meter(text, numeric, text) TO crdo_app;
GRANT EXECUTE ON FUNCTION finance.set_budget_policy(uuid, text, text, numeric, numeric),
  finance.set_tax_profile(uuid, text, numeric, numeric, numeric, numeric, numeric, numeric),
  finance.set_cost_item(uuid, text, text, numeric, boolean, boolean),
  finance.resume_optional(uuid, text) TO crdo_app;
GRANT EXECUTE ON FUNCTION finance.run_budget_guard(), finance.budget_status(text),
  finance.record_meter(text, numeric, text) TO crdo_ops;

COMMIT;
