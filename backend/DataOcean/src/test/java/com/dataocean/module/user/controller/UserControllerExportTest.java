package com.dataocean.module.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerExportTest {

    @Test
    void exportsAllPagesWithinSizeLimitAndOverridesIncomingPageAliases() throws Exception {
        UserService service = mock(UserService.class);
        List<Long> requestedPages = new ArrayList<>();
        when(service.listUsers(any())).thenAnswer(invocation -> {
            UserQuery query = invocation.getArgument(0);
            assertThat(query.getUsername()).isEqualTo("candidate");
            Page<UserVO> result = query.toMpPageDefaultSortByCreatedAtDesc();
            result.setTotal(205);
            requestedPages.add(result.getCurrent());
            result.setRecords(LongStream.range(result.offset(), Math.min(result.offset() + result.getSize(), 205))
                    .mapToObj(index -> UserVO.builder()
                            .id(index + 1)
                            .username("user" + (index + 1))
                            .build()).toList());
            return result;
        });
        UserQuery query = new UserQuery();
        query.setUsername("candidate");
        query.setPageNo(99L);
        query.setPage(88L);
        query.setPageNum(77L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UserController(service).exportUsers(query, response);

        assertThat(requestedPages).containsExactly(1L, 2L, 3L);
        assertThat(response.getContentAsString().lines().count()).isEqualTo(206);
        assertThat(response.getContentAsString()).contains("205,user205,");
    }
}
