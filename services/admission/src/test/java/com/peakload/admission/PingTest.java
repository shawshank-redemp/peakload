package com.peakload.admission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

// Loads only the web layer: no datasource, no Flyway, no upstream client, so no database is needed.
// It used to be a full @SpringBootTest; the database and the proxy now make that slow and container-bound.
@WebMvcTest(PingController.class)
class PingTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pingReturnsServiceName() throws Exception {
        mvc.perform(get("/api/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("admission"));
    }
}
