package com.dataocean.module.datasource.service;

/**
 * 持有数据源和资料锁时交付治理复核所需的确切资料修订。
 *
 * @param sourceId 所属数据源
 * @param noteId 资料编号
 * @param noteVersion 本次资料聚合版本
 * @param beforeRevisionId 操作前生效的正文修订
 * @param afterRevisionId 实质编辑后的修订，撤下时为空
 */
public record SourceNoteChanged(
        long sourceId, long noteId, int noteVersion, long beforeRevisionId, Long afterRevisionId) {}
