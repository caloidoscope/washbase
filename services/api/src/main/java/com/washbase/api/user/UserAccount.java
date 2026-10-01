package com.washbase.api.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person who signs in (table {@code users}, Flyway {@code V1__users.sql}). Named {@code UserAccount} to avoid
 * clashing with Spring Security's {@code User}. Never returned from controllers: map to a DTO record.
 *
 * <p>Invariants (also enforced by the schema): {@code email} is stored trimmed and lower-cased; {@code mobile} is
 * stored as {@code +639XXXXXXXXX}; at least one of them is set; at most one active ADMIN.
 */
@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 254)
	private String email;

	@Column(length = 13)
	private String mobile;

	@Column(name = "password_hash")
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Role role;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserAccount() {
	}

	/**
	 * @param email already normalized (trimmed, lower-cased) or {@code null}
	 * @param mobile already normalized ({@code +639XXXXXXXXX}) or {@code null}
	 */
	public UserAccount(String name, String email, String mobile, String passwordHash, Role role,
			boolean mustChangePassword) {
		this.name = name;
		this.email = email;
		this.mobile = mobile;
		this.passwordHash = passwordHash;
		this.role = role;
		this.mustChangePassword = mustChangePassword;
	}

	/**
	 * Restores an inactive account with a new password that must be changed at the next sign-in (the Admin
	 * bootstrap's recovery path, ADR-001).
	 */
	public void reactivate(String newPasswordHash) {
		this.active = true;
		this.passwordHash = newPasswordHash;
		this.mustChangePassword = true;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getMobile() {
		return mobile;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
