package com.dataocean.module.fieldtag.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import com.dataocean.module.fieldtag.entity.dto.FeedbackRequestDTO;
import com.dataocean.module.fieldtag.mapper.FeedbackReviewMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.UserFeedbackMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserFeedbackServiceImplTest {

    @Mock
    private UserFeedbackMapper feedbackMapper;

    @Mock
    private FeedbackReviewMapper reviewMapper;

    @Mock
    private FieldConfidenceEventMapper eventMapper;

    @Mock
    private DbColumnMetaMapper dbColumnMetaMapper;

    @Mock
    private QueryTaskMapper queryTaskMapper;

    @Mock
    private ConfidenceCalculator confidenceCalculator;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserFeedbackServiceImpl(
                feedbackMapper,
                reviewMapper,
                eventMapper,
                dbColumnMetaMapper,
                queryTaskMapper,
                confidenceCalculator,
                stringRedisTemplate,
                eventPublisher
        );
        setCurrentUser(7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitFeedback_rejectsQueryTaskOwnedByAnotherUser() {
        FeedbackRequestDTO request = buildRequest();
        when(dbColumnMetaMapper.selectById(20L)).thenReturn(buildColumn(1L));
        when(queryTaskMapper.selectById(10L)).thenReturn(buildTask(8L, 1L));

        assertThatThrownBy(() -> service.submitFeedback(request))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(feedbackMapper, confidenceCalculator, stringRedisTemplate);
    }

    @Test
    void submitFeedback_rejectsColumnFromDifferentDatasource() {
        FeedbackRequestDTO request = buildRequest();
        when(dbColumnMetaMapper.selectById(20L)).thenReturn(buildColumn(2L));
        when(queryTaskMapper.selectById(10L)).thenReturn(buildTask(7L, 1L));

        assertThatThrownBy(() -> service.submitFeedback(request))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(feedbackMapper, confidenceCalculator, stringRedisTemplate);
    }

    private FeedbackRequestDTO buildRequest() {
        FeedbackRequestDTO request = new FeedbackRequestDTO();
        request.setQueryTaskId(10L);
        request.setColumnMetaId(20L);
        request.setFeedbackType(UserFeedback.TYPE_LIKE);
        return request;
    }

    private DbColumnMeta buildColumn(Long datasourceId) {
        DbColumnMeta column = new DbColumnMeta();
        column.setId(20L);
        column.setDatasourceId(datasourceId);
        return column;
    }

    private QueryTask buildTask(Long userId, Long datasourceId) {
        QueryTask task = new QueryTask();
        task.setId(10L);
        task.setUserId(userId);
        task.setDatasourceId(datasourceId);
        return task;
    }

    private void setCurrentUser(Long userId) {
        LoginUser loginUser = new LoginUser(
                userId,
                "tester",
                "password",
                "tester",
                List.of("USER"),
                List.of("field-tag:manage"),
                List.of(new SimpleGrantedAuthority("field-tag:manage"))
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
