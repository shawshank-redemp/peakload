package com.peakload.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

// A connection pool timeout must be answered 503 with Retry-After and a JSON error, not 500. The services are
// mocked, so no database is needed: the exceptions are thrown from the mocked createOrder, as the real pool would.
@WebMvcTest(OrderController.class)
class OrderControllerDatabaseErrorTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    OrderService orders;

    @MockBean
    OrderStatusService statuses;

    @Test
    void transactionStartFailureIs503WithRetryAfter() throws Exception {
        when(orders.createOrder(any(), any(), any())).thenThrow(new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        mvc.perform(post("/orders")
                        .header("Idempotency-Key", "k-1")
                        .header("X-Client-Id", "c-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("database_unavailable"));
    }

    @Test
    void jdbcConnectionFailureIs503WithRetryAfter() throws Exception {
        when(orders.createOrder(any(), any(), any())).thenThrow(new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        mvc.perform(post("/orders")
                        .header("Idempotency-Key", "k-2")
                        .header("X-Client-Id", "c-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("database_unavailable"));
    }
}
