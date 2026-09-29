INSERT INTO identity.roles (id, code, name, description)
VALUES
    (gen_random_uuid(), 'CITIZEN', 'Ciudadano', 'Usuario ciudadano del sistema SIMERTPI'),
    (gen_random_uuid(), 'INSPECTOR', 'Controlador', 'Personal municipal encargado del control del estacionamiento'),
    (gen_random_uuid(), 'SUPERVISOR', 'Supervisor', 'Personal encargado de supervisar las operaciones de control'),
    (gen_random_uuid(), 'SIMERTPI_ADMIN', 'Administrador SIMERTPI', 'Administrador funcional del sistema SIMERTPI'),
    (gen_random_uuid(), 'COLLECTIONS', 'Recaudación', 'Personal encargado de procesos de recaudación'),
    (gen_random_uuid(), 'FINANCE', 'Finanzas', 'Personal encargado de procesos financieros'),
    (gen_random_uuid(), 'IT_ADMIN', 'Administrador TI', 'Administrador técnico de la plataforma'),
    (gen_random_uuid(), 'AUDITOR', 'Auditor', 'Usuario encargado de consultar información de auditoría');