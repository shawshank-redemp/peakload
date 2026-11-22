package com.peakload.dispatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

// A web-layer slice: only the controller is loaded, so no datasource, Flyway or Kafka is created. A full
// @SpringBootTest here connected to the live database (localhost:15432/peakload) and ran Flyway against it.
@WebMvcTest(PingController.class)
class PingTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pingReturnsServiceName() throws Exception {
        mvc.perform(get("/api/ping"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("dispatch"));
    }
}
