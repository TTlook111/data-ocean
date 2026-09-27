package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.entity.Conversation;
import com.dataocean.module.query.entity.ConversationMessage;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplTest {

    @BeforeAll
    static void initTableMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMessage.class);
    }

    @Test
    void concurrentTurnReservationFailsClosedWhenAtomicClaimLoses() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        ConversationServiceImpl service = new ConversationServiceImpl(conversations, messages);
        when(conversations.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.acquireTurn(5L, 7L, 2L, "task-b"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("另一个问题");
    }

    @Test
    void cursorPageReturnsStableAscendingIdsAndHasMoreFlag() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        Conversation conversation = Conversation.builder().id(5L).userId(7L).datasourceId(2L)
                .status("ACTIVE").updatedAt(LocalDateTime.now()).build();
        when(conversations.selectById(5L)).thenReturn(conversation);
        when(messages.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>(List.of(
                ConversationMessage.builder().id(10L).conversationId(5L).role("assistant").content("10").build(),
                ConversationMessage.builder().id(9L).conversationId(5L).role("user").content("9").build(),
                ConversationMessage.builder().id(8L).conversationId(5L).role("assistant").content("8").build())));
        ConversationServiceImpl service = new ConversationServiceImpl(conversations, messages);

        var page = service.listMessagePage(5L, 7L, 20L, 2);

        assertThat(page.getItems()).extracting("id").containsExactly(9L, 10L);
        assertThat(page.getNextBeforeMessageId()).isEqualTo(9L);
        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void deletedConversationIsNotVisibleAndCannotBeContinued() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        Conversation deleted = Conversation.builder().id(5L).userId(7L).datasourceId(2L)
                .status("DELETED").updatedAt(LocalDateTime.now()).build();
        when(conversations.selectById(5L)).thenReturn(deleted);
        ConversationServiceImpl service = new ConversationServiceImpl(conversations, messages);

        assertThat(service.isVisible(5L, 7L)).isFalse();
        assertThatThrownBy(() -> service.getOrCreateConversation(7L, 2L, 5L, "继续提问"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能继续提问");
        verify(messages, never()).selectList(any());
    }

    @Test
    void deletingConversationReturnsTheTaskToCancelAndTombstonesItImmediately() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
        Conversation conversation = Conversation.builder().id(5L).userId(7L).datasourceId(2L)
                .status("ACTIVE").activeTurnTaskId("running-task").updatedAt(LocalDateTime.now()).build();
        when(conversations.selectById(5L)).thenReturn(conversation);
        ConversationServiceImpl service = new ConversationServiceImpl(conversations, messages);

        String taskId = service.deleteConversation(5L, 7L);

        assertThat(taskId).isEqualTo("running-task");
        assertThat(conversation.getStatus()).isEqualTo("DELETED");
        assertThat(conversation.getActiveTurnTaskId()).isNull();
        verify(conversations).updateById(conversation);
    }
}
