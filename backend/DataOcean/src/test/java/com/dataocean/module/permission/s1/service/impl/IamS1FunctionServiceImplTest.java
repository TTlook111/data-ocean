package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamS1FunctionServiceImplTest {

    @Test
    void roleSaveResolutionContainsServerSideDependencies() {
        IamS1FunctionMapper mapper = mock(IamS1FunctionMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                function(1L, "query:use"), function(2L, "query:sql:view")));

        List<IamS1Function> functions = new IamS1FunctionServiceImpl(mapper)
                .resolveExpanded(List.of("query:sql:view"));

        assertThat(functions).extracting(IamS1Function::getFunctionCode)
                .containsExactly("query:use", "query:sql:view");
    }

    private IamS1Function function(Long id, String code) {
        IamS1Function function = new IamS1Function();
        function.setId(id);
        function.setFunctionCode(code);
        function.setStatus("ACTIVE");
        return function;
    }
}
