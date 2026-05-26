package com.dataocean.module.fieldtag.service;

import com.dataocean.module.fieldtag.entity.dto.BatchTagRequestDTO;
import com.dataocean.module.fieldtag.entity.dto.FieldTagRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FieldTagVO;

import java.util.List;

/**
 * 字段标签服务接口
 * <p>
 * 提供字段标签的增删查操作，支持单个打标和批量打标。
 * </p>
 */
public interface FieldTagService {

    /**
     * 为字段添加标签
     *
     * @param request 打标请求参数
     * @return 创建的标签视图对象
     */
    FieldTagVO addTag(FieldTagRequestDTO request);

    /**
     * 批量为多个字段打同一标签
     *
     * @param request 批量打标请求参数
     * @return 成功打标的数量
     */
    int batchAddTags(BatchTagRequestDTO request);

    /**
     * 移除字段标签
     *
     * @param id 标签ID
     */
    void removeTag(Long id);

    /**
     * 查询指定字段的所有标签
     *
     * @param columnMetaId 字段元数据ID
     * @return 标签列表
     */
    List<FieldTagVO> getTagsByColumnMetaId(Long columnMetaId);

    /**
     * 按标签编码查询关联的字段ID列表
     *
     * @param tagCode 标签编码
     * @return 字段元数据ID列表
     */
    List<Long> getColumnIdsByTagCode(String tagCode);

    /**
     * 查询所有预定义标签
     *
     * @return 预定义标签列表（tagCode + tagName + category）
     */
    List<com.dataocean.module.fieldtag.entity.PredefinedTag> listPredefinedTags();
}
