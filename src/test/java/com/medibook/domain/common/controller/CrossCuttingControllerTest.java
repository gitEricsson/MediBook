package com.medibook.domain.common.controller;

import com.medibook.security.CustomUserDetailsService;
import com.medibook.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({LookupController.class, PublicController.class})
@AutoConfigureMockMvc(addFilters = false)
public class CrossCuttingControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtTokenProvider                       jwtTokenProvider;
    @MockBean CustomUserDetailsService               userDetailsService;
    @MockBean @SuppressWarnings("rawtypes") RedisTemplate redisTemplate;

    @Test
    @WithMockUser
    void getTimezones_ShouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/lookups/timezones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser
    void getLanguages_ShouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/lookups/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0]").value("English"));
    }

    @Test
    void health_ShouldBePublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void version_ShouldBePublic() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists());
    }
}
