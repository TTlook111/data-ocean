package com.dataocean.module.prompt.entity.query;
import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper = true)
public class PromptInvocationQuery extends PageQuery {
    private String sceneCode;
}
