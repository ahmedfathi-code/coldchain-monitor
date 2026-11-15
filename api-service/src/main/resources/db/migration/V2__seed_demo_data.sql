-- Demo data: one super admin, two pharmacies with an admin and an operator each, and the
-- four devices of the device simulator (ids must match simulator/application.yml).
--
-- Every demo user has the password  Coldchain#2026  (documented in the README).
-- Only its BCrypt hash is stored. These accounts are for local demos only.
--
-- Demo thresholds: 2-8 °C with SHORT durations (excursion 2 min, door 1 min) so that the
-- simulator scenarios produce alerts within minutes. Production devices would use 15 / 5.

INSERT INTO tenant (id, name, created_at)
VALUES ('nile-pharmacy', 'Nile Pharmacy', UTC_TIMESTAMP(6)),
       ('delta-pharmacy', 'Delta Pharmacy', UTC_TIMESTAMP(6));

INSERT INTO app_user (username, password_hash, role, tenant_id)
VALUES ('superadmin@coldchain.local', '$2a$10$xmPBiBzy7hyXujkawJM50uGQOaOG7ZBvYrsOXUUN3Nscinxi.Uic6', 'SUPER_ADMIN', NULL),
       ('admin@nile-pharmacy.local', '$2a$10$xmPBiBzy7hyXujkawJM50uGQOaOG7ZBvYrsOXUUN3Nscinxi.Uic6', 'TENANT_ADMIN', 'nile-pharmacy'),
       ('operator@nile-pharmacy.local', '$2a$10$xmPBiBzy7hyXujkawJM50uGQOaOG7ZBvYrsOXUUN3Nscinxi.Uic6', 'OPERATOR', 'nile-pharmacy'),
       ('admin@delta-pharmacy.local', '$2a$10$xmPBiBzy7hyXujkawJM50uGQOaOG7ZBvYrsOXUUN3Nscinxi.Uic6', 'TENANT_ADMIN', 'delta-pharmacy'),
       ('operator@delta-pharmacy.local', '$2a$10$xmPBiBzy7hyXujkawJM50uGQOaOG7ZBvYrsOXUUN3Nscinxi.Uic6', 'OPERATOR', 'delta-pharmacy');

INSERT INTO device (id, tenant_id, serial_number, name, type, location, min_temp, max_temp,
                    excursion_minutes, door_open_max_minutes, active, created_at, updated_at)
VALUES ('nile-fridge-01', 'nile-pharmacy', 'SN-NF-0001', 'Vaccine fridge', 'FRIDGE', 'Nile Pharmacy - Zamalek, back room',
        2.0, 8.0, 2, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       ('nile-truck-01', 'nile-pharmacy', 'SN-NT-0001', 'Delivery truck 1', 'TRUCK', 'Cairo - Alexandria route',
        2.0, 8.0, 2, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       ('delta-fridge-01', 'delta-pharmacy', 'SN-DF-0001', 'Insulin fridge', 'FRIDGE', 'Delta Pharmacy - Tanta, main floor',
        2.0, 8.0, 2, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
       ('delta-fridge-02', 'delta-pharmacy', 'SN-DF-0002', 'Backup fridge', 'FRIDGE', 'Delta Pharmacy - Tanta, storage',
        2.0, 8.0, 2, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
