CREATE TABLE patient_access_grants (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    patient_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    reason VARCHAR(500),
    granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    CONSTRAINT fk_pag_patient FOREIGN KEY (patient_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pag_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id) ON DELETE CASCADE,
    CONSTRAINT uk_patient_doctor_grant UNIQUE (patient_id, doctor_id)
);

CREATE INDEX idx_pag_patient_status ON patient_access_grants(patient_id, status);
CREATE INDEX idx_pag_doctor_status ON patient_access_grants(doctor_id, status);
