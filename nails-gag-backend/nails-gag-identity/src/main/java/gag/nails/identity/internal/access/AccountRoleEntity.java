package gag.nails.identity.internal.access;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Назначение роли аккаунту. */
@Entity
@Table(schema = "identity", name = "account_role")
public class AccountRoleEntity {

    @EmbeddedId
    private AccountRoleId id;

    protected AccountRoleEntity() {}

    public AccountRoleEntity(UUID accountId, UUID roleId) {
        this.id = new AccountRoleId(accountId, roleId);
    }

    UUID accountId() {
        return id.accountId();
    }

    public UUID roleId() {
        return id.roleId();
    }
}
