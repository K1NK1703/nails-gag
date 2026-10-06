package gag.nails.identity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * База для тестов на настоящих транзакциях: откат отклонённой операции виден только так. Наследник
 * ставит на себя {@code @Transactional(propagation = NOT_SUPPORTED)} прямо на классе — объявленная на
 * классе аннотация сильнее мета-аннотации {@code @ModuleTest}, а с базового класса её бы перекрыла.
 * Поэтому и {@code @BeforeEach}/{@code @AfterEach} здесь идут вне транзакции.
 *
 * <p>Уборка после каждого теста не зависит от того, где он упал: таблицы {@code identity.*} очищаются
 * целиком (другие тесты в них ничего не фиксируют), а из {@code clients} удаляются карточки, которых не
 * было до теста, с их согласиями и журналом признака. Публичного удаления карточки в {@code clients} нет,
 * поэтому там SQL.
 */
abstract class CommittingIdentityTest extends IdentityTestSupport {

    private final Set<UUID> clientsBefore = new HashSet<>();

    @BeforeEach
    void rememberExistingClients() {
        clientsBefore.clear();
        clientsBefore.addAll(clientIds());
    }

    @AfterEach
    void removeCommitted() {
        jdbc.sql("delete from identity.session").update();
        jdbc.sql("delete from identity.pending_registration").update();
        jdbc.sql("delete from identity.login_event").update();
        jdbc.sql("delete from identity.access_change").update();
        jdbc.sql("delete from identity.account").update();
        for (var clientId : clientIds()) {
            if (!clientsBefore.contains(clientId)) {
                jdbc.sql("delete from clients.consent where client_id = :clientId")
                        .param("clientId", clientId)
                        .update();
                jdbc.sql("delete from clients.prepayment_flag_change where client_id = :clientId")
                        .param("clientId", clientId)
                        .update();
                jdbc.sql("delete from clients.client where id = :clientId")
                        .param("clientId", clientId)
                        .update();
            }
        }
    }

    private Set<UUID> clientIds() {
        return new HashSet<>(
                jdbc.sql("select id from clients.client").query(UUID.class).list());
    }
}
