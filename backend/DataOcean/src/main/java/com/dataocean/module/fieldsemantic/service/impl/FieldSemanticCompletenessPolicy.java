package com.dataocean.module.fieldsemantic.service.impl;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticRevision;
import java.util.*;

/** 仅对已明确分类的业务角色检查必要信息，不按字段名强猜单位或枚举。 */
public final class FieldSemanticCompletenessPolicy {
    private FieldSemanticCompletenessPolicy() {}
    public static List<String> missing(FieldSemanticRevision revision,Set<String> tags){
        var missing=new ArrayList<String>();
        if(tags.contains("AMOUNT")&&(revision.getUnit()==null||revision.getUnit().isBlank()))missing.add("金额单位");
        if((tags.contains("ENUM")||tags.contains("STATUS"))&&(revision.getEnumSemantics()==null||revision.getEnumSemantics().isBlank()))missing.add("枚举含义");
        return List.copyOf(missing);
    }
}
