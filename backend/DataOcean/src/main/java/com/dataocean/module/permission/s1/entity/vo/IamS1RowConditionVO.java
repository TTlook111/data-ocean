package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/** 记录条件的无原值预览表示。 */
@Getter
public class IamS1RowConditionVO {
    private final String matchType;
    private final List<IamS1RowPredicateVO> predicates;

    @JsonCreator
    public IamS1RowConditionVO(@JsonProperty("matchType") String matchType,
                               @JsonProperty("predicates") List<IamS1RowPredicateVO> predicates) {
        this.matchType = matchType;
        this.predicates = predicates == null ? List.of() : List.copyOf(predicates);
    }
}
