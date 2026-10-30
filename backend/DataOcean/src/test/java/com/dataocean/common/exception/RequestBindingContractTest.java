package com.dataocean.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestBindingContractTest {
    @RestController static class Probe {
        @GetMapping("/binding") public Long required(@RequestParam Long datasourceId){ return datasourceId; }
    }
    @Test void missingAndMistypedParametersAre400AndNeverEchoTheInput() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/binding")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/binding").param("datasourceId","private-invalid-value"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-invalid-value"))));
    }
}
