-- V51: persistent metadata and assignable permissions for administrative role management.
-- Existing account-role assignments are intentionally preserved.

ALTER TABLE identity.roles
    ADD COLUMN display_name VARCHAR(100),
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN portal_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES identity.users(id) ON DELETE SET NULL,
    ADD COLUMN updated_by UUID REFERENCES identity.users(id) ON DELETE SET NULL;

UPDATE identity.roles
   SET display_name = name,
       is_system = TRUE,
       portal_enabled = code IN ('IT_ADMIN', 'SIMERTPI_ADMIN');

ALTER TABLE identity.roles
    ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE identity.roles
    ADD CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    ADD CONSTRAINT ck_roles_system_codes CHECK (
        is_system = (code IN ('CITIZEN','INSPECTOR','SUPERVISOR','SIMERTPI_ADMIN','COLLECTIONS','FINANCE','IT_ADMIN','AUDITOR'))
    ),
    ADD CONSTRAINT ck_roles_display_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 100),
    ADD CONSTRAINT ck_roles_version CHECK (row_version >= 0);

CREATE TABLE identity.permissions (
    code VARCHAR(80) PRIMARY KEY,
    module_code VARCHAR(40) NOT NULL,
    action_code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300) NOT NULL,
    assignable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_permissions_code CHECK (code ~ '^[a-z][a-z0-9_.-]{2,79}$'),
    CONSTRAINT ck_permissions_module CHECK (module_code ~ '^[a-z][a-z0-9_-]{1,39}$'),
    CONSTRAINT ck_permissions_action CHECK (action_code ~ '^[a-z][a-z0-9_-]{1,31}$'),
    CONSTRAINT ck_permissions_name CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT uq_permissions_module_action UNIQUE (module_code, action_code)
);

CREATE TABLE identity.role_permissions (
    role_id UUID NOT NULL REFERENCES identity.roles(id) ON DELETE RESTRICT,
    permission_code VARCHAR(80) NOT NULL REFERENCES identity.permissions(code) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by UUID REFERENCES identity.users(id) ON DELETE SET NULL,
    PRIMARY KEY (role_id, permission_code)
);

CREATE INDEX idx_role_permissions_permission ON identity.role_permissions(permission_code, role_id);
CREATE INDEX idx_roles_enabled_portal ON identity.roles(enabled, portal_enabled) WHERE enabled AND portal_enabled;

-- Persist delivery outcomes only; invitation secrets and message bodies are never stored here.
CREATE TABLE identity.activation_delivery_attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    actor_user_id UUID REFERENCES identity.users(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACCEPTED_BY_PROVIDER', 'UNCONFIGURED', 'FAILED')),
    provider VARCHAR(40) NOT NULL,
    safe_error_code VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_activation_delivery_provider CHECK (length(btrim(provider)) BETWEEN 1 AND 40),
    CONSTRAINT ck_activation_delivery_error_code CHECK (safe_error_code IS NULL OR safe_error_code ~ '^[A-Z0-9_]{1,64}$'),
    CONSTRAINT ck_activation_delivery_failure_code CHECK (status <> 'FAILED' OR safe_error_code IS NOT NULL)
);
CREATE INDEX idx_activation_delivery_user_date
    ON identity.activation_delivery_attempts(user_id, attempted_at DESC);

-- Caller stores a one-way SHA-256 of a normalized source address, never the address itself.
CREATE TABLE identity.activation_attempt_throttles (
    attempt_key CHAR(64) PRIMARY KEY CHECK (attempt_key ~ '^[0-9a-f]{64}$'),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts SMALLINT NOT NULL CHECK (attempts BETWEEN 0 AND 100),
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_activation_throttle_blocked_until
    ON identity.activation_attempt_throttles(blocked_until) WHERE blocked_until IS NOT NULL;

-- Role identifiers shipped with SIMERTPI remain protected from generic CRUD.
CREATE FUNCTION identity.reject_system_role_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.is_system THEN
            RAISE EXCEPTION 'System roles cannot be created through role management'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_system_roles_immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.is_system THEN
        RAISE EXCEPTION 'System roles are immutable through role management'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_system_roles_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_system_roles_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON identity.roles
    FOR EACH ROW EXECUTE FUNCTION identity.reject_system_role_mutation();

CREATE FUNCTION identity.guard_role_permission_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE target_role_id UUID; target_permission_code VARCHAR(80);
BEGIN
    target_role_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.role_id ELSE NEW.role_id END;
    target_permission_code := CASE WHEN TG_OP = 'DELETE' THEN OLD.permission_code ELSE NEW.permission_code END;
    IF EXISTS (SELECT 1 FROM identity.roles WHERE id = target_role_id AND is_system) THEN
        RAISE EXCEPTION 'System role permissions are immutable through role management'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_system_role_permissions_immutable';
    END IF;
    IF TG_OP <> 'DELETE' AND NOT EXISTS (
        SELECT 1 FROM identity.permissions WHERE code = target_permission_code AND assignable
    ) THEN
        RAISE EXCEPTION 'Permission is not assignable to a custom role'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_role_permission_assignable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_permission_guard
    BEFORE INSERT OR UPDATE OR DELETE ON identity.role_permissions
    FOR EACH ROW EXECUTE FUNCTION identity.guard_role_permission_mutation();

-- Catalog entries correspond to existing administrative HTTP operations. Unknown
-- and unsupported operations are not representable as grants.
INSERT INTO identity.permissions(code, module_code, action_code, name, description, assignable) VALUES
 ('dashboard.view','dashboard','view','Panel principal','Consultar identidad y panel administrativo',TRUE),
 ('reports.view','reports','view','Reportes municipales','Consultar reportes operativos autorizados',TRUE),
 ('reports.financial.view','reports_financial','view','Reportes financieros','Consultar información financiera de reportes',TRUE),
 ('audit.view','audit','view','Auditoría','Consultar el historial administrativo permitido',TRUE),
 ('inspectors.view','inspectors','view','Consultar inspectores','Consultar inspectores municipales',TRUE),
 ('inspectors.manage','inspectors','manage','Administrar inspectores','Crear, editar y cambiar el estado de inspectores',TRUE),
 ('inspections.view','inspections','view','Consultar inspecciones','Consultar inspecciones existentes',TRUE),
 ('inspections.manage','inspections','manage','Administrar inspecciones','Registrar y revisar inspecciones según las operaciones existentes',FALSE),
 ('violations.view','violations','view','Consultar infracciones','Consultar infracciones existentes',TRUE),
 ('violations.manage','violations','manage','Administrar infracciones','Gestionar revisiones permitidas de infracciones',FALSE),
 ('payments.view','payments','view','Consultar pagos','Consultar pagos administrativos',TRUE),
 ('tariffs.view','tariffs','view','Consultar tarifas','Consultar configuraciones y vigencias de tarifas',TRUE),
 ('tariffs.create','tariffs','create','Crear tarifas','Crear configuraciones tarifarias',TRUE),
 ('tariffs.update','tariffs','update','Modificar tarifas','Modificar o finalizar configuraciones tarifarias',TRUE),
 ('billing_days.view','billing_days','view','Consultar días de cobro','Consultar calendarios de cobro',TRUE),
 ('billing_days.create','billing_days','create','Crear días de cobro','Crear calendarios de cobro',TRUE),
 ('billing_days.update','billing_days','update','Modificar días de cobro','Modificar calendarios de cobro',TRUE),
 ('schedules.view','schedules','view','Consultar horarios','Consultar horarios de cobro',TRUE),
 ('schedules.create','schedules','create','Crear horarios','Crear horarios de cobro',TRUE),
 ('schedules.update','schedules','update','Modificar horarios','Modificar horarios de cobro',TRUE),
 ('holidays.view','holidays','view','Consultar feriados','Consultar feriados y excepciones',TRUE),
 ('holidays.create','holidays','create','Crear feriados','Crear excepciones de calendario',TRUE),
 ('holidays.update','holidays','update','Modificar feriados','Modificar o desactivar excepciones',TRUE),
 ('zones.view','zones','view','Consultar zonas','Consultar zonas de estacionamiento',TRUE),
 ('zones.create','zones','create','Crear zonas','Crear zonas de estacionamiento',TRUE),
 ('zones.update','zones','update','Modificar zonas','Modificar el estado y datos de zonas',TRUE),
 ('streets.view','streets','view','Consultar calles','Consultar calles de estacionamiento',TRUE),
 ('streets.create','streets','create','Crear calles','Crear calles asociadas a zonas',TRUE),
 ('streets.update','streets','update','Modificar calles','Modificar calles de estacionamiento',TRUE),
 ('spaces.view','spaces','view','Consultar espacios','Consultar espacios de estacionamiento',TRUE),
 ('spaces.create','spaces','create','Crear espacios','Crear espacios asociados a calles',TRUE),
 ('spaces.update','spaces','update','Modificar espacios','Modificar configuración de espacios',TRUE),
 ('sessions.view','sessions','view','Consultar sesiones','Consultar sesiones de estacionamiento',TRUE),
 ('reconciliation.manage','reconciliation','manage','Conciliación','Ejecutar procesos administrativos de conciliación',TRUE),
 ('users.view','users','view','Consultar usuarios','Consultar cuentas institucionales',FALSE),
 ('users.create','users','create','Crear usuarios','Crear cuentas institucionales invitadas',FALSE),
 ('users.update','users','update','Modificar usuarios','Editar o cambiar el estado de usuarios no privilegiados',FALSE),
 ('users.roles.assign','users','roles','Asignar roles','Asignar roles existentes no protegidos',FALSE),
 ('users.invitations','users','invitations','Gestionar invitaciones','Crear y reenviar invitaciones de usuarios',TRUE),
 ('roles.view','roles','view','Consultar roles','Consultar roles y permisos',FALSE),
 ('roles.create','roles','create','Crear roles','Crear roles personalizados',FALSE),
 ('roles.update','roles','update','Modificar roles','Modificar roles personalizados y sus permisos',FALSE),
 ('permissions.view','permissions','view','Consultar permisos','Consultar el catálogo de permisos soportados',FALSE)
ON CONFLICT (code) DO NOTHING;

-- The Secretary role is a least-privilege, read-only account role. The two grants
-- correspond to existing dashboard identity and GET /api/v1/admin/reports.
INSERT INTO identity.roles(id, code, name, description, display_name, enabled, portal_enabled, is_system)
VALUES (gen_random_uuid(), 'SECRETARIA', 'Secretaría',
        'Personal autorizado para consultar el panel principal y reportes municipales permitidos.',
        'Secretaría', TRUE, TRUE, FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions(role_id, permission_code)
SELECT r.id, p.code
  FROM identity.roles r
  JOIN identity.permissions p ON p.code IN ('dashboard.view', 'reports.view')
 WHERE r.code = 'SECRETARIA'
ON CONFLICT (role_id, permission_code) DO NOTHING;
