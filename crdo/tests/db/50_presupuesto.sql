-- Control presupuestario: tope US$ 20 todo incluido, reserva, alertas, pausa de opcionales, IA separada.
-- Cálculos esperados (escenario por defecto = tarjeta en pesos):
--   factor = 1 + (21 IVA + 30 percepción + 2 IIBB + 1 comisión)/100 = 1,54 ; EUR→USD 1,20
--   CX33  8,49 € → 10,188 US$ neto → 15,69 bruto ; IPv4 0,50 € → 0,60 → 0,92 ; total 16,61
\set ON_ERROR_STOP 1

INSERT INTO auth.users (id, email, display_name, role, password_hash) VALUES
  ('00000000-0000-0000-0000-0000000000b1', 'fin1@crdo.test', 'Fin 1', 'owner', 'x'),
  ('00000000-0000-0000-0000-0000000000b2', 'fin2@crdo.test', 'Fin 2', 'owner', 'x'),
  ('00000000-0000-0000-0000-0000000000b3', 'fin3@crdo.test', 'Fin 3', 'owner', 'x'),
  ('00000000-0000-0000-0000-0000000000b4', 'fin4@crdo.test', 'Fin 4', 'owner', 'x');
INSERT INTO auth.totp_secrets (user_id, secret)
  SELECT id, gen_random_bytes(20) FROM auth.users WHERE email LIKE 'fin%';
\set b1 '''00000000-0000-0000-0000-0000000000b1'''
\set b2 '''00000000-0000-0000-0000-0000000000b2'''
\set b3 '''00000000-0000-0000-0000-0000000000b3'''
\set b4 '''00000000-0000-0000-0000-0000000000b4'''
SELECT t.code(:b1) AS b1a, t.code(:b1, 1) AS b1b, t.code(:b2) AS b2a, t.code(:b2, 1) AS b2b,
       t.code(:b3) AS b3a, t.code(:b3, 1) AS b3b, t.code(:b4) AS b4a \gset

-- Tope oficial inviolable
SELECT t.must_fail($$UPDATE finance.budget_policy SET hard_cap_usd = 25 WHERE category = 'infra'$$,
                   'tope de infraestructura > US$ 20 imposible (ni superusuario)');
SET ROLE crdo_app;
SELECT t.ok(finance.set_budget_policy(:b4, '000000', 'infra', 25, 15) = 'above_official_cap',
            'ni el dueño puede subir el tope por encima de US$ 20');
SELECT t.must_fail($$UPDATE finance.budget_policy SET reserve_pct = 10$$, 'app no puede tocar la política');
SELECT t.must_fail($$UPDATE finance.tax_profile SET ar_perception_pct = 0$$, 'app no puede tocar impuestos');
SELECT t.must_fail($$UPDATE finance.cost_items SET active = true WHERE code = 'domain'$$, 'app no puede activar partidas');

-- Peor escenario (tarjeta en pesos)
SELECT t.ok((SELECT projected_usd = 16.61 AND operating_cap_usd = 17.00 AND level = 'aviso'
               FROM finance.budget_status('infra')),
            'CX33 + IPv4 en el peor escenario: US$ 16,61 (<= tope operativo 17, nivel aviso)');
SELECT t.ok(finance.set_cost_item(:b1, :'b1a', 'domain', 15, true) = 'would_exceed_budget',
            'peor escenario: activar dominio (US$ 1,93/mes bruto) superaría la reserva -> RECHAZADO');
SELECT t.ok(NOT (SELECT active FROM finance.cost_items WHERE code = 'domain'), 'el dominio sigue inactivo');

-- Escenario pagando con dólares propios (sin percepción del 30 %)
SELECT t.ok(finance.set_tax_profile(:b1, :'b1b', 1.20, 0, 21, 0, 2, 1) = 'updated', 'perfil: pago con dólares propios');
SELECT t.ok((SELECT projected_usd = 13.38 AND level = 'atencion' FROM finance.budget_status('infra')),
            'con dólares propios: US$ 13,38');
SELECT t.ok(finance.set_cost_item(:b2, :'b2a', 'domain', 15, true) = 'updated',
            'con dólares propios el dominio entra (US$ 14,93)');

-- Opcional variable que se dispara: R2
SELECT t.ok(finance.set_cost_item(:b2, :'b2b', 'r2_objects', 0.015, true) = 'updated', 'R2 activado');
SELECT finance.record_meter('r2_storage_gb', 9, 'prueba');
SELECT t.ok((SELECT projected_usd = 14.93 FROM finance.budget_status('infra')), 'R2 dentro de la cuota gratis: costo 0');
SELECT finance.record_meter('r2_storage_gb', 300, 'prueba');
SELECT t.ok((SELECT level = 'excedido' FROM finance.budget_status('infra')), '300 GB en R2 -> proyección supera US$ 20');
RESET ROLE;

SET ROLE crdo_ops;
SELECT level AS glevel, array_to_string(actions, ',') AS gacts FROM finance.run_budget_guard() \gset
RESET ROLE;
SELECT t.ok(:'glevel' = 'excedido', 'guardián detecta el riesgo');
SELECT t.ok(:'gacts' = 'owner_do_not_renew,pause_r2_writes', 'acciones solo sobre opcionales: ' || :'gacts');
SELECT t.ok((SELECT value = 'false'::jsonb FROM sys.settings WHERE key = 'storage_writes_enabled'), 'escrituras en R2 pausadas');
SELECT t.ok((SELECT value = 'true'::jsonb FROM sys.settings WHERE key = 'optional_services_paused'), 'opcionales en pausa');
SELECT t.ok(EXISTS (SELECT 1 FROM audit.events WHERE action = 'budget.optional_paused'), 'pausa auditada');

SET ROLE crdo_app;
SELECT t.ok(finance.resume_optional(:b3, :'b3a') = 'still_at_risk', 'no se reanuda mientras siga el riesgo');
SELECT finance.record_meter('r2_storage_gb', 5, 'prueba');   -- se liberó espacio
SELECT t.ok(finance.resume_optional(:b3, :'b3a') = 'resumed', 'riesgo resuelto + TOTP: reanudado');

-- Indispensable que sube de precio: se registra (es la realidad) y se avisa
SELECT t.ok(finance.set_cost_item(:b3, :'b3b', 'hetzner_cx33', 12, true) = 'accepted_over_budget',
            'subida de precio del servidor: aceptada con alerta (decisión del dueño: p. ej. bajar a CX23)');
RESET ROLE;
UPDATE finance.cost_items SET net_amount = 8.49 WHERE code = 'hetzner_cx33';

-- IA: presupuesto separado
SET ROLE crdo_app;
SELECT t.ok(NOT finance.ai_authorize(0.01), 'IA sin presupuesto asignado: bloqueada');
SELECT infra_before.projected_usd AS ib FROM finance.budget_status('infra') infra_before \gset
SELECT t.ok(finance.set_budget_policy(:b4, :'b4a', 'ai', 10, 15) = 'updated', 'dueño fija US$ 10 para IA (operativo 8,50)');
INSERT INTO finance.usage_events (provider_code, category, metric, quantity, cost_usd_est)
  VALUES ('anthropic', 'ai', 'output_tokens', 100000, 8.00);
SELECT t.ok(finance.ai_authorize(0.40), 'IA: 8,00 + 0,40 <= 8,50 autorizado');
SELECT t.ok(NOT finance.ai_authorize(1.00), 'IA: 8,00 + 1,00 > 8,50 bloqueado');
SELECT t.ok((SELECT projected_usd = :ib FROM finance.budget_status('infra')), 'el gasto de IA no consume el presupuesto de infraestructura');
SELECT t.must_fail($$DELETE FROM finance.usage_events WHERE category = 'ai'$$, 'app no puede borrar consumo para liberar presupuesto');
SELECT t.must_fail($$INSERT INTO finance.usage_events (provider_code, category, metric, quantity, cost_usd_est) VALUES ('anthropic','ai','x',1,-5)$$,
                   'app no puede registrar costos negativos');
RESET ROLE;
