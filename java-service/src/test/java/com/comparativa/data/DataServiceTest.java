package com.comparativa.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataServiceTest {

    /** Repositorio de prueba: devuelve usuarios fijos o falla, sin tocar Postgres. */
    private record StubRepository(List<User> users, RuntimeException failure) implements UserRepository {

        static StubRepository returning(List<User> users) {
            return new StubRepository(users, null);
        }

        static StubRepository failing(RuntimeException failure) {
            return new StubRepository(null, failure);
        }

        @Override
        public List<User> listUsers(int limit) {
            if (failure != null) {
                throw failure;
            }
            return users;
        }
    }

    @Test
    void simulacionEsperaElRetardo() {
        DataService service = new DataService(StubRepository.returning(List.of()));

        long start = System.nanoTime();
        DataResult result = service.get(false);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(DataService.SIMULATED_DELAY.toMillis());
        assertThat(result.source()).isEqualTo("simulation");
        assertThat(result.count()).isZero();
        assertThat(result.users()).isEmpty();
    }

    @Test
    void databaseDevuelveLosUsuarios() {
        List<User> seeded = List.of(
                new User("1", "admin@datatest.com", true, OffsetDateTime.now()),
                new User("2", "teacher@datatest.com", true, OffsetDateTime.now()));
        DataService service = new DataService(StubRepository.returning(seeded));

        DataResult result = service.get(true);

        assertThat(result.source()).isEqualTo("database");
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.users()).hasSize(2);
        assertThat(result.users().get(0).email()).isEqualTo("admin@datatest.com");
    }

    @Test
    void databaseConFalloDelRepositorioLanzaDatabaseUnavailable() {
        DataService service = new DataService(
                StubRepository.failing(new IllegalStateException("conexión rechazada")));

        assertThatThrownBy(() -> service.get(true))
                .isInstanceOf(DatabaseUnavailableException.class);
    }

    @Test
    void databaseSinFilasDevuelveListaVacia() {
        DataService service = new DataService(StubRepository.returning(null));

        DataResult result = service.get(true);

        assertThat(result.users()).isNotNull().isEmpty();
        assertThat(result.count()).isZero();
    }
}
