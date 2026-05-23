-- V41: Support doctors being cross-listed in multiple departments
-- and holding multiple specializations.

CREATE TABLE IF NOT EXISTS doctor_departments (
    doctor_id     BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (doctor_id, department_id),
    CONSTRAINT fk_doc_dept_doctor     FOREIGN KEY (doctor_id)     REFERENCES doctors(id)     ON DELETE CASCADE,
    CONSTRAINT fk_doc_dept_department FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE RESTRICT
);

CREATE INDEX idx_doctor_departments_doctor     ON doctor_departments(doctor_id);
CREATE INDEX idx_doctor_departments_department ON doctor_departments(department_id);

CREATE TABLE IF NOT EXISTS doctor_specializations (
    doctor_id             BIGINT       NOT NULL,
    specialization_value  VARCHAR(150) NOT NULL,
    CONSTRAINT fk_doc_spec_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id) ON DELETE CASCADE
);

CREATE INDEX idx_doctor_specializations_doctor ON doctor_specializations(doctor_id);
CREATE INDEX idx_doctor_specializations_value  ON doctor_specializations(specialization_value);
