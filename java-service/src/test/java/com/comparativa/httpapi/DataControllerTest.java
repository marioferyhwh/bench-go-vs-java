package com.comparativa.httpapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.comparativa.data.DataService;
import com.comparativa.data.User;
import com.comparativa.data.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DataController.class)
@Import({DataControllerTest.TestBeans.class, ApiExceptionHandler.class})
class DataControllerTest {

    @TestConfiguration
    static class TestBeans {

        /** Repositorio en memoria con un usuario sembrado. */
        @Bean
        UserRepository userRepository() {
            return limit -> List.of(
                    new User("1", "admin@datatest.com", true, OffsetDateTime.parse("2026-09-17T10:00:00Z")));
        }

        @Bean
        DataService dataService(UserRepository repository) {
            return new DataService(repository);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        DataMetrics dataMetrics(MeterRegistry registry) {
            return new DataMetrics(registry);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sinParamsUsaSimulacion() throws Exception {
        mockMvc.perform(get("/data"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.source", is("simulation")))
                .andExpect(jsonPath("$.count", is(0)))
                .andExpect(jsonPath("$.users", hasSize(0)));
    }

    @Test
    void conUseDatabaseTrueConsultaLaBase() throws Exception {
        mockMvc.perform(get("/data").param("useDatabase", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("database")))
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.users[0].email", is("admin@datatest.com")))
                .andExpect(jsonPath("$.users[0].createdAt").exists());
    }

    @Test
    void laRespuestaNoIncluyePassword() throws Exception {
        mockMvc.perform(get("/data").param("useDatabase", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void valorInvalidoDevuelve400() throws Exception {
        mockMvc.perform(get("/data").param("useDatabase", "yes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid useDatabase value")));
    }

    @Test
    void metodoNoPermitidoDevuelve405() throws Exception {
        mockMvc.perform(post("/data"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error", is("method not allowed")));
    }
}
